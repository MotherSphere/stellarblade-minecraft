// The fixture adapter imported as Stellar Blade imports xinput1_3.dll (by
// ordinal), with its exports then hot-patched as Steam's overlay patches them on
// Windows: the hooks answer slot 0 themselves, as disconnected, and never call
// the adapter. The game's calls through its imports must still reach the
// adapter, and the adapter must keep telling the game's windows that devices
// changed until it sees the gameplay poll (Unreal polls a slot it saw
// disconnected only after one), then stop.
#define WIN32_LEAN_AND_MEAN
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <windows.h>
#include <dbt.h>
#include <timeapi.h>
#include <xinput.h>
using GetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_STATE *);
using SetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_VIBRATION *);
extern "C" DWORD FakeGameplayPoll(GetStateFn, DWORD, XINPUT_STATE *);
// This host's import slots for the fixture's exports: each use reads the slot,
// as the game's `call [import]` does.
extern "C" GetStateFn __imp_XInputGetState;
extern "C" SetStateFn __imp_XInputSetState;
static long overlay_gets = 0, overlay_sets = 0, device_changes = 0;
static ULONGLONG change_times[2] = {};
void require(bool value, const char *label) {
  if (!value) {
    std::cerr << "FAIL " << label << "\n";
    ExitProcess(1);
  }
}
void write(const std::string &path, const std::string &contents) {
  auto temp = path + ".host-tmp";
  {
    std::ofstream f(temp, std::ios::binary);
    f << contents;
    require(bool(f), "fixture write");
  }
  require(MoveFileExA(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING),
          "fixture replace");
}
std::string read(const char *path) {
  std::ifstream f(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(f), {}};
}
DWORD WINAPI overlay_get(DWORD, XINPUT_STATE *state) {
  ++overlay_gets;
  if (state)
    *state = {};
  return ERROR_DEVICE_NOT_CONNECTED;
}
DWORD WINAPI overlay_set(DWORD, XINPUT_VIBRATION *) {
  ++overlay_sets;
  return ERROR_DEVICE_NOT_CONNECTED;
}
// An inline hook as Steam's overlay installs one: the export's first five
// bytes become a jump to a relay page within its reach, which jumps to the hook.
void hot_patch(void *function, void *hook) {
  auto at = reinterpret_cast<uintptr_t>(function);
  void *page = nullptr;
  for (uintptr_t p = (at & ~uintptr_t(0xffff)) - 0x10000;
       !page && p && at - p < 0x7fff0000; p -= 0x10000)
    page = VirtualAlloc(reinterpret_cast<void *>(p), 4096,
                        MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
  require(page, "relay page");
  unsigned char relay[14] = {0xff, 0x25, 0, 0, 0, 0}; // jmp [rip+0]
  std::memcpy(relay + 6, &hook, sizeof(hook));
  std::memcpy(page, relay, sizeof(relay));
  unsigned char jump[5] = {0xe9};
  auto displacement = int32_t(reinterpret_cast<intptr_t>(page) - intptr_t(at + 5));
  std::memcpy(jump + 1, &displacement, sizeof(displacement));
  DWORD protection = 0;
  require(VirtualProtect(function, sizeof(jump), PAGE_EXECUTE_READWRITE, &protection),
          "unprotect export");
  std::memcpy(function, jump, sizeof(jump));
  VirtualProtect(function, sizeof(jump), protection, &protection);
  FlushInstructionCache(GetCurrentProcess(), function, sizeof(jump));
}
LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
  if (message == WM_DEVICECHANGE && w == DBT_DEVNODES_CHANGED) {
    if (device_changes < 2)
      change_times[device_changes] = GetTickCount64();
    ++device_changes;
  }
  return DefWindowProcW(window, message, w, l);
}
// Dispatches this thread's messages until `done` holds or `milliseconds` of
// wall-clock time pass, whatever the timer resolution makes of Sleep.
template <class Done> void pump(ULONGLONG milliseconds, Done done) {
  for (auto start = GetTickCount64();;) {
    MSG message;
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE))
      DispatchMessageW(&message);
    if (done() || GetTickCount64() - start >= milliseconds)
      return;
    Sleep(10);
  }
}
// Every import of the fixture is by ordinal, the path the game's imports take.
bool imports_fixture_by_ordinal() {
  auto base = reinterpret_cast<const unsigned char *>(GetModuleHandleW(nullptr));
  auto nt = reinterpret_cast<const IMAGE_NT_HEADERS64 *>(
      base + reinterpret_cast<const IMAGE_DOS_HEADER *>(base)->e_lfanew);
  auto &imports = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
  bool any = false;
  for (auto entry = reinterpret_cast<const IMAGE_IMPORT_DESCRIPTOR *>(base + imports.VirtualAddress);
       entry->Name; ++entry) {
    if (_stricmp(reinterpret_cast<const char *>(base + entry->Name), "stellar_input_fixture.dll"))
      continue;
    if (!entry->OriginalFirstThunk)
      return false;
    for (auto thunk = reinterpret_cast<const IMAGE_THUNK_DATA64 *>(base + entry->OriginalFirstThunk);
         thunk->u1.AddressOfData; ++thunk) {
      if (!IMAGE_SNAP_BY_ORDINAL64(thunk->u1.Ordinal))
        return false;
      any = true;
    }
  }
  return any;
}
int main() {
  // The game's 1 ms timer, kept while this process's window stays hidden.
  PROCESS_POWER_THROTTLING_STATE throttling{PROCESS_POWER_THROTTLING_CURRENT_VERSION,
                                            PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION, 0};
  SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, &throttling,
                        sizeof(throttling));
  timeBeginPeriod(1);
  DeleteFileA("eve-input.txt");
  DeleteFileA("eve-control-permit.0");
  DeleteFileA("eve-control-permit.1");
  DeleteFileA("eve-input-ack.txt");
  DeleteFileA("eve-input-calls.txt");
  require(imports_fixture_by_ordinal(), "the fixture imported by ordinal, as the game does");
  char cwd[4096];
  require(GetCurrentDirectoryA(sizeof(cwd), cwd) > 0, "cwd");
  write("stellar-input-directory.txt", std::string(cwd) + "\n");
  auto fixture = GetModuleHandleW(L"stellar_input_fixture.dll");
  require(fixture, "fixture imported");
  auto get = reinterpret_cast<GetStateFn>(GetProcAddress(fixture, "XInputGetState"));
  auto set = reinterpret_cast<SetStateFn>(GetProcAddress(fixture, "XInputSetState"));
  require(get && set, "fixture exports");
  // Routed when the fixture loaded, before any overlay could patch the exports.
  require(__imp_XInputGetState != get && __imp_XInputSetState != set,
          "imports routed past the exports");
  // A top-level window of this process, as the game's.
  WNDCLASSW type{};
  type.lpfnWndProc = window_proc;
  type.hInstance = GetModuleHandleW(nullptr);
  type.lpszClassName = L"StellarOverlayHost";
  require(RegisterClassW(&type), "window class");
  require(CreateWindowExW(0, type.lpszClassName, L"overlay host", WS_OVERLAPPEDWINDOW, 0,
                          0, 100, 100, nullptr, nullptr, type.hInstance, nullptr),
          "window");
  hot_patch(reinterpret_cast<void *>(get), reinterpret_cast<void *>(&overlay_get));
  hot_patch(reinterpret_cast<void *>(set), reinterpret_cast<void *>(&overlay_set));
  XINPUT_STATE state{};
  require(get(0, &state) == ERROR_DEVICE_NOT_CONNECTED && overlay_gets == 1,
          "the overlay answers the export's callers");
  // A device discovery through the import (not the gameplay poll) reaches the
  // adapter, which arms its worker.
  require(__imp_XInputGetState(0, &state) == ERROR_SUCCESS && overlay_gets == 1,
          "the import reaches the adapter past the overlay");
  // Two device changes give the period between them.
  pump(20000, [] { return device_changes >= 2; });
  require(device_changes >= 2, "device changes repeat until the gameplay poll");
  auto period = change_times[1] - change_times[0];
  require(FakeGameplayPoll(__imp_XInputGetState, 0, &state) == ERROR_SUCCESS &&
              overlay_gets == 1,
          "the gameplay poll reaches the adapter past the overlay");
  XINPUT_VIBRATION none{};
  __imp_XInputSetState(0, &none);
  require(overlay_sets == 0, "vibration reaches the adapter past the overlay");
  // Once the gameplay poll showed up, no more device changes, for one and a
  // half periods (a device plugged in meanwhile also counts).
  auto seen = device_changes;
  pump(period * 3 / 2, [] { return false; });
  require(device_changes == seen, "no device change after the gameplay poll");
  // The callers report: the gameplay poll and the discovery; the overlay's
  // answer never reached the adapter.
  bool reported = false;
  for (int n = 0; n < 40 && !reported; ++n) {
    auto calls = read("eve-input-calls.txt");
    auto count = [&](const char *key) {
      auto at = calls.find(key);
      return at == std::string::npos ? 0ul : std::stoul(calls.substr(at + std::strlen(key)));
    };
    reported = calls.rfind("SBMC_XINPUT_CALLS 1\nTIME ", 0) == 0 &&
               count("\nGAMEPLAY ") == 1 && count("\nOTHER ") == 1;
    if (!reported)
      Sleep(100);
  }
  require(reported, "callers report");
  std::cout << "PASS overlay host: ordinal imports routed past hot-patched "
               "exports, gameplay poll and vibration reach the adapter, device "
               "changes every "
            << period << " ms until the gameplay poll\n";
}
