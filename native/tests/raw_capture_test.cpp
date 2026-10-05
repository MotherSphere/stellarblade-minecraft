#ifdef NDEBUG
#undef NDEBUG
#endif
#include "raw_capture.hpp"
#include <array>
#include <cassert>
#include <cstring>
#include <iostream>

using namespace stellar_raw;
static const std::string run(32, 'a'), session(32, 'b');
static std::string metadata() {
  return "SBMC_FRAME 6 100-7 100\nSIZE 2 1\nCAMERA 1 2 3 4 5 6 75\n"
         "PAWN 1 2 3 90\nCONTEXT 11 12\nLINK " +
         session + " 1 100 0 0 0\nCOLOR raw 8\nMASK depth 0\nDEPTH raw 8\nRUN " +
         run + "\nEND 100-7\n";
}
template <size_t N, class T>
static void put(std::array<unsigned char, N> &bytes, size_t offset, T value) {
  std::memcpy(bytes.data() + offset, &value, sizeof(value));
}
static void raw_pixels(const void *pixels, const void **data, int64_t *size) {
  auto bytes = static_cast<const unsigned char *>(pixels);
  std::memcpy(data, bytes + 0x28, sizeof(*data));
  std::memcpy(size, bytes + 0x30, sizeof(*size));
  *size *= 4;
}
static bool read_memory(const void *source, void *target, size_t size) {
  if (!source)
    return false;
  std::memcpy(target, source, size);
  return true;
}
int main() {
  auto descriptor = parse_metadata(metadata());
  assert(descriptor && descriptor->counter == 7 && descriptor->width == 2 &&
         descriptor->run == run && descriptor->session == session);
  for (auto bad :
       {metadata() + "\n", metadata().substr(1), std::string(2049, 'a')})
    assert(!parse_metadata(bad));
  auto bad = metadata();
  bad.replace(bad.find("75"), 2, "nan");
  assert(!parse_metadata(bad));
  bad = metadata();
  bad.replace(bad.find("raw 8"), 5, "raw 9");
  assert(!parse_metadata(bad));
  bad = metadata();
  bad.replace(bad.find("11 12"), 5, "0 12");
  assert(!parse_metadata(bad));
  // Frame-v5 implied a separate coverage plane; only v6 derives it from depth.
  bad = metadata();
  bad.replace(bad.find("SBMC_FRAME 6"), 12, "SBMC_FRAME 5");
  assert(!parse_metadata(bad));
  bad = metadata();
  bad.replace(bad.find("MASK depth 0"), 12, "MASK none 0");
  assert(!parse_metadata(bad));
  auto path = L"Z:/bridge/eve-raw-" + std::wstring(run.begin(), run.end()) +
              L"-100-7-color.bmp";
  auto job = parse_job(path, L"Z:\\bridge\\");
  assert(job && job->run == run && job->id == "100-7" && job->kind == 0);
  assert(!parse_job(L"Z:/elsewhere/" + path.substr(10), L"Z:/bridge/"));
  assert(!parse_job(path + L".extra", L"Z:/bridge/"));
  assert(!parse_job(L"Z:/bridge/../bridge/" + path.substr(10), L"Z:/bridge/"));
  assert(!parse_job(L"eve-raw-" + path.substr(18), L"Z:/bridge/"));

  // Exercise the same offset reader and raw getter used by the hook with
  // ordinary CPU-owned fixture memory. No alternate game identity is enabled.
  std::array<unsigned char, 0x90> task{};
  std::array<unsigned char, 0x60> holder{};
  std::array<unsigned char, 0x40> pixels{};
  std::array<uintptr_t, 2> table{0, reinterpret_cast<uintptr_t>(&raw_pixels)};
  std::array<unsigned char, 8> image{1, 2, 3, 4, 5, 6, 7, 8};
  Profile profile{0x1234, reinterpret_cast<uintptr_t>(table.data()),
                  0x2345, reinterpret_cast<uintptr_t>(&raw_pixels),
                  0x3456, 0x4567,
                  0x5678, 9};
  std::u16string name(path.begin(), path.end());
  put(task, 0, profile.task_vtable);
  put(task, 8, name.c_str());
  put(task, 0x10, int32_t(name.size() + 1));
  put(task, 0x14, int32_t(name.size() + 1));
  put(task, 0x18, uint8_t(3));
  put(task, 0x30, profile.completion_invoke);
  put(task, 0x40, holder.data());
  put(holder, 0, profile.completion_holder_vtable);
  put(holder, 0x58, profile.none_name);
  put(task, 0x70, pixels.data());
  put(pixels, 0, profile.color_vtable);
  put(pixels, 8, int32_t(2));
  put(pixels, 12, int32_t(1));
  put(pixels, 0x14, uint8_t(1));
  put(pixels, 0x15, uint8_t(8));
  put(pixels, 0x16, uint8_t(4));
  put(pixels, 0x28, image.data());
  put(pixels, 0x30, int64_t(2));
  put(pixels, 0x38, int64_t(2));
  auto view = inspect_task(task.data(), profile, read_memory);
  assert(view && view->filename == path && view->inert_completion);
  auto plane = inspect_plane(*view, profile, *descriptor, *job, read_memory);
  assert(plane && plane->data == image.data() && plane->bytes == 8);
  put(holder, 0x10, uintptr_t(1));
  view = inspect_task(task.data(), profile, read_memory);
  assert(view && !view->inert_completion); // Forward; preserve callbacks.
  put(holder, 0x10, uintptr_t(0));
  put(holder, 0x5c, uint32_t(1));
  assert(!inspect_task(task.data(), profile, read_memory)->inert_completion);
  put(holder, 0x5c, uint32_t(0));
  put(task, 0x80, int32_t(1));
  view = inspect_task(task.data(), profile, read_memory);
  assert(view &&
         !inspect_plane(*view, profile, *descriptor, *job, read_memory));
  put(task, 0x80, int32_t(0));
  put(pixels, 0x30, int64_t(3));
  view = inspect_task(task.data(), profile, read_memory);
  assert(!inspect_plane(*view, profile, *descriptor, *job, read_memory));
  put(pixels, 0x30, int64_t(2));
  put(task, 0x18, uint8_t(0));
  view = inspect_task(task.data(), profile, read_memory);
  assert(!inspect_plane(*view, profile, *descriptor, *job, read_memory));
  put(task, 0, uintptr_t(0));
  assert(!inspect_task(task.data(), profile, read_memory));
  std::cout << "raw task guards passed\n";
}
