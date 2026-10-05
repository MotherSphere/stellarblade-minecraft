#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "raw_capture.hpp"
#include "frame_bus.hpp"
#include <mutex>
#include <windows.h>

namespace stellar_raw {
namespace {
using RunTask = bool (*)(void *);
std::mutex capture_mutex;
stellar_frame::Producer producer;
Profile profile{};
RunTask original_run = nullptr;
std::wstring directory;
std::vector<std::string> owned_runs;
std::optional<stellar_frame::Request> current;
uint64_t request_observed = 0;
bool enabled = false, stopped = true;
constexpr size_t maximum_runs = 128;

uint64_t wall_ms() {
  FILETIME time;
  GetSystemTimeAsFileTime(&time);
  ULARGE_INTEGER value;
  value.LowPart = time.dwLowDateTime;
  value.HighPart = time.dwHighDateTime;
  return value.QuadPart / 10000 - 11644473600000ULL;
}
bool readable(const void *address, size_t size) {
  uintptr_t at = reinterpret_cast<uintptr_t>(address);
  if (!at || !size || at > UINTPTR_MAX - size)
    return false;
  uintptr_t end = at + size;
  while (at < end) {
    MEMORY_BASIC_INFORMATION region{};
    if (!VirtualQuery(reinterpret_cast<const void *>(at), &region,
                      sizeof(region)) ||
        region.State != MEM_COMMIT ||
        (region.Protect & (PAGE_NOACCESS | PAGE_GUARD)))
      return false;
    DWORD access = region.Protect & 0xff;
    if (access != PAGE_READONLY && access != PAGE_READWRITE &&
        access != PAGE_WRITECOPY && access != PAGE_EXECUTE_READ &&
        access != PAGE_EXECUTE_READWRITE && access != PAGE_EXECUTE_WRITECOPY)
      return false;
    auto next =
        reinterpret_cast<uintptr_t>(region.BaseAddress) + region.RegionSize;
    if (next <= at)
      return false;
    at = next;
  }
  return true;
}
bool read_memory(const void *source, void *target, size_t size) {
  if (!readable(source, size))
    return false;
  std::memcpy(target, source, size);
  return true;
}
std::optional<std::string> read_metadata() {
  auto path = directory + L"eve-raw-frame.txt";
  HANDLE file = CreateFileW(
      path.c_str(), GENERIC_READ,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
      OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
  if (file == INVALID_HANDLE_VALUE)
    return {};
  BY_HANDLE_FILE_INFORMATION info{};
  bool regular = GetFileInformationByHandle(file, &info) &&
                 !(info.dwFileAttributes &
                   (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT));
  std::string text(2049, '\0');
  DWORD size = 0;
  bool ok =
      regular && ReadFile(file, text.data(), static_cast<DWORD>(text.size()),
                          &size, nullptr);
  CloseHandle(file);
  if (!ok || !size || size > 2048)
    return {};
  text.resize(size);
  return text;
}
bool known_run(std::string_view run) {
  return std::find(owned_runs.begin(), owned_runs.end(), run) !=
         owned_runs.end();
}
void fail(uint32_t error) {
  stopped = true;
  producer.fail(error);
}
bool lease_alive(uint64_t wall, uint64_t mono) {
  return current && !stopped && current->active &&
         current->time <= wall / 1000 && wall / 1000 - current->time <= 3 &&
         mono >= request_observed && mono - request_observed <= 3000;
}
bool run_task(void *task) {
  bool owned = false;
  std::string owned_run;
  try {
    auto view = inspect_task(task, profile, read_memory);
    auto job = view ? parse_job(view->filename, directory) : std::nullopt;
    if (job) {
      std::unique_lock<std::mutex> lock(capture_mutex);
      owned_run = job->run;
      owned = known_run(owned_run);
      if (owned) {
        bool this_run = current && current->run == job->run;
        if (!view->inert_completion) {
          // Preserve an unexpected delegate's engine completion semantics.
          // This job is never reported as a successfully intercepted raw frame.
          if (this_run)
            fail(3);
          lock.unlock();
          return original_run(task);
        }
        // Old callbacks own their engine pixels, not the new mapping. Drop
        // them without codecs or changing the new RUN's completion/error.
        if (!this_run || !lease_alive(wall_ms(), GetTickCount64()) ||
            !producer.active(wall_ms())) {
          if (this_run && !stopped) {
            stopped = true;
            producer.stop();
          }
          return false;
        }
        // The worker can be between polls when Lua queues this task. Lua
        // keeps this bounded descriptor immutable until all planes complete.
        auto text = read_metadata();
        auto metadata = text ? parse_metadata(*text) : std::nullopt;
        if (!metadata || metadata->run != current->run ||
            metadata->session != current->session || metadata->id != job->id ||
            metadata->width != current->width ||
            metadata->height != current->height) {
          fail(1);
          return false;
        }
        auto plane =
            inspect_plane(*view, profile, *metadata, *job, read_memory);
        if (!plane || !readable(plane->data, plane->bytes)) {
          fail(2);
          return false;
        }
        if (!producer.begin(*text, metadata->counter) ||
            !producer.plane(job->kind, plane->data, plane->bytes,
                            metadata->width, metadata->height)) {
          fail(2);
          return false;
        }
        return true;
      }
    }
  } catch (...) {
    if (owned) {
      std::lock_guard<std::mutex> lock(capture_mutex);
      if (current && current->run == owned_run)
        fail(3);
      return false;
    }
  }
  return original_run(task);
}
bool code_matches(uintptr_t base, uintptr_t rva, std::string_view hex) {
  auto bytes = reinterpret_cast<const unsigned char *>(base + rva);
  if (!readable(bytes, hex.size() / 2))
    return false;
  auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
  for (size_t i = 0; i < hex.size() / 2; ++i)
    if (bytes[i] != (digit(hex[i * 2]) << 4 | digit(hex[i * 2 + 1])))
      return false;
  return true;
}
} // namespace

bool initialize_raw_capture(void *module, const std::wstring &path) {
  std::lock_guard<std::mutex> lock(capture_mutex);
  if (enabled)
    return true;
  auto base = reinterpret_cast<uintptr_t>(module);
  IMAGE_DOS_HEADER dos{};
  if (!read_memory(module, &dos, sizeof(dos)) ||
      dos.e_magic != IMAGE_DOS_SIGNATURE || dos.e_lfanew < 0 ||
      dos.e_lfanew > 4096)
    return false;
  IMAGE_NT_HEADERS64 pe{};
  if (!read_memory(reinterpret_cast<const void *>(base + dos.e_lfanew), &pe,
                   sizeof(pe)) ||
      pe.Signature != IMAGE_NT_SIGNATURE || pe.FileHeader.Machine != 0x8664 ||
      pe.FileHeader.TimeDateStamp != 0x6a6a3b74 ||
      pe.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC ||
      pe.OptionalHeader.SizeOfImage != 0x15981000)
    return false;
  // The caller has already verified the complete executable SHA256. These
  // independent in-memory guards also reject replaced functions or vtables.
  if (!code_matches(base, 0x2eee460,
                    "48895c2418574881ecc0000000488b05cc72e3034833c448898424b000"
                    "0000488bd9e8990200000f"))
    return false;
  if (!code_matches(base, 0x2eeb510,
                    "48895c2408574883ec20488bd98bfa4883c178e85854abfe488b4b7048"
                    "85c9740a488b01ba01000000ff1048837b30007415488b4340488d4b50"
                    "4885c0480f45c8488b01ff5010488b4b084885c97405e84bf872ff488d"
                    "0574dda40248890340f6c7017421488b0d84ff17044885c975"))
    return false;
  if (!code_matches(
          base, 0x2eecf70,
          "48895c2408574883ec20488bf9488b4918488b01ff5008488b4f20888180000000e8"
          "ba8655fe488b5f108b5708488bcbe83b070000f0ff4b08488b4b10488b01ff501048"
          "8b07ba01000000488bcf488b5c24304883c4205f48ff"))
    return false;
  if (!code_matches(base, 0x2eef2f0, "488b4128488902488b413048c1e002498900c3"))
    return false;
  if (!code_matches(base, 0x2eef1f0, "488b4128488902488b413048c1e003498900c3"))
    return false;
  if (!code_matches(base, 0x2eefd40, "0fb612e928ceffff"))
    return false;
  if (!code_matches(
          base, 0x2eecb70,
          "48895c2410574883ec20488339000fb6da488bf97427488b41104883c1204885c088"
          "542430480f45c8488b01ff50084c8b07488d542430488bc841ffd08b05cd60120439"
          "47480f95c1837f4c000f95c00ac87454488d4f40e8e36ecbff4885c07446488b5748"
          "488b4810e8d1f6aeff4885c07434488d4f40885c2430e8bf6ecbff488b5748488bc8"
          "488bd8e8a07ec8ff488b0b4c8d442430488bd04c8b8918020000488bcb41ffd1488b"
          "5c24384883c4205fc3cc"))
    return false;
  if (!code_matches(base, 0x10c51b0, "488d4110c3"))
    return false;
  if (!code_matches(
          base, 0x2eeb450,
          "40534883ec2033c04c8d4424384889442430488d5424304889442438488bd9488b01"
          "ff500848837c243000742f0fb643160fb6531548c1ea03480fafd04863430c480faf"
          "d048634308480fafd048395424387508b0014883c4205bc332c04883c4205b"))
    return false;
  Profile found{
      base + 0x6006770, base + 0x60065f0, base + 0x6006610, base + 0x2eef2f0,
      base + 0x2eef1f0, base + 0x2eefd40, base + 0x60066b0, 0};
  if (!read_memory(reinterpret_cast<void *>(base + 0x7012c80), &found.none_name,
                   sizeof(found.none_name)))
    return false;
  const std::array<std::pair<uintptr_t, uintptr_t>, 8> pointers{
      {{0x6006770, 0x2eeb510},
       {0x6006778, 0x2eee460},
       {0x6006780, 0x2eee5e0},
       {0x60065f8, 0x2eef2f0},
       {0x6006618, 0x2eef1f0},
       {0x60066b0, 0x2ef0220},
       {0x60066b8, 0x10c51b0},
       {0x60066c0, 0xe6fcf0}}};
  for (auto entry : pointers) {
    uintptr_t value = 0;
    if (!read_memory(reinterpret_cast<void *>(base + entry.first), &value,
                     sizeof(value)) ||
        value != base + entry.second)
      return false;
  }
  directory = path;
  if (directory.empty())
    return false;
  if (directory.back() != L'/' && directory.back() != L'\\')
    directory += L'\\';
  profile = found;
  original_run = reinterpret_cast<RunTask>(base + 0x2eee460);
  auto slot = reinterpret_cast<void **>(base + 0x6006778);
  if (!detail::replace_slot(slot, reinterpret_cast<void *>(original_run),
                            reinterpret_cast<void *>(&run_task)))
    return false;
  enabled = true;
  return true;
}

void poll_raw_capture(std::string_view bytes, uint64_t now_ms) {
  // Input and its leases share this worker. A raw pixel copy must not delay
  // that path; the next worker iteration can observe the raw request.
  std::unique_lock<std::mutex> lock(capture_mutex, std::try_to_lock);
  if (!lock.owns_lock() || !enabled)
    return;
  try {
    // A callback failure latches this RUN immediately. The worker then releases
    // its mapping and acknowledges state 2, preserving the recorded error.
    if (stopped)
      producer.stop();
    auto mono = GetTickCount64();
    auto request = stellar_frame::parse_request(bytes);
    if (current && !stopped && !lease_alive(now_ms, mono)) {
      stopped = true;
      producer.stop();
    }
    if (!request || !request->active || request->time > now_ms / 1000 ||
        now_ms / 1000 - request->time > 3) {
      if (!stopped) {
        stopped = true;
        producer.stop();
      }
      return;
    }
    if (current && current->run == request->run) {
      if (stopped)
        return;
      if (current->session != request->session ||
          current->width != request->width ||
          current->height != request->height || request->time < current->time) {
        fail(1);
        return;
      }
      if (request->time != current->time)
        request_observed = mono;
      current = request;
    } else {
      if (known_run(request->run) || owned_runs.size() >= maximum_runs)
        return;
      owned_runs.push_back(request->run);
      current = request;
      request_observed = mono;
      stopped = false;
      if (!producer.open(directory, *request, GetCurrentProcessId(), now_ms)) {
        fail(1);
        return;
      }
    }
    producer.heartbeat(now_ms);
    if (!producer.active(now_ms)) {
      stopped = true;
      producer.stop();
    }
  } catch (...) {
    fail(3);
  }
}

namespace detail {
bool replace_slot(void **slot, void *expected, void *replacement) {
  DWORD protection = 0;
  if (reinterpret_cast<uintptr_t>(slot) % alignof(void *) ||
      !VirtualProtect(slot, sizeof(void *), PAGE_READWRITE, &protection))
    return false;
  void *previous =
      InterlockedCompareExchangePointer(slot, replacement, expected);
  DWORD ignored = 0;
  bool restored =
      VirtualProtect(slot, sizeof(void *), protection, &ignored) != FALSE;
  if (!restored && previous == expected) {
    InterlockedCompareExchangePointer(slot, expected, replacement);
    VirtualProtect(slot, sizeof(void *), protection, &ignored);
  }
  return restored && previous == expected;
}
} // namespace detail
} // namespace stellar_raw
