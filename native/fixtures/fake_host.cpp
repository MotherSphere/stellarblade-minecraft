#define WIN32_LEAN_AND_MEAN
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <windows.h>
#include <xinput.h>
using GetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_STATE *);
using SetStateFn = DWORD(WINAPI *)(DWORD, XINPUT_VIBRATION *);
extern "C" DWORD FakeGameplayPoll(GetStateFn, DWORD, XINPUT_STATE *);
static const std::string run(32, 'a'), session(32, 'b');
void require(bool value, const char *label) {
  if (!value) {
    std::cerr << "FAIL " << label << "\n";
    ExitProcess(1);
  }
}
uint64_t now() {
  FILETIME f;
  GetSystemTimeAsFileTime(&f);
  ULARGE_INTEGER n;
  n.LowPart = f.dwLowDateTime;
  n.HighPart = f.dwHighDateTime;
  return n.QuadPart / 10000 - 11644473600000ULL;
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
std::string permit(uint64_t seq, std::string state = "active") {
  return "SBMC_CONTROL 1\nRUN " + run + "\nSESSION " + session +
         "\nGEN 1\nSEQ " + std::to_string(seq) + "\nTIME " +
         std::to_string(now() / 1000) + "\nSTATE " + state +
         "\nCONTEXT 11 12\nEND " + std::to_string(seq) + "\n";
}
std::string packet(int epoch, int seq, std::string state,
                   std::string edges = "") {
  int count = 0;
  for (char c : edges)
    if (c == '\n')
      ++count;
  return "SBMC_INPUT 1\nRUN " + run + "\nSESSION " + session + "\nEPOCH " +
         std::to_string(epoch) + "\nSEQ " + std::to_string(seq) + "\nTIME " +
         std::to_string(now()) + "\nSTATE " + state +
         "\nMOVE 0 0\nLOOK 0 0\nBASE 0 0\nEDGES " + std::to_string(count) +
         "\n" + edges + "END " + std::to_string(seq) + "\n";
}
void wait_ack(std::string state, int edge, int epoch = 1) {
  for (int n = 0; n < 50; ++n) {
    auto a = read("eve-input-ack.txt");
    if (a.find("STATE " + state + "\n") != std::string::npos &&
        a.find("EDGE " + std::to_string(edge) + "\n") != std::string::npos &&
        a.find("EPOCH " + std::to_string(epoch) + "\n") != std::string::npos)
      return;
    Sleep(10);
  }
  require(false, ("ACK " + state).c_str());
}
int main() {
  DeleteFileA("eve-input.txt");
  DeleteFileA("eve-control-permit.0");
  DeleteFileA("eve-control-permit.1");
  DeleteFileA("eve-input-ack.txt");
  DeleteFileA("eve-input-calls.txt");
  char cwd[4096];
  require(GetCurrentDirectoryA(sizeof(cwd), cwd) > 0, "cwd");
  write("stellar-input-directory.txt", std::string(cwd) + "\n");
  wchar_t system[MAX_PATH];
  GetSystemDirectoryW(system, MAX_PATH);
  std::wstring backend_path = std::wstring(system) + L"\\xinput1_4.dll";
  auto backend = LoadLibraryW(backend_path.c_str());
  require(backend, "system backend");
  auto real =
      reinterpret_cast<GetStateFn>(GetProcAddress(backend, "XInputGetState"));
  auto production = LoadLibraryW(L".\\xinput1_3.dll");
  require(production, "production DLL load");
  auto production_get = reinterpret_cast<GetStateFn>(
      GetProcAddress(production, "XInputGetState"));
  require(production_get && GetProcAddress(production, MAKEINTRESOURCEA(2)) ==
                                reinterpret_cast<FARPROC>(production_get),
          "GetState ordinal 2");
  require(GetProcAddress(production, MAKEINTRESOURCEA(3)) ==
              GetProcAddress(production, "XInputSetState"),
          "SetState ordinal 3");
  XINPUT_STATE a{}, b{};
  require(production_get(0, &a) == real(0, &b),
          "unknown host production forwards");
  require(GetFileAttributesA("eve-input-ack.txt") == INVALID_FILE_ATTRIBUTES,
          "production cannot arm fixture");
  auto fixture = LoadLibraryW(L".\\stellar_input_fixture.dll");
  require(fixture, "fixture DLL load");
  auto get =
      reinterpret_cast<GetStateFn>(GetProcAddress(fixture, "XInputGetState"));
  require(get, "fixture GetState");
  auto set =
      reinterpret_cast<SetStateFn>(GetProcAddress(fixture, "XInputSetState"));
  auto real_set =
      reinterpret_cast<SetStateFn>(GetProcAddress(backend, "XInputSetState"));
  XINPUT_VIBRATION no_vibration{};
  require(set && real_set &&
              set(4, &no_vibration) == real_set(4, &no_vibration),
          "invalid-slot vibration passthrough");
  require(get(1, &a) == real(1, &b), "other slot forwards");
  require(get(0, nullptr) == ERROR_BAD_ARGUMENTS, "null output rejected");
  write("eve-control-permit.0", permit(1));
  write("eve-input.txt", packet(1, 1, "neutral"));
  Sleep(30);
  write("eve-input.txt", packet(1, 2, "neutral"));
  Sleep(30);
  require(get(0, &a) == ERROR_SUCCESS, "observer connected");
  require(GetFileAttributesA("eve-input-ack.txt") == INVALID_FILE_ATTRIBUTES,
          "observer cannot complete handshake");
  require(FakeGameplayPoll(get, 0, &a) == ERROR_SUCCESS &&
              a.Gamepad.wButtons == 0,
          "neutral handshake poll");
  wait_ack("ready", 0);
  write("eve-input.txt", packet(1, 3, "neutral"));
  Sleep(30);
  auto stamp = std::to_string(now());
  write("eve-input.txt",
        packet(1, 4, "active",
               "EDGE 1 4096 " + stamp + " 100 200 300 400\nEDGE 2 0 " + stamp +
                   " -100 0 0 0\n"));
  Sleep(50);
  for (int i = 0; i < 4; ++i) {
    get(0, &a);
    require(a.Gamepad.wButtons == 0, "observer cannot consume press");
  }
  FakeGameplayPoll(get, 0, &a);
  require(a.Gamepad.wButtons == 4096 && a.Gamepad.sThumbLX == 100 &&
              a.Gamepad.sThumbRY == 400,
          "ordered directional press");
  auto first_packet = a.dwPacketNumber;
  require(set(0, &no_vibration) == ERROR_SUCCESS, "remote vibration discarded");
  wait_ack("ready", 1);
  get(0, &a);
  require(a.Gamepad.wButtons == 4096, "observer sees cached press");
  FakeGameplayPoll(get, 0, &a);
  require(a.Gamepad.wButtons == 0 && a.Gamepad.sThumbLX == -100 &&
              a.dwPacketNumber > first_packet,
          "ordered release and packet number");
  wait_ack("ready", 2);
  write("eve-control-permit.1", permit(2, "stopped"));
  Sleep(50);
  get(0, &a);
  require(a.Gamepad.wButtons == 0, "stop observer neutral");
  wait_ack("blocked", 2);
  FakeGameplayPoll(get, 0, &a);
  wait_ack("released", 2);
  write("eve-control-permit.0", permit(3));
  write("eve-input.txt", packet(2, 1, "neutral"));
  Sleep(50);
  FakeGameplayPoll(get, 0, &a);
  wait_ack("ready", 0, 2);
  Sleep(550);
  get(0, &a);
  require(a.Gamepad.wButtons == 0, "expired observer neutral");
  wait_ack("blocked", 0, 2);
  FakeGameplayPoll(get, 0, &a);
  wait_ack("released", 0, 2);
  require(get(2, &a) == real(2, &b), "other slot remains passthrough");
  // Unloading the caller's handle cannot invalidate the pinned worker/module.
  FreeLibrary(fixture);
  require(get(0, &a) == ERROR_SUCCESS, "worker module pinned");
  // The callers report counts the slot-0 polls above by return address: the
  // gameplay poll's, and the observer's (an overlay hook would be one).
  bool reported = false;
  for (int n = 0; n < 40 && !reported; ++n) {
    auto calls = read("eve-input-calls.txt");
    auto count = [&](const char *key) {
      auto at = calls.find(key);
      return at == std::string::npos ? 0ul : std::stoul(calls.substr(at + std::strlen(key)));
    };
    reported = calls.rfind("SBMC_XINPUT_CALLS 1\nTIME ", 0) == 0 &&
               count("\nGAMEPLAY ") == 6 && count("\nOTHER ") == 9;
    if (!reported)
      Sleep(100);
  }
  require(reported, "callers report");
  std::cout
      << "PASS production identity rejection, ordinals, forwarding, gameplay "
         "gate, neutral handshake, edge ACK, stop, expiry, module lifetime, "
         "callers report\n";
}
