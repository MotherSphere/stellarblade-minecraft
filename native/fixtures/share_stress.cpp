// Lua's side of the Windows sharing check, through the MSVC CRT as UE4SS's Lua
// uses it: reads terrain.txt with fopen (read and write sharing, no delete
// sharing) and replaces stress.txt by remove, then rename (MSVC rename refuses
// an existing destination), about once a millisecond, until Minecraft's side
// (BridgeRuntimeTest's contended run) writes stress-done.txt. No game files.
#define _CRT_SECURE_NO_WARNINGS // Plain fopen is the point: Lua calls it, and fopen_s shares differently.
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <iostream>
#include <string>
#include <thread>

namespace {
bool exists(const std::string &path) {
  if (FILE *f = std::fopen(path.c_str(), "rb")) {
    std::fclose(f);
    return true;
  }
  return false;
}
int fail(const char *message) {
  std::cerr << "FAIL " << message << "\n";
  return 1;
}
} // namespace

int main(int argc, char **argv) {
  if (argc != 2)
    return fail("expected the bridge directory");
  std::string directory = argv[1], terrain = directory + "/terrain.txt",
              stress = directory + "/stress.txt",
              temporary = directory + "/stress.txt.tmp",
              done = directory + "/stress-done.txt";
  auto deadline = std::chrono::steady_clock::now() + std::chrono::minutes(5);
  unsigned long reads = 0, absent = 0, denied = 0, replaced = 0, refused = 0;
  for (unsigned long n = 0; !exists(done); ++n) {
    if (std::chrono::steady_clock::now() > deadline)
      return fail("Minecraft's side never finished");
    if (FILE *f = std::fopen(terrain.c_str(), "rb")) {
      std::string text;
      char buffer[8192];
      for (size_t got; (got = std::fread(buffer, 1, sizeof buffer, f)) > 0;)
        text.append(buffer, got);
      std::fclose(f);
      // A complete snapshot or nothing: Java replaces it atomically.
      if (text.rfind("terrain ", 0) != 0 || text.size() < 5 ||
          text.compare(text.size() - 5, 5, "\nEND\n") != 0)
        return fail("torn terrain.txt");
      ++reads;
    } else if (errno == ENOENT) {
      ++absent; // Before Minecraft's first write.
    } else {
      ++denied; // A removal still pending, as Lua would see it.
    }
    if (FILE *f = std::fopen(temporary.c_str(), "wb")) {
      std::fputs(("stress " + std::to_string(n) + "\n").c_str(), f);
      std::fclose(f);
      std::remove(stress.c_str());
      if (std::rename(temporary.c_str(), stress.c_str()) == 0)
        ++replaced;
      else
        ++refused; // Lua retries on its next tick.
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  if (!reads || !replaced)
    return fail("the two sides never overlapped");
  std::cout << "PASS share stress: " << reads << " reads of terrain.txt ("
            << absent << " absent, " << denied << " denied), " << replaced
            << " replacements of stress.txt (" << refused << " refused)\n";
  return 0;
}
