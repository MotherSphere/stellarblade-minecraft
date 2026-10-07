#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include "input.hpp"
#include "pose_stream.hpp"
#include "raw_capture.hpp"
#include <array>
#include <bcrypt.h>
#include <intrin.h>
#include <mutex>
#include <thread>
#include <xinput.h>

namespace {
using GetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_STATE *);
using SetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_VIBRATION *);
GetStateFn backend_get = nullptr;
SetStateFn backend_set = nullptr;
std::once_flag initialize_once;
std::mutex state_lock;
stellar_input::Core core;
bool verified = false;
uintptr_t gameplay_return = 0;
std::wstring directory;
stellar_input::Pad previous_pad;
DWORD packet_number = 0;
// Pinned shipping build: only the slot-0 gameplay poll advances edges.
// Its separate device-discovery calls may only observe the cached state.
constexpr uintptr_t return_rva = 0x2bf7a3b, check_rva = 0x2bf7a28;
// GUObjectArray of the same pinned build (inlined FUObjectArray::IndexToObject).
constexpr uintptr_t objects_rva = 0x6dd7230;
constexpr std::array<unsigned char, 19> call_bytes = {
    0x0f, 0x57, 0xc0, 0x48, 0x8d, 0x55, 0x90, 0x33, 0xc9, 0x0f,
    0x11, 0x45, 0x90, 0xff, 0x15, 0x3d, 0x6f, 0x9b, 0x02};
constexpr std::array<unsigned char, 32> expected_hash = {
    0x57, 0x3a, 0xaf, 0xf1, 0xc9, 0x45, 0x5f, 0x85, 0xea, 0x60, 0x36,
    0xef, 0x6f, 0x6c, 0xcb, 0x07, 0x74, 0xde, 0x41, 0x64, 0x72, 0x2a,
    0x29, 0x62, 0x76, 0xfb, 0xbf, 0xc7, 0xfa, 0x87, 0x54, 0x5c};
uint64_t wall_ms() {
  FILETIME f;
  GetSystemTimeAsFileTime(&f);
  ULARGE_INTEGER n;
  n.LowPart = f.dwLowDateTime;
  n.HighPart = f.dwHighDateTime;
  return n.QuadPart / 10000 - 11644473600000ULL;
}
std::wstring module_path(HMODULE module) {
  std::wstring path(32768, L'\0');
  DWORD n =
      GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
  if (!n || n == path.size())
    return {};
  path.resize(n);
  return path;
}
std::optional<std::string> read_file(const std::wstring &path, DWORD bound) {
  HANDLE f =
      CreateFileW(path.c_str(), GENERIC_READ,
                  FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                  nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (f == INVALID_HANDLE_VALUE)
    return {};
  std::string s(bound + 1, '\0');
  DWORD n = 0;
  BOOL ok = ReadFile(f, s.data(), bound + 1, &n, nullptr);
  CloseHandle(f);
  if (!ok || n > bound)
    return {};
  s.resize(n);
  return s;
}
bool hash_matches(const std::wstring &path) {
  HANDLE f = CreateFileW(path.c_str(), GENERIC_READ,
                         FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                         OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (f == INVALID_HANDLE_VALUE)
    return false;
  BCRYPT_ALG_HANDLE algorithm = nullptr;
  BCRYPT_HASH_HANDLE hash = nullptr;
  std::array<unsigned char, 32> digest{};
  bool ok = BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM,
                                        nullptr, 0) >= 0;
  if (ok)
    ok = BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0) >= 0;
  std::array<unsigned char, 65536> buffer{};
  DWORD n = 0;
  while (ok) {
    if (!ReadFile(f, buffer.data(), static_cast<DWORD>(buffer.size()), &n,
                  nullptr)) {
      ok = false;
      break;
    }
    if (!n)
      break;
    ok = BCryptHashData(hash, buffer.data(), n, 0) >= 0;
  }
  if (ok)
    ok = BCryptFinishHash(hash, digest.data(),
                          static_cast<ULONG>(digest.size()), 0) >= 0;
  if (hash)
    BCryptDestroyHash(hash);
  if (algorithm)
    BCryptCloseAlgorithmProvider(algorithm, 0);
  CloseHandle(f);
  return ok && digest == expected_hash;
}
bool verify_host() {
  auto module = GetModuleHandleW(nullptr);
  auto base = reinterpret_cast<unsigned char *>(module);
#ifdef STELLAR_INPUT_TEST_HOST
  // A distinct fixture DLL only. Production has no environment/configuration
  // bypass.
  auto path = module_path(module);
  if (path.substr(path.find_last_of(L"\\/") + 1) !=
      L"stellar_input_fake_host.exe")
    return false;
  auto entry = GetProcAddress(module, "FakeGameplayReturn");
  if (!entry)
    return false;
  gameplay_return = reinterpret_cast<uintptr_t>(entry);
  return true;
#else
  auto dos = reinterpret_cast<IMAGE_DOS_HEADER *>(base);
  if (dos->e_magic != IMAGE_DOS_SIGNATURE || dos->e_lfanew < 0 ||
      dos->e_lfanew > 4096)
    return false;
  auto pe = reinterpret_cast<IMAGE_NT_HEADERS64 *>(base + dos->e_lfanew);
  if (pe->Signature != IMAGE_NT_SIGNATURE || pe->FileHeader.Machine != 0x8664 ||
      pe->FileHeader.TimeDateStamp != 0x6a6a3b74 ||
      pe->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC ||
      pe->OptionalHeader.SizeOfImage != 0x15981000)
    return false;
  if (!std::equal(call_bytes.begin(), call_bytes.end(), base + check_rva) ||
      !hash_matches(module_path(module)))
    return false;
  gameplay_return = reinterpret_cast<uintptr_t>(base) + return_rva;
  stellar_pose::initialize(reinterpret_cast<uintptr_t>(base) + objects_rva,
                           reinterpret_cast<uintptr_t>(base) + stellar_pose::BoomVtableRva);
  return true;
#endif
}
bool read_directory(HMODULE self) {
  auto path = module_path(self);
  auto slash = path.find_last_of(L"\\/");
  if (slash == std::wstring::npos)
    return false;
  auto config = read_file(
      path.substr(0, slash + 1) + L"stellar-input-directory.txt", 4096);
  if (!config)
    return false;
  if (!config->empty() && config->back() == '\n')
    config->pop_back();
  if (config->empty() || std::any_of(config->begin(), config->end(),
                                     [](unsigned char c) { return c < 32; }))
    return false;
  int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, config->data(),
                              static_cast<int>(config->size()), nullptr, 0);
  if (!n)
    return false;
  directory.resize(n);
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, config->data(),
                      static_cast<int>(config->size()), directory.data(), n);
  bool drive = directory.size() >= 3 &&
               ((directory[0] >= 'A' && directory[0] <= 'Z') ||
                (directory[0] >= 'a' && directory[0] <= 'z')) &&
               directory[1] == L':' &&
               (directory[2] == L'\\' || directory[2] == L'/');
  bool unc =
      directory.size() > 2 && directory[0] == L'\\' && directory[1] == L'\\';
  if (!drive && !unc)
    return false;
  if (directory.back() != L'\\' && directory.back() != L'/')
    directory += L'\\';
  return true;
}
bool write_ack(const std::string &bytes) {
  auto path = directory + L"eve-input-ack.txt", temp = path + L".tmp";
  HANDLE f = CreateFileW(temp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL, nullptr);
  if (f == INVALID_HANDLE_VALUE)
    return false;
  DWORD n = 0;
  bool ok = WriteFile(f, bytes.data(), static_cast<DWORD>(bytes.size()), &n,
                      nullptr) &&
            n == bytes.size();
  CloseHandle(f);
  return ok &&
         MoveFileExW(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING);
}
void worker() {
  std::string published, slot0, slot1;
  // Input every 5 ms keeps forwarding latency low; permits, capture and pose
  // files keep their 20 ms period. Cached permit bytes are re-validated.
  for (unsigned iteration = 0;; ++iteration) {
    bool slow = iteration % 4 == 0;
    auto input_bytes =
        read_file(directory + L"eve-input.txt", 4096).value_or("");
    if (slow) {
      slot0 = read_file(directory + L"eve-control-permit.0", 512).value_or("");
      slot1 = read_file(directory + L"eve-control-permit.1", 512).value_or("");
    }
    auto input = stellar_input::parse_input(input_bytes);
    auto permit = stellar_input::select_permit(slot0, slot1);
    std::string permit_bytes;
    if (permit) {
      auto p0 = stellar_input::parse_permit(slot0);
      permit_bytes = p0 && p0->seq == permit->seq ? slot0 : slot1;
    }
    uint64_t wall = wall_ms(), now = GetTickCount64();
    std::optional<stellar_input::Ack> ack;
    {
      std::lock_guard<std::mutex> lock(state_lock);
      core.update(input, permit, input_bytes, permit_bytes, wall, now);
      ack = core.receipt(wall);
    }
    if (ack) {
      auto stamp = ack->time;
      // An unchanged receipt must not appear to be a new liveness heartbeat.
      ack->time = 0;
      auto key = stellar_input::encode_ack(*ack);
      if (key != published) {
        ack->time = stamp;
        if (write_ack(stellar_input::encode_ack(*ack)))
          published = std::move(key);
      }
    }
    if (!slow) {
      Sleep(5);
      continue;
    }
    auto capture_request =
        read_file(directory + L"eve-stream.txt", 512).value_or("");
    stellar_raw::poll_raw_capture(capture_request, wall_ms());
    try {
      stellar_pose::poll_files(
          directory,
          read_file(directory + L"eve-pose-request.txt", 512).value_or(""),
          read_file(directory + L"eve-pose-components.0", 65536).value_or(""),
          read_file(directory + L"eve-pose-components.1", 65536).value_or(""),
          wall_ms(), read_file(directory + L"eve-lock.txt", 256).value_or(""));
    } catch (...) {
      // The pose stream is optional and must never stop input forwarding.
    }
    Sleep(5);
  }
}
void initialize() {
  wchar_t system[MAX_PATH];
  UINT n = GetSystemDirectoryW(system, MAX_PATH);
  if (n && n < MAX_PATH) {
    std::wstring path(system, n);
    path += L"\\xinput1_4.dll";
    HMODULE backend = LoadLibraryW(path.c_str());
    if (backend) {
      backend_get = reinterpret_cast<GetStateFn>(
          GetProcAddress(backend, "XInputGetState"));
      backend_set = reinterpret_cast<SetStateFn>(
          GetProcAddress(backend, "XInputSetState"));
    }
  }
  if (!backend_get || !backend_set || !verify_host())
    return;
  HMODULE self = nullptr;
  if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                              GET_MODULE_HANDLE_EX_FLAG_PIN,
                          reinterpret_cast<LPCWSTR>(&initialize), &self) ||
      !read_directory(self))
    return;
  // Keep the DLL and its sole worker alive until process termination.
  verified = true;
  try {
    stellar_raw::initialize_raw_capture(GetModuleHandleW(nullptr), directory);
  } catch (...) {
    // Raw capture is optional; failure must not break the verified input path.
  }
  try {
    std::thread(worker).detach();
  } catch (...) {
    verified = false;
  }
}
DWORD get_state(DWORD index, XINPUT_STATE *state, void *caller) {
  std::call_once(initialize_once, initialize);
  if (!state)
    return ERROR_BAD_ARGUMENTS;
  if (!verified || index != 0)
    return backend_get ? backend_get(index, state) : ERROR_DEVICE_NOT_CONNECTED;
  stellar_input::Core::Result result;
  bool gameplay = reinterpret_cast<uintptr_t>(caller) == gameplay_return;
  if (gameplay) {
    // Game thread, between frames: last frame's animation and physics are final.
    try {
      std::string owner;
      {
        std::lock_guard<std::mutex> lock(state_lock);
        owner = core.forwarding_run();
      }
      stellar_pose::on_gameplay_poll(owner);
    } catch (...) {
    }
  }
  {
    std::lock_guard<std::mutex> lock(state_lock);
    result = core.poll(gameplay, wall_ms(), GetTickCount64());
    if (result.override) {
      if (result.pad != previous_pad) {
        previous_pad = result.pad;
        ++packet_number;
      }
      *state = {};
      state->dwPacketNumber = packet_number;
      state->Gamepad.wButtons = static_cast<WORD>(result.pad.buttons);
      state->Gamepad.bLeftTrigger = (result.pad.buttons & 0x10000) ? 255 : 0;
      state->Gamepad.bRightTrigger = (result.pad.buttons & 0x20000) ? 255 : 0;
      state->Gamepad.sThumbLX = result.pad.lx;
      state->Gamepad.sThumbLY = result.pad.ly;
      state->Gamepad.sThumbRX = result.pad.rx;
      state->Gamepad.sThumbRY = result.pad.ry;
      return ERROR_SUCCESS;
    }
  }
  DWORD status = backend_get(index, state);
  if (status == ERROR_DEVICE_NOT_CONNECTED) {
    *state = {};
    return ERROR_SUCCESS;
  }
  return status;
}
} // namespace
// The return address MUST be captured in the exported entry, before any helper.
extern "C" DWORD WINAPI XInputGetState(DWORD index, XINPUT_STATE *state) {
  return get_state(index, state, _ReturnAddress());
}
extern "C" DWORD WINAPI XInputSetState(DWORD index,
                                       XINPUT_VIBRATION *vibration) {
  std::call_once(initialize_once, initialize);
  if (!vibration)
    return ERROR_BAD_ARGUMENTS;
  if (verified && index == 0) {
    std::lock_guard<std::mutex> lock(state_lock);
    if (core.owns_remote())
      return ERROR_SUCCESS;
  }
  return backend_set ? backend_set(index, vibration)
                     : ERROR_DEVICE_NOT_CONNECTED;
}
