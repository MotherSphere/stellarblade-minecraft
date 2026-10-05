#pragma once
#include "input.hpp"
#include <array>
#include <cmath>
#include <cstring>

namespace stellar_raw {
struct Metadata {
  std::string id, run, session;
  uint64_t time = 0, counter = 0;
  uint32_t width = 0, height = 0;
};
struct Job {
  std::string run, id;
  int kind = -1;
};
inline bool frame_id(std::string_view id, uint64_t &time, uint64_t &counter) {
  auto dash = id.find('-');
  return dash != std::string_view::npos && dash > 0 && dash <= 16 &&
         id.size() - dash - 1 <= 16 &&
         stellar_input::detail::number(id.substr(0, dash), time) &&
         stellar_input::detail::number(id.substr(dash + 1), counter) && counter;
}
inline bool decimal(std::string_view text, double &value) {
  if (text.empty())
    return false;
  if (text.front() == '+')
    text.remove_prefix(1);
  if (text.empty())
    return false;
  auto result = std::from_chars(text.data(), text.data() + text.size(), value);
  return result.ec == std::errc{} && result.ptr == text.data() + text.size() &&
         std::isfinite(value);
}
inline std::optional<Metadata> parse_metadata(std::string_view text) {
  using namespace stellar_input::detail;
  Lines lines(text, 2048);
  Metadata result;
  auto header = lines.field("SBMC_FRAME", 3);
  uint64_t timestamp, width, height, n;
  if (header.empty() || header[0] != "5" ||
      !frame_id(header[1], timestamp, result.counter) ||
      !number(header[2], result.time) || timestamp != result.time)
    return {};
  result.id = header[1];
  auto size = lines.field("SIZE", 2);
  if (size.empty() || !number(size[0], width) || !number(size[1], height) ||
      !width || width > 3840 || !height || height > 2160)
    return {};
  result.width = static_cast<uint32_t>(width);
  result.height = static_cast<uint32_t>(height);
  auto camera = lines.field("CAMERA", 7);
  double value = 0;
  if (camera.empty())
    return {};
  for (auto field : camera)
    if (!decimal(field, value))
      return {};
  if (!(value > 0 && value < 180))
    return {};
  auto pawn = lines.field("PAWN", 4);
  if (pawn.empty())
    return {};
  for (auto field : pawn)
    if (!decimal(field, value))
      return {};
  if (value <= 0)
    return {};
  auto context = lines.field("CONTEXT", 2);
  if (context.empty())
    return {};
  for (auto field : context)
    if (!number(field, n) || !n)
      return {};
  auto link = lines.field("LINK", 6);
  if (link.empty() || !identity(link[0]) || !number(link[1], n) || !n ||
      n > 2147483647 || !decimal(link[2], value) || value <= 0)
    return {};
  result.session = link[0];
  for (size_t i = 3; i < link.size(); ++i)
    if (!decimal(link[i], value))
      return {};
  auto color = lines.field("COLOR", 2);
  if (color.empty() || color[0] != "raw" || !number(color[1], n) ||
      n != width * height * 4 || !lines.literal("MASK none 0"))
    return {};
  auto depth = lines.field("DEPTH", 2);
  if (depth.empty() || depth[0] != "raw" || !number(depth[1], n) ||
      n != width * height * 4 || !lines.text("RUN", result.run) ||
      !identity(result.run))
    return {};
  auto end = lines.field("END", 1);
  if (end.empty() || end[0] != result.id || lines.at != lines.rows.size())
    return {};
  return result;
}
inline std::wstring normalized_path(std::wstring path) {
  std::replace(path.begin(), path.end(), L'\\', L'/');
  if (path.size() >= 2 && path[1] == L':' && path[0] >= L'a' && path[0] <= L'z')
    path[0] -= L'a' - L'A';
  return path;
}
inline std::optional<Job> parse_job(std::wstring_view path,
                                    std::wstring_view directory) {
  auto normalized = normalized_path(std::wstring(path));
  auto parent = normalized_path(std::wstring(directory));
  if (parent.empty())
    return {};
  if (parent.back() != L'/')
    parent += L'/';
  parent += L"eve-raw-";
  if (normalized.compare(0, parent.size(), parent) != 0)
    return {};
  auto tail = normalized.substr(parent.size());
  std::string name;
  for (wchar_t c : tail) {
    if (c < 32 || c > 126)
      return {};
    name += static_cast<char>(c);
  }
  if (name.size() < 35 || name[32] != '-')
    return {};
  Job job;
  job.run = name.substr(0, 32);
  if (!stellar_input::detail::identity(job.run))
    return {};
  static constexpr std::array<std::string_view, 3> suffixes{
      "-color.bmp", "-mask.bmp", "-depth.exr"};
  for (int kind = 0; kind < 3; ++kind) {
    auto suffix = suffixes[kind];
    if (name.size() <= 33 + suffix.size() ||
        name.compare(name.size() - suffix.size(), suffix.size(), suffix) != 0)
      continue;
    job.id = name.substr(33, name.size() - 33 - suffix.size());
    uint64_t time, counter;
    if (!frame_id(job.id, time, counter))
      return {};
    job.kind = kind;
    return job;
  }
  return {};
}

// These addresses are supplied from the pinned executable after identity and
// code checks. The portable reader is also used by the isolated task fixture.
struct Profile {
  uintptr_t task_vtable, color_vtable, depth_vtable;
  uintptr_t color_getter, depth_getter, completion_invoke;
  uintptr_t completion_holder_vtable;
  uint32_t none_name;
};
struct TaskView {
  std::wstring filename;
  const void *pixels = nullptr;
  uint8_t format = 0;
  int32_t preprocessors = 0;
  bool inert_completion = false;
};
struct Plane {
  const void *data = nullptr;
  size_t bytes = 0;
};
using ReadMemory = bool (*)(const void *, void *, size_t);
template <class T, size_t N>
inline T at(const std::array<unsigned char, N> &bytes, size_t offset) {
  T value;
  std::memcpy(&value, bytes.data() + offset, sizeof(value));
  return value;
}
inline std::optional<TaskView>
inspect_task(const void *task, const Profile &profile, ReadMemory read) {
  std::array<unsigned char, 0x90> bytes{};
  if (!read(task, bytes.data(), bytes.size()) ||
      at<uintptr_t>(bytes, 0) != profile.task_vtable)
    return {};
  auto length = at<int32_t>(bytes, 0x10), capacity = at<int32_t>(bytes, 0x14);
  if (length < 2 || length > 4096 || capacity < length)
    return {};
  std::u16string filename(static_cast<size_t>(length), u'\0');
  if (!read(at<const void *>(bytes, 8), filename.data(), filename.size() * 2) ||
      filename.back() != 0 || filename.find(u'\0') != filename.size() - 1)
    return {};
  filename.pop_back();
  TaskView view;
  view.filename.assign(filename.begin(), filename.end());
  view.pixels = at<const void *>(bytes, 0x70);
  view.format = at<uint8_t>(bytes, 0x18);
  view.preprocessors = at<int32_t>(bytes, 0x80);
  if (at<uintptr_t>(bytes, 0x30) != profile.completion_invoke)
    return view;
  std::array<unsigned char, 0x60> holder{};
  if (!read(at<const void *>(bytes, 0x40), holder.data(), holder.size()))
    return view;
  view.inert_completion =
      at<uintptr_t>(holder, 0) == profile.completion_holder_vtable &&
      at<uintptr_t>(holder, 0x10) == 0 &&
      at<uint32_t>(holder, 0x58) == profile.none_name &&
      at<uint32_t>(holder, 0x5c) == 0;
  return view;
}
inline std::optional<Plane> inspect_plane(const TaskView &view,
                                          const Profile &profile,
                                          const Metadata &metadata,
                                          const Job &job, ReadMemory read) {
  if (view.preprocessors != 0 || job.kind < 0 || job.kind > 2 ||
      job.run != metadata.run || job.id != metadata.id ||
      view.format != (job.kind == 2 ? 5 : 3))
    return {};
  std::array<unsigned char, 0x40> pixels{};
  if (!read(view.pixels, pixels.data(), pixels.size()))
    return {};
  bool depth = job.kind == 2;
  auto vtable = depth ? profile.depth_vtable : profile.color_vtable;
  auto getter = depth ? profile.depth_getter : profile.color_getter;
  uintptr_t virtual_getter = 0;
  if (at<uintptr_t>(pixels, 0) != vtable ||
      at<int32_t>(pixels, 8) != static_cast<int32_t>(metadata.width) ||
      at<int32_t>(pixels, 12) != static_cast<int32_t>(metadata.height) ||
      at<int32_t>(pixels, 0x10) != (depth ? 1 : 0) ||
      at<uint8_t>(pixels, 0x14) != (depth ? 0 : 1) ||
      at<uint8_t>(pixels, 0x15) != (depth ? 16 : 8) ||
      at<uint8_t>(pixels, 0x16) != 4 || at<uintptr_t>(pixels, 0x18) ||
      at<uintptr_t>(pixels, 0x20) ||
      !read(reinterpret_cast<const void *>(vtable + 8), &virtual_getter,
            sizeof(virtual_getter)) ||
      virtual_getter != getter)
    return {};
  auto count = int64_t(metadata.width) * metadata.height;
  if (at<int64_t>(pixels, 0x30) != count || at<int64_t>(pixels, 0x38) < count)
    return {};
  using Getter = void (*)(const void *, const void **, int64_t *);
  const void *data = nullptr;
  int64_t size = 0;
  reinterpret_cast<Getter>(getter)(view.pixels, &data, &size);
  if (!data || size != count * (depth ? 8 : 4) ||
      data != at<const void *>(pixels, 0x28))
    return {};
  return Plane{data, static_cast<size_t>(size)};
}
namespace detail {
bool replace_slot(void **slot, void *expected, void *replacement);
}
bool initialize_raw_capture(void *module, const std::wstring &directory);
void poll_raw_capture(std::string_view request, uint64_t now_ms);
} // namespace stellar_raw
