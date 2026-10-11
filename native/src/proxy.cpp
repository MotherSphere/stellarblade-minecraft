#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <dbt.h>

#include "input.hpp"
#include "pose_stream.hpp"
#include "raw_capture.hpp"
#include <array>
#include <atomic>
#include <bcrypt.h>
#include <cstring>
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
// Slot-0 XInputGetState calls by caller, for eve-input-calls.txt (CallReport).
std::atomic<uint32_t> gameplay_calls{0}, other_calls{0};
// Pinned shipping build: only the slot-0 gameplay poll advances edges.
// Its separate device-discovery calls may only observe the cached state.
constexpr uintptr_t return_rva = 0x2bf7a3b, check_rva = 0x2bf7a28;
// GUObjectArray of the same pinned build (inlined FUObjectArray::IndexToObject).
constexpr uintptr_t objects_rva = 0x6dd7230;
constexpr std::array<unsigned char, 19> call_bytes = {
    0x0f, 0x57, 0xc0, 0x48, 0x8d, 0x55, 0x90, 0x33, 0xc9, 0x0f,
    0x11, 0x45, 0x90, 0xff, 0x15, 0x3d, 0x6f, 0x9b, 0x02};
// Stellar Blade's walk action has no controller key, and only its key test
// starts the sprint-to-walk transit. That test (USBPlayerInput, RVA 0x23c9860)
// has one caller, the input tick's call at RVA 0x23c8d3a, which is redirected
// to force_walk: held while Minecraft holds the walk key.
constexpr uintptr_t walk_call_rva = 0x23c8d3a, walk_test_rva = 0x23c9860;
using WalkTestFn = unsigned char (*)(void *);
WalkTestFn walk_test = nullptr;
std::atomic<bool> bridge_walk{false};
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
unsigned char force_walk(void *input) {
  unsigned char held = walk_test(input); // Always called: its toggle mode counts presses.
  return bridge_walk.load(std::memory_order_relaxed) ? 1 : held;
}
// A jump to force_walk in a page within rel32 reach below the image, then the
// call's displacement swapped in one atomic store of its aligned code word.
bool hook_walk(uintptr_t base) {
  uintptr_t site = base + walk_call_rva, word_at = site & ~uintptr_t(7);
  void *page = nullptr;
  for (uintptr_t at = (base & ~uintptr_t(0xffff)) - 0x10000;
       !page && at && site - at < 0x7fff0000; at -= 0x10000)
    page = VirtualAlloc(reinterpret_cast<void *>(at), 4096,
                        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
  if (!page)
    return false;
  auto target = reinterpret_cast<uintptr_t>(&force_walk);
  const unsigned char jump[6] = {0xff, 0x25, 0, 0, 0, 0}; // jmp [rip+0]
  std::memcpy(page, jump, sizeof(jump));
  std::memcpy(static_cast<char *>(page) + sizeof(jump), &target, sizeof(target));
  walk_test = reinterpret_cast<WalkTestFn>(base + walk_test_rva);
  auto *word = reinterpret_cast<volatile LONG64 *>(word_at);
  auto expected = static_cast<uint64_t>(*word);
  auto next = stellar_input::redirect_call(expected, word_at, site,
                                           base + walk_test_rva,
                                           reinterpret_cast<uintptr_t>(page));
  DWORD protection = 0, ignored = 0;
  bool done = next && VirtualProtect(page, 4096, PAGE_EXECUTE_READ, &ignored) &&
              VirtualProtect(reinterpret_cast<void *>(word_at), 8,
                             PAGE_EXECUTE_READWRITE, &protection);
  if (done) {
    done = InterlockedCompareExchange64(word, LONG64(*next), LONG64(expected)) ==
           LONG64(expected);
    VirtualProtect(reinterpret_cast<void *>(word_at), 8, protection, &ignored);
    FlushInstructionCache(GetCurrentProcess(), reinterpret_cast<void *>(word_at), 8);
  }
  if (!done)
    VirtualFree(page, 0, MEM_RELEASE);
  return done;
}
bool verify_host() {
  auto module = GetModuleHandleW(nullptr);
  auto base = reinterpret_cast<unsigned char *>(module);
#ifdef STELLAR_INPUT_TEST_HOST
  // A distinct fixture DLL only. Production has no environment/configuration
  // bypass.
  auto path = module_path(module);
  auto name = path.substr(path.find_last_of(L"\\/") + 1);
  if (name != L"stellar_input_fake_host.exe" &&
      name != L"stellar_input_overlay_host.exe")
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
DWORD WINAPI direct_get_state(DWORD index, XINPUT_STATE *state);
DWORD WINAPI direct_set_state(DWORD index, XINPUT_VIBRATION *vibration);
// Points the host's imports of this DLL's XInputGetState and XInputSetState at
// entries no overlay patches. Steam's overlay hot-patches the exports on
// Windows and answers the game's slot-0 poll itself, as disconnected: Unreal
// then never polls slot 0 again, and neither the pose stream nor forwarded
// input runs. The entries behave as the exports, so an unverified host keeps a
// plain passthrough. Returns how many imports it routed.
int route_imports(HMODULE self) {
  auto base = reinterpret_cast<unsigned char *>(GetModuleHandleW(nullptr));
  auto dos = reinterpret_cast<IMAGE_DOS_HEADER *>(base);
  if (dos->e_magic != IMAGE_DOS_SIGNATURE)
    return 0;
  auto pe = reinterpret_cast<IMAGE_NT_HEADERS64 *>(base + dos->e_lfanew);
  if (pe->Signature != IMAGE_NT_SIGNATURE ||
      pe->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
    return 0;
  auto &imports = pe->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
  auto own = module_path(self);
  own = own.substr(own.find_last_of(L"\\/") + 1);
  if (!imports.VirtualAddress || own.empty())
    return 0;
  int routed = 0;
  for (auto d = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR *>(base + imports.VirtualAddress);
       d->Name; ++d) {
    if (!d->OriginalFirstThunk ||
        !stellar_input::same_module(reinterpret_cast<const char *>(base + d->Name), own))
      continue;
    auto names = reinterpret_cast<IMAGE_THUNK_DATA64 *>(base + d->OriginalFirstThunk);
    auto slots = reinterpret_cast<IMAGE_THUNK_DATA64 *>(base + d->FirstThunk);
    for (; names->u1.AddressOfData; ++names, ++slots) {
      bool by_ordinal = IMAGE_SNAP_BY_ORDINAL64(names->u1.Ordinal);
      auto kind = stellar_input::routed_import(
          by_ordinal, by_ordinal ? uint16_t(IMAGE_ORDINAL64(names->u1.Ordinal)) : 0,
          by_ordinal ? std::string_view()
                     : std::string_view(reinterpret_cast<IMAGE_IMPORT_BY_NAME *>(
                                            base + names->u1.AddressOfData)->Name));
      if (kind == stellar_input::Routed::none)
        continue;
      auto entry = kind == stellar_input::Routed::get_state
                       ? reinterpret_cast<LONG64>(&direct_get_state)
                       : reinterpret_cast<LONG64>(&direct_set_state);
      auto *slot = reinterpret_cast<volatile LONG64 *>(&slots->u1.Function);
      DWORD protection = 0, ignored = 0;
      if (!VirtualProtect(const_cast<LONG64 *>(slot), sizeof(LONG64), PAGE_READWRITE, &protection))
        continue;
      InterlockedExchange64(slot, entry);
      VirtualProtect(const_cast<LONG64 *>(slot), sizeof(LONG64), protection, &ignored);
      ++routed;
    }
  }
  return routed;
}
// Unreal polls a slot it saw disconnected again only after a device change,
// and an overlay may have answered its first poll before the imports were
// routed: posts one to this process's top-level windows.
void nudge_device_change() {
  EnumWindows(
      [](HWND window, LPARAM) -> BOOL {
        DWORD process = 0;
        GetWindowThreadProcessId(window, &process);
        if (process == GetCurrentProcessId())
          PostMessageW(window, WM_DEVICECHANGE, DBT_DEVNODES_CHANGED, 0);
        return TRUE;
      },
      0);
}
// A complete file in the bridge directory: written beside it, then renamed over it.
bool write_text(const wchar_t *name, const std::string &bytes) {
  auto path = directory + name, temp = path + L".tmp";
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
  // A wardrobe answer is handed over once, so it is kept until written:
  // Windows refuses the replace while Java reads the file. The ack and the
  // calls report are rewritten on later passes anyway.
  std::string published, slot0, slot1, unsent_wardrobe;
  stellar_input::CallReport calls;
  unsigned nudges = 0;
  // Input every 5 ms keeps forwarding latency low; permits, capture and pose
  // files keep their 20 ms period. Cached permit bytes are re-validated.
  for (unsigned iteration = 0;; ++iteration) {
    bool slow = iteration % 4 == 0;
    // Until the gameplay poll shows up: a device change every 2 s, for a minute.
    if (iteration % 400 == 0 && nudges < 30 &&
        gameplay_calls.load(std::memory_order_relaxed) == 0) {
      ++nudges;
      nudge_device_change();
    }
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
        if (write_text(L"eve-input-ack.txt", stellar_input::encode_ack(*ack)))
          published = std::move(key);
      }
    }
    if (auto report = calls.report(gameplay_calls.load(std::memory_order_relaxed),
                                   other_calls.load(std::memory_order_relaxed), now, wall))
      write_text(L"eve-input-calls.txt", *report);
    if (!slow) {
      Sleep(5);
      continue;
    }
    auto capture_request =
        read_file(directory + L"eve-stream.txt", 512).value_or("");
    stellar_raw::poll_raw_capture(capture_request, wall_ms());
    try {
      auto wardrobe = stellar_pose::poll_files(
          directory,
          read_file(directory + L"eve-pose-request.txt", 512).value_or(""),
          read_file(directory + L"eve-pose-components.0", 65536).value_or(""),
          read_file(directory + L"eve-pose-components.1", 65536).value_or(""),
          wall_ms(), read_file(directory + L"eve-lock.txt", 256).value_or(""),
          read_file(directory + L"eve-wardrobe-request.txt", 512).value_or(""));
      if (!wardrobe.empty())
        unsent_wardrobe = std::move(wardrobe);
      if (!unsent_wardrobe.empty() &&
          write_text(L"eve-wardrobe.txt", unsent_wardrobe))
        unsent_wardrobe.clear();
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
  // Again now: an overlay may have rewritten the imports since DllMain did.
  route_imports(self);
#ifndef STELLAR_INPUT_TEST_HOST
  // Windows 11 may ignore the game's timer resolution and slow it while it is
  // occluded and silent, as behind a fullscreen Minecraft: the worker's
  // Sleep(5) would last about 15.6 ms. Opt this process out (nothing persists;
  // Wine ignores it); Windows 10 knows only the execution speed flag.
  PROCESS_POWER_THROTTLING_STATE throttling{
      PROCESS_POWER_THROTTLING_CURRENT_VERSION,
      PROCESS_POWER_THROTTLING_EXECUTION_SPEED |
          PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION,
      0};
  if (!SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling,
                             &throttling, sizeof throttling)) {
    throttling.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
    SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling,
                          &throttling, sizeof throttling);
  }
  // Only once pinned: the redirected call jumps into this DLL. Optional: without it the walk key does nothing.
  hook_walk(reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr)));
#endif
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
  (gameplay ? gameplay_calls : other_calls).fetch_add(1, std::memory_order_relaxed);
  if (gameplay) {
    // Game thread, between frames: last frame's animation and physics are final.
    try {
      std::string owner, permitted;
      {
        std::lock_guard<std::mutex> lock(state_lock);
        owner = core.forwarding_run();
        permitted = core.permit_run(GetTickCount64());
      }
      stellar_pose::on_gameplay_poll(owner, permitted);
    } catch (...) {
    }
  }
  {
    std::lock_guard<std::mutex> lock(state_lock);
    result = core.poll(gameplay, wall_ms(), GetTickCount64());
    bridge_walk.store(result.override &&
                          (result.pad.buttons & stellar_input::walk_button),
                      std::memory_order_relaxed);
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
DWORD set_state(DWORD index, XINPUT_VIBRATION *vibration) {
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
// The routed imports' entries (route_imports). The return address MUST be
// captured in the entry the host calls, before any helper.
__declspec(noinline) DWORD WINAPI direct_get_state(DWORD index, XINPUT_STATE *state) {
  return get_state(index, state, _ReturnAddress());
}
__declspec(noinline) DWORD WINAPI direct_set_state(DWORD index, XINPUT_VIBRATION *vibration) {
  return set_state(index, vibration);
}
} // namespace
// The return address MUST be captured in the exported entry, before any helper.
extern "C" DWORD WINAPI XInputGetState(DWORD index, XINPUT_STATE *state) {
  return get_state(index, state, _ReturnAddress());
}
extern "C" DWORD WINAPI XInputSetState(DWORD index,
                                       XINPUT_VIBRATION *vibration) {
  return set_state(index, vibration);
}
// The loader has bound the host's imports and runs no overlay code yet: route
// them now, before the host's first poll. Only memory protection and the
// import table, safe under the loader lock.
extern "C" BOOL WINAPI DllMain(HINSTANCE self, DWORD reason, LPVOID) {
  if (reason == DLL_PROCESS_ATTACH)
    route_imports(self);
  return TRUE;
}
