#include "pose_stream.hpp"
#include "input.hpp"
#include "shared_memory.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <mutex>
#ifndef _WIN32
#include <unistd.h>
#endif

namespace stellar_pose {
namespace {
constexpr uint32_t Slots = 3, Dirty = 4, Format = 1;
constexpr uint64_t Lease = 3000;
using stellar_input::detail::identity;
using stellar_input::detail::Lines;
using stellar_input::detail::number;
using stellar_shared::exchange32;
using stellar_shared::load32;
using stellar_shared::load64;
using stellar_shared::monotonic_now;
using stellar_shared::plain;
using stellar_shared::put;
using stellar_shared::store64;

// USkeletalMeshComponent and FUObjectArray layout of the pinned 4.26 build,
// taken from execSnapshotPose, USkeletalMeshComponent::SnapshotPose,
// execK2_GetComponentToWorld and inlined FUObjectArray::IndexToObject.
constexpr size_t InternalIndex = 0x0c, ClassPrivate = 0x10, WorldTransform = 0x1f0,
                 SpaceTransforms = 0x5a8, SkeletalMesh = 0x568,
                 ReadTransforms = 0x674, MorphWeights = 0x738;
// GUObjectArray: ObjObjects at 0x10 (chunk table pointer first), NumElements
// at ObjObjects + 0x14; 64K items per chunk, 24-byte FUObjectItem.
constexpr size_t ChunkTable = 0x10, ElementCount = 0x24, ItemBytes = 24,
                 ItemFlags = 8;
constexpr int32_t PendingKill = 1 << 29, Unreachable = 1 << 28;

bool fresh(uint64_t value, uint64_t now) {
  return value && (value <= now ? now - value <= Lease : value - now <= 100);
}
bool middle_valid(uint32_t state) {
  return (state & ~7u) == 0 && (state & 3u) < Slots;
}
std::vector<std::string_view> split(std::string_view line) {
  std::vector<std::string_view> parts;
  size_t p = 0;
  while (p <= line.size()) {
    size_t e = line.find(' ', p);
    if (e == std::string_view::npos)
      e = line.size();
    parts.push_back(line.substr(p, e - p));
    if (e == line.size())
      break;
    p = e + 1;
  }
  return parts;
}
bool address(std::string_view s, uint64_t &out) {
  if (s.size() != 16)
    return false;
  out = 0;
  for (char c : s) {
    int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    if (digit < 0)
      return false;
    out = out << 4 | uint64_t(digit);
  }
  return out != 0 && (out & 7) == 0;
}
bool hex32(std::string_view s, uint64_t &out) {
  out = 0;
  for (char c : s) {
    int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    if (digit < 0)
      return false;
    out = out << 4 | uint64_t(digit);
  }
  return !s.empty();
}
bool names(std::string_view line, std::string_view key, uint32_t index,
           uint32_t count) {
  auto parts = split(line);
  uint64_t n = 0;
  if (parts.size() != count + 2 || parts[0] != key || !number(parts[1], n) ||
      n != index)
    return false;
  for (size_t i = 2; i < parts.size(); i++)
    if (parts[i].empty())
      return false;
  return true;
}
template <class T> T at(uintptr_t where) {
  T value;
  std::memcpy(&value, reinterpret_cast<const void *>(where), sizeof value);
  return value;
}
bool alive(uintptr_t objects, uintptr_t object) {
  auto index = at<int32_t>(object + InternalIndex);
  if (index < 0 || index >= at<int32_t>(objects + ElementCount))
    return false;
  auto chunks = at<uintptr_t>(objects + ChunkTable);
  auto chunk = chunks ? at<uintptr_t>(chunks + 8 * uintptr_t(index >> 16)) : 0;
  if (!chunk)
    return false;
  auto item = chunk + ItemBytes * uintptr_t(index & 0xffff);
  return at<uintptr_t>(item) == object &&
         !(at<int32_t>(item + ItemFlags) & (PendingKill | Unreachable));
}
// FTransform in memory: rotation xyzw, translation xyz + pad, scale xyz + pad.
void transform(uintptr_t source, float *out) {
  float raw[12];
  std::memcpy(raw, reinterpret_cast<const void *>(source), sizeof raw);
  std::memcpy(out, raw, 7 * sizeof(float));
  std::memcpy(out + 7, raw + 8, 3 * sizeof(float));
}
} // namespace

std::optional<Request> parse_request(std::string_view text) {
  Lines lines(text, 512);
  Request result;
  std::string state;
  if (!lines.literal("SBMC_POSE_REQUEST 1") || !lines.text("RUN", result.run) ||
      !identity(result.run) || !lines.num("TIME", result.time) ||
      !lines.text("STATE", state) || (state != "active" && state != "stopped") ||
      !lines.literal("END") || lines.at != lines.rows.size())
    return {};
  result.active = state == "active";
  return result;
}

std::optional<Table> parse_components(std::string_view text) {
  Lines lines(text, 65536);
  Table table;
  std::string session;
  uint64_t count = 0;
  if (!lines.literal("SBMC_POSE_COMPONENTS 1") || !lines.text("RUN", table.run) ||
      !identity(table.run) || !lines.num("GENERATION", table.generation) ||
      !table.generation || !lines.text("SESSION", session) ||
      (session != "none" && !identity(session)) || lines.at >= lines.rows.size())
    return {};
  auto anchor = split(lines.rows[lines.at++]);
  bool none = anchor.size() == 2 && anchor[1] == "none";
  if (anchor.empty() || anchor[0] != "ANCHOR" || (!none && anchor.size() != 5))
    return {};
  if (lines.at >= lines.rows.size())
    return {};
  auto controller = split(lines.rows[lines.at++]);
  if (controller.size() == 4 && controller[0] == "CONTROLLER") {
    Controller c;
    uint64_t offset = 0;
    if (!address(controller[1], c.object) || !address(controller[2], c.cls) ||
        controller[3].size() != 8 || !hex32(controller[3], offset) || !offset ||
        offset >= 0x4000 || offset % 4)
      return {};
    c.offset = static_cast<uint32_t>(offset);
    table.controller = c;
  } else if (controller.size() != 2 || controller[0] != "CONTROLLER" || controller[1] != "none")
    return {};
  if (!lines.num("COUNT", count) || !count || count > MaxComponents)
    return {};
  uint64_t floats = 0;
  for (uint32_t i = 1; i <= count; i++) {
    if (lines.at >= lines.rows.size())
      return {};
    auto f = split(lines.rows[lines.at++]);
    Component c;
    uint64_t index = 0, bones = 0, morphs = 0;
    if (f.size() != 10 || f[0] != "COMPONENT" || !number(f[1], index) ||
        index != i || !address(f[2], c.object) || !address(f[3], c.cls) ||
        !address(f[4], c.mesh) || !number(f[5], bones) || bones > MaxBones ||
        !number(f[6], morphs) || morphs > MaxMorphs || f[7].empty() ||
        f[8].empty() || f[9].empty())
      return {};
    c.bones = static_cast<uint32_t>(bones);
    c.morphs = static_cast<uint32_t>(morphs);
    if (c.bones && (lines.at >= lines.rows.size() ||
                    !names(lines.rows[lines.at++], "BONES", i, c.bones)))
      return {};
    if (c.morphs && (lines.at >= lines.rows.size() ||
                     !names(lines.rows[lines.at++], "MORPHS", i, c.morphs)))
      return {};
    floats += 10 + 10 * uint64_t(c.bones) + c.morphs;
    table.components.push_back(c);
  }
  if (floats > Capacity || !lines.end(table.generation))
    return {};
  table.floats = static_cast<uint32_t>(floats);
  return table;
}

std::optional<Table> select_components(std::string_view slot0,
                                       std::string_view slot1,
                                       std::string_view run) {
  auto a = parse_components(slot0), b = parse_components(slot1);
  if (a && a->run != run)
    a.reset();
  if (b && b->run != run)
    b.reset();
  if (a && b)
    return a->generation >= b->generation ? a : b;
  return a ? a : b;
}

uint32_t read_component(const Engine &engine, const Component &c, float *out) {
  std::memset(out, 0, sizeof(float) * (10 + 10 * size_t(c.bones) + c.morphs));
  auto object = static_cast<uintptr_t>(c.object);
  if (!engine.objects || !alive(engine.objects, object) ||
      at<uint64_t>(object + ClassPrivate) != c.cls ||
      at<uint64_t>(object + SkeletalMesh) != c.mesh)
    return 0;
  uint32_t status = WorldValid;
  transform(object + WorldTransform, out);
  if (c.bones) {
    auto read = at<int32_t>(object + ReadTransforms);
    auto array = object + SpaceTransforms + 16 * uintptr_t(read);
    auto data = read == 0 || read == 1 ? at<uintptr_t>(array) : 0;
    if (data && at<int32_t>(array + 8) == int32_t(c.bones)) {
      for (uint32_t k = 0; k < c.bones; k++)
        transform(data + 48 * uintptr_t(k), out + 10 + 10 * k);
      status |= BonesValid;
    }
  }
  if (c.morphs) {
    auto data = at<uintptr_t>(object + MorphWeights);
    if (data && at<int32_t>(object + MorphWeights + 8) == int32_t(c.morphs)) {
      std::memcpy(out + 10 + 10 * c.bones, reinterpret_cast<const void *>(data),
                  sizeof(float) * c.morphs);
      status |= MorphsValid;
    }
  }
  return status;
}

bool read_controller(const Engine &engine, const Controller &c, float *out) {
  std::memset(out, 0, 3 * sizeof(float));
  auto object = static_cast<uintptr_t>(c.object);
  if (!engine.objects || !alive(engine.objects, object) ||
      at<uint64_t>(object + ClassPrivate) != c.cls)
    return false;
  std::memcpy(out, reinterpret_cast<const void *>(object + c.offset), 3 * sizeof(float));
  return true;
}

struct Producer::Impl {
  stellar_shared::Mapping file;
  uint8_t *base = nullptr;
  std::string run;
  uint32_t back = 1;
  uint64_t counter = 0, heartbeat = 0, observed = 0, total_us = 0;
  uint32_t max_us = 0;
  bool owned = false;
  void release() {
    file.close();
    base = nullptr;
    owned = false;
  }
  bool valid_header(const Request &r, uint64_t now) const {
    if (std::memcmp(base, "SBMCPOS1", 8) || plain<uint32_t>(base, 8) != 1 ||
        plain<uint32_t>(base, 12) != Header ||
        plain<uint32_t>(base, 16) != SlotBytes ||
        plain<uint32_t>(base, 20) != Capacity ||
        plain<uint32_t>(base, 28) != TotalBytes ||
        std::memcmp(base + 32, r.run.data(), 32) || !plain<uint32_t>(base, 96) ||
        load32(base, 128) != 0 || load32(base, 192) != 0 ||
        load32(base, 196) != 0 || load32(base, 200) != 0 ||
        load64(base, 208) != 0 || load64(base, 216) != 0 ||
        load32(base, 256) != 1 || !fresh(load64(base, 264), now) ||
        load64(base, 272) != 0)
      return false;
    for (auto range : {std::pair<size_t, size_t>{24, 28},
                       {64, 96},
                       {100, 128},
                       {132, 192},
                       {204, 208},
                       {224, 256},
                       {260, 264},
                       {280, Header}})
      for (size_t i = range.first; i < range.second; i++)
        if (base[i] != 0)
          return false;
    return true;
  }
};

Producer::Producer() : impl_(std::make_unique<Impl>()) {}
Producer::~Producer() { stop(); }
const std::string &Producer::run() const { return impl_->run; }
bool Producer::open(const std::wstring &directory, const Request &request,
                    uint32_t pid, uint64_t wall) {
  auto &self = *impl_;
  if (!request.active || !identity(request.run) || !pid)
    return false;
  if (self.base && self.run == request.run) {
    heartbeat(wall);
    return active(wall);
  }
  stop();
  auto path = std::filesystem::path(directory) / ("eve-pose-" + request.run + ".bin");
  if (!self.file.open(path, TotalBytes))
    return false;
  self.base = self.file.base;
  if (!self.valid_header(request, wall) ||
      !stellar_shared::claim(self.base, 192, pid)) {
    self.release();
    return false;
  }
  self.owned = true;
  self.run = request.run;
  self.back = 1;
  self.counter = self.total_us = 0;
  self.max_us = 0;
  self.heartbeat = load64(self.base, 264);
  self.observed = monotonic_now();
  store64(self.base, 208, wall);
  exchange32(self.base, 196, 1);
  return true;
}
bool Producer::active(uint64_t wall) const {
  const auto &self = *impl_;
  return self.base && self.owned && load32(self.base, 196) == 1 &&
         load32(self.base, 256) == 1 && middle_valid(load32(self.base, 128)) &&
         fresh(load64(self.base, 264), wall) &&
         monotonic_now() - self.observed < Lease;
}
void Producer::heartbeat(uint64_t wall) {
  auto &self = *impl_;
  if (!self.base || !self.owned || load32(self.base, 196) != 1)
    return;
  if (!middle_valid(load32(self.base, 128))) {
    fail(1);
    return;
  }
  auto beat = load64(self.base, 264);
  if (!active(wall) || beat < self.heartbeat) {
    stop();
    return;
  }
  if (beat > self.heartbeat) {
    self.heartbeat = beat;
    self.observed = monotonic_now();
  }
  store64(self.base, 208, wall);
}
bool Producer::publish(uint64_t generation, const uint32_t *status,
                       uint32_t count, const float *payload, uint32_t floats,
                       const float *rotation, uint64_t wall,
                       uint32_t read_us) {
  auto &self = *impl_;
  if (!active(wall) || !generation || generation > stellar_input::max_integer ||
      !count || count > MaxComponents || floats > Capacity) {
    return false;
  }
  auto *slot = self.base + Header + size_t(SlotBytes) * self.back;
  std::memset(slot, 0, SlotHeader);
  put(slot, 0, ++self.counter);
  put(slot, 8, wall);
  put(slot, 16, generation);
  put(slot, 24, Format);
  put(slot, 28, count);
  put(slot, 32, floats);
  // Controller rotation: status at 36, Pitch, Yaw, Roll at 40.
  if (rotation) {
    put(slot, 36, uint32_t(1));
    std::memcpy(slot + 40, rotation, 3 * sizeof(float));
  }
  std::memcpy(slot + 64, status, sizeof(uint32_t) * count);
  std::memcpy(slot + SlotHeader, payload, sizeof(float) * floats);
  // Same back/middle/front exchange as the raw frame mapping.
  auto previous = exchange32(self.base, 128, self.back | Dirty);
  if (!middle_valid(previous)) {
    fail(1);
    return false;
  }
  self.back = previous & 3u;
  self.total_us += read_us;
  self.max_us = std::max(self.max_us, read_us);
  put(self.base, 224, read_us);
  put(self.base, 228, self.max_us);
  store64(self.base, 232, self.total_us);
  store64(self.base, 216, self.counter);
  return true;
}
void Producer::fail(uint32_t error) {
  auto &self = *impl_;
  if (self.base && self.owned) {
    exchange32(self.base, 200, error >= 1 && error <= 3 ? error : 3);
    exchange32(self.base, 196, 3);
  }
}
void Producer::stop() {
  auto &self = *impl_;
  // State 2 acknowledges release; the consumer may then delete the mapping.
  if (self.base && self.owned)
    exchange32(self.base, 196, 2);
  self.release();
  self.run.clear();
}

namespace {
std::mutex lock; // The game thread only try-locks; it never waits for the worker.
Engine engine;
Producer producer;
std::optional<Table> table;
std::vector<float> payload(Capacity);
std::array<uint32_t, MaxComponents> status{};
uint32_t process_id() {
#ifdef _WIN32
  return GetCurrentProcessId();
#else
  return static_cast<uint32_t>(getpid());
#endif
}
uint64_t micros() {
#ifdef _WIN32
  LARGE_INTEGER counter, frequency;
  QueryPerformanceCounter(&counter);
  QueryPerformanceFrequency(&frequency);
  return static_cast<uint64_t>(counter.QuadPart * 1000000 / frequency.QuadPart);
#else
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                   std::chrono::steady_clock::now().time_since_epoch())
                                   .count());
#endif
}
#ifdef _WIN32
// A component freed between Lua's publication and this read faults at worst;
// contain it like an invalid component. No C++ object lives in this frame.
uint32_t guarded(const Engine &e, const Component &c, float *out) {
  __try {
    return read_component(e, c, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    std::memset(out, 0, sizeof(float) * (10 + 10 * size_t(c.bones) + c.morphs));
    return 0;
  }
}
bool guarded(const Engine &e, const Controller &c, float *out) {
  __try {
    return read_controller(e, c, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    std::memset(out, 0, 3 * sizeof(float));
    return false;
  }
}
#else
uint32_t guarded(const Engine &e, const Component &c, float *out) {
  return read_component(e, c, out);
}
bool guarded(const Engine &e, const Controller &c, float *out) {
  return read_controller(e, c, out);
}
#endif
} // namespace

void initialize(uintptr_t objects) { engine.objects = objects; }

void poll_files(const std::wstring &directory, std::string_view request_text,
                std::string_view slot0, std::string_view slot1, uint64_t wall) {
  auto request = parse_request(request_text);
  bool wanted = request && request->active && fresh(request->time, wall);
  auto next = wanted ? select_components(slot0, slot1, request->run)
                     : std::optional<Table>{};
  std::lock_guard<std::mutex> guard(lock);
  if (!wanted) {
    producer.stop();
    table.reset();
    return;
  }
  if (!producer.open(directory, *request, process_id(), wall)) {
    table.reset();
    return;
  }
  if (!next)
    table.reset();
  else if (!table || table->generation != next->generation)
    table = std::move(next);
}

void on_gameplay_poll() {
  std::unique_lock<std::mutex> guard(lock, std::try_to_lock);
  if (!guard.owns_lock() || !table || !engine.objects)
    return;
  auto wall = stellar_shared::wall_now();
  if (!producer.active(wall))
    return;
  auto started = micros();
  uint32_t offset = 0, count = 0;
  for (const auto &c : table->components) {
    status[count++] = guarded(engine, c, payload.data() + offset);
    offset += 10 + 10 * c.bones + c.morphs;
  }
  float rotation[3];
  bool rotated = table->controller && guarded(engine, *table->controller, rotation);
  auto elapsed = micros() - started;
  producer.publish(table->generation, status.data(), count, payload.data(),
                   offset, rotated ? rotation : nullptr, wall,
                   static_cast<uint32_t>(std::min<uint64_t>(elapsed, UINT32_MAX)));
}
} // namespace stellar_pose
