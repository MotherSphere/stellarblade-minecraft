#ifdef NDEBUG
#undef NDEBUG
#endif
// This isolated executable includes the actual handler, so synthetic task
// injection adds no fixture identity or alternate addresses to the shipped DLL.
#include "../src/raw_capture.cpp"
#include <cassert>
#include <fstream>
#include <future>
#include <iostream>
#include <thread>

namespace {
using namespace stellar_raw;
uint32_t forwarded = 0;
bool original(void *) {
  ++forwarded;
  return true;
}
void color_getter(const void *pixels, const void **data, int64_t *size) {
  const auto *p = static_cast<const unsigned char *>(pixels);
  std::memcpy(data, p + 0x28, sizeof(*data));
  std::memcpy(size, p + 0x30, sizeof(*size));
  *size *= 4;
}
void depth_getter(const void *pixels, const void **data, int64_t *size) {
  color_getter(pixels, data, size);
  *size *= 2;
}
template <class T> void write(void *base, size_t offset, T value) {
  std::memcpy(static_cast<char *>(base) + offset, &value, sizeof(value));
}
uint64_t read64(const void *base, size_t offset) {
  uint64_t value;
  std::memcpy(&value, static_cast<const char *>(base) + offset, sizeof(value));
  return value;
}
struct Mapping {
  HANDLE file = INVALID_HANDLE_VALUE, mapping = nullptr;
  void *data = nullptr;
  Mapping(const std::wstring &path, const std::string &run,
          const std::string &session) {
    file = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    assert(file != INVALID_HANDLE_VALUE);
    LARGE_INTEGER length;
    length.QuadPart = 16576;
    assert(SetFilePointerEx(file, length, nullptr, FILE_BEGIN) &&
           SetEndOfFile(file));
    mapping =
        CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 16576, nullptr);
    assert(mapping);
    data = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, 16576);
    assert(data);
    std::memset(data, 0, 16576);
    std::memcpy(data, "SBMCFRM1", 8);
    write(data, 8, uint32_t(1));
    write(data, 12, uint32_t(4096));
    write(data, 16, uint32_t(2));
    write(data, 20, uint32_t(1));
    write(data, 24, uint32_t(4160));
    write(data, 28, uint32_t(16576));
    std::memcpy(static_cast<char *>(data) + 32, run.data(), 32);
    std::memcpy(static_cast<char *>(data) + 64, session.data(), 32);
    write(data, 96, GetCurrentProcessId());
    write(data, 256, uint32_t(1));
    write(data, 264, stellar_raw::wall_ms());
  }
  ~Mapping() {
    if (data)
      UnmapViewOfFile(data);
    if (mapping)
      CloseHandle(mapping);
    if (file != INVALID_HANDLE_VALUE)
      CloseHandle(file);
  }
};
std::string descriptor(const std::string &run, const std::string &session,
                       const std::string &id, uint64_t time) {
  return "SBMC_FRAME 5 " + id + " " + std::to_string(time) +
         "\nSIZE 2 1\n"
         "CAMERA 1 2 3 4 5 6 75\nPAWN 1 2 3 90\nCONTEXT 11 12\nLINK " +
         session + " 1 100 0 0 0\nCOLOR raw 8\nMASK none 0\nDEPTH raw 8\nRUN " +
         run + "\nEND " + id + "\n";
}
void save(const std::wstring &path, const std::string &text) {
  HANDLE file =
      CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                  CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  assert(file != INVALID_HANDLE_VALUE);
  DWORD count;
  assert(WriteFile(file, text.data(), static_cast<DWORD>(text.size()), &count,
                   nullptr) &&
         count == text.size());
  CloseHandle(file);
}
std::wstring wide(const std::string &text) {
  return {text.begin(), text.end()};
}
} // namespace
int wmain(int argc, wchar_t **argv) {
  assert(argc == 2);
  std::wstring path = argv[1];
  assert(!stellar_raw::initialize_raw_capture(GetModuleHandleW(nullptr), path));
  if (path.back() != L'/' && path.back() != L'\\')
    path += L'\\';
  auto table = static_cast<void **>(
      VirtualAlloc(nullptr, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE));
  assert(table);
  table[0] = nullptr;
  table[1] = reinterpret_cast<void *>(&original);
  DWORD protection;
  assert(VirtualProtect(table, 4096, PAGE_READONLY, &protection));
  assert(!detail::replace_slot(
      table + 1, nullptr, reinterpret_cast<void *>(&stellar_raw::run_task)));
  assert(table[1] == reinterpret_cast<void *>(&original));
  assert(
      detail::replace_slot(table + 1, reinterpret_cast<void *>(&original),
                           reinterpret_cast<void *>(&stellar_raw::run_task)));
  MEMORY_BASIC_INFORMATION info{};
  assert(VirtualQuery(table, &info, sizeof(info)) &&
         info.Protect == PAGE_READONLY);
  std::array<uintptr_t, 2> color_table{
      0, reinterpret_cast<uintptr_t>(&color_getter)};
  std::array<uintptr_t, 2> depth_table{
      0, reinterpret_cast<uintptr_t>(&depth_getter)};
  stellar_raw::profile = {reinterpret_cast<uintptr_t>(table),
                          reinterpret_cast<uintptr_t>(color_table.data()),
                          reinterpret_cast<uintptr_t>(depth_table.data()),
                          reinterpret_cast<uintptr_t>(&color_getter),
                          reinterpret_cast<uintptr_t>(&depth_getter),
                          0x1234,
                          0x2345,
                          7};
  stellar_raw::original_run = original;
  stellar_raw::directory = path;
  stellar_raw::enabled = true;
  // The existing input worker must never wait for a raw plane copy.
  std::promise<void> locked;
  auto ready = locked.get_future();
  std::thread copying([&] {
    std::lock_guard<std::mutex> lock(stellar_raw::capture_mutex);
    locked.set_value();
    Sleep(250);
  });
  ready.wait();
  auto poll_start = GetTickCount64();
  stellar_raw::poll_raw_capture("", stellar_raw::wall_ms());
  auto poll_elapsed = GetTickCount64() - poll_start;
  copying.join();
  assert(poll_elapsed < 100);
  auto now = stellar_raw::wall_ms(), time = now / 1000;
  std::string run(32, 'a'), next_run(32, 'c'), session(32, 'b');
  auto id = std::to_string(time) + "-1";
  Mapping first(path + L"eve-frames-" + wide(run) + L".bin", run, session);
  auto request = [&](const std::string &r) {
    return "SBMC_CAPTURE_SESSION 2\nRUN " + r + "\nSESSION " + session +
           "\nTIME " + std::to_string(time) + "\nSIZE 2 1\nSTATE active\nEND\n";
  };
  stellar_raw::poll_raw_capture(request(run), now);
  assert(read64(first.data, 192) ==
         (uint64_t(1) << 32 | GetCurrentProcessId()));
  save(path + L"eve-raw-frame.txt", descriptor(run, session, id, time));
  std::array<unsigned char, 0x90> task{};
  std::array<unsigned char, 0x60> holder{};
  std::array<unsigned char, 0x40> pixels{};
  std::array<unsigned char, 8> color{1, 2, 3, 255, 4, 5, 6, 255},
      mask{0, 0, 0, 0, 0, 0, 0, 128};
  std::array<uint16_t, 8> depth{0x3c00, 0, 0, 0, 0x4000, 0, 0, 0};
  write(task.data(), 0, table);
  write(task.data(), 0x30, uintptr_t(0x1234));
  write(task.data(), 0x40, holder.data());
  write(task.data(), 0x70, pixels.data());
  write(holder.data(), 0, uintptr_t(0x2345));
  write(holder.data(), 0x58, uint32_t(7));
  write(pixels.data(), 8, int32_t(2));
  write(pixels.data(), 12, int32_t(1));
  write(pixels.data(), 0x16, uint8_t(4));
  write(pixels.data(), 0x30, int64_t(2));
  write(pixels.data(), 0x38, int64_t(2));
  auto invoke = [&](const std::string &r, int kind, bool callback = false) {
    static constexpr const char *suffixes[] = {"color.bmp", "mask.bmp",
                                               "depth.exr"};
    std::wstring filename = path + L"eve-raw-" + wide(r) + L"-" + wide(id) +
                            L"-" + wide(suffixes[kind]);
    write(task.data(), 8, filename.c_str());
    write(task.data(), 0x10, int32_t(filename.size() + 1));
    write(task.data(), 0x14, int32_t(filename.size() + 1));
    write(task.data(), 0x18, uint8_t(kind == 2 ? 5 : 3));
    write(holder.data(), 0x10, uintptr_t(callback ? 1 : 0));
    write(pixels.data(), 0,
          kind == 2 ? depth_table.data() : color_table.data());
    write(pixels.data(), 0x10, int32_t(kind == 2 ? 1 : 0));
    write(pixels.data(), 0x14, uint8_t(kind == 2 ? 0 : 1));
    write(pixels.data(), 0x15, uint8_t(kind == 2 ? 16 : 8));
    const void *data = kind == 2   ? static_cast<const void *>(depth.data())
                       : kind == 1 ? mask.data()
                                   : color.data();
    write(pixels.data(), 0x28, data);
    return reinterpret_cast<bool (*)(void *)>(table[1])(task.data());
  };
  assert(invoke(std::string(32, 'f'), 0) &&
         forwarded == 1); // Foreign filename uses original.
  assert(invoke(run, 2) && read64(first.data, 216) == 0);
  assert(invoke(run, 0) && read64(first.data, 216) == 0);
  assert(invoke(run, 1) && read64(first.data, 216) == 1 && forwarded == 1);
  for (uint64_t counter = 2; counter <= 13; ++counter) {
    id = std::to_string(time) + "-" + std::to_string(counter);
    save(path + L"eve-raw-frame.txt", descriptor(run, session, id, time));
    assert(invoke(run, 0) && invoke(run, 1) && invoke(run, 2));
  }
  stellar_raw::poll_raw_capture(request(run), stellar_raw::wall_ms());
  std::ifstream timing_file(path + L"eve-raw-timing.txt", std::ios::binary);
  std::string timing{std::istreambuf_iterator<char>(timing_file), {}};
  assert(timing.find("SBMC_RAW_TIMINGS 1\nRUN " + run + "\n") == 0);
  assert(timing.find("END 36\n") != std::string::npos && timing.size() <= 4096);
  assert(timing.find("PLANE " + std::to_string(time) + "-12 ") !=
         std::string::npos);
  assert(timing.find("PLANE " + std::to_string(time) + "-13 ") ==
         std::string::npos);
  assert(!invoke(run, 1)); // Duplicate cannot publish twice.
  assert((read64(first.data, 192) >> 32) == 3);
  stellar_raw::poll_raw_capture(request(run), stellar_raw::wall_ms());
  assert((read64(first.data, 192) >> 32) == 2 &&
         (read64(first.data, 200) & 0xffffffffu) == 2);
  assert(!stellar_raw::producer.ready());
  assert(!invoke(run, 0));
  assert((read64(first.data, 192) >> 32) == 2 && forwarded == 1);
  Mapping second(path + L"eve-frames-" + wide(next_run) + L".bin", next_run,
                 session);
  stellar_raw::poll_raw_capture(request(next_run), stellar_raw::wall_ms());
  assert((read64(second.data, 192) >> 32) == 1);
  assert(!invoke(run,
                 0)); // Late old callback neither encodes nor poisons new RUN.
  assert((read64(second.data, 192) >> 32) == 1 && forwarded == 1);
  assert(invoke(next_run, 0, true) &&
         forwarded == 2); // Unexpected completion must run original.
  assert((read64(second.data, 192) >> 32) == 3 &&
         read64(second.data, 216) == 0);
  auto stopped_request = request(next_run);
  stopped_request.replace(stopped_request.find("STATE active"), 12,
                          "STATE stopped");
  stellar_raw::poll_raw_capture(stopped_request, stellar_raw::wall_ms());
  assert((read64(second.data, 192) >> 32) == 2 &&
         (read64(second.data, 200) & 0xffffffffu) == 3);
  assert(!stellar_raw::producer.ready());
  assert(detail::replace_slot(table + 1,
                              reinterpret_cast<void *>(&stellar_raw::run_task),
                              reinterpret_cast<void *>(&original)));
  VirtualFree(table, 0, MEM_RELEASE);
  std::cout << "real raw hook fixture passed\n";
}
