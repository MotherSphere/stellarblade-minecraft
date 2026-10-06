#include "frame_bus.hpp"
#include "raw_capture.hpp"
#include "shared_memory.hpp"
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <limits>

namespace stellar_frame {
namespace {
constexpr uint32_t Header = 4096, Slots = 3, Dirty = 4;
constexpr uint64_t Lease = 3000, Maximum = stellar_input::max_integer;
using Lines = stellar_input::detail::Lines;
using stellar_input::detail::identity;
using stellar_input::detail::number;
using stellar_shared::exchange32;
using stellar_shared::load32;
using stellar_shared::load64;
using stellar_shared::monotonic_now;
using stellar_shared::plain;
using stellar_shared::put;
using stellar_shared::store64;
using stellar_shared::wall_now;
bool fresh(uint64_t value, uint64_t now) {
  return value && (value <= now ? now - value <= Lease : value - now <= 100);
}
struct Layout {
  uint32_t pixels, plane, stride, bytes;
};
std::optional<Layout> layout(uint32_t width, uint32_t height) {
  if (!width || width > 3840 || !height || height > 2160)
    return {};
  uint64_t pixels = uint64_t(width) * height;
  uint64_t stride = (Header + pixels * 8 + 63) & ~uint64_t(63);
  uint64_t bytes = Header + stride * Slots;
  if (bytes > std::numeric_limits<uint32_t>::max())
    return {};
  return Layout{static_cast<uint32_t>(pixels),
                static_cast<uint32_t>(pixels * 4),
                static_cast<uint32_t>(stride), static_cast<uint32_t>(bytes)};
}
bool middle_valid(uint32_t state) {
  return (state & ~7u) == 0 && (state & 3u) < Slots;
}
float half_float(uint16_t bits) {
  uint32_t sign = uint32_t(bits & 0x8000u) << 16;
  uint32_t exponent = (bits >> 10) & 31u, mantissa = bits & 1023u;
  uint32_t value = sign;
  // Construct IEEE754 bits directly: a Windows CRT ldexp call per depth pixel
  // otherwise dominates full-resolution frames. Preserve canonical quiet NaNs.
  if (exponent == 31)
    value |= 0x7f800000u | (mantissa ? 0x00400000u : 0u);
  else if (exponent)
    value |= ((exponent + 112u) << 23) | (mantissa << 13);
  else if (mantissa) {
    exponent = 113;
    while (!(mantissa & 1024u)) {
      mantissa <<= 1;
      --exponent;
    }
    value |= (exponent << 23) | ((mantissa & 1023u) << 13);
  }
  float result;
  std::memcpy(&result, &value, sizeof result);
  return result;
}
} // namespace

std::optional<Request> parse_request(std::string_view text) {
  Lines lines(text, 512);
  Request result{};
  std::string state;
  uint64_t width = 0, height = 0;
  if (!lines.literal("SBMC_CAPTURE_SESSION 2") ||
      !lines.text("RUN", result.run) || !identity(result.run) ||
      !lines.text("SESSION", result.session) || !identity(result.session) ||
      !lines.num("TIME", result.time))
    return {};
  auto size = lines.field("SIZE", 2);
  if (size.empty() || !number(size[0], width) || !number(size[1], height) ||
      width > 3840 || height > 2160 ||
      !layout(static_cast<uint32_t>(width), static_cast<uint32_t>(height)) ||
      !lines.text("STATE", state) ||
      (state != "active" && state != "stopped") || !lines.literal("END") ||
      lines.at != lines.rows.size())
    return {};
  result.width = static_cast<uint32_t>(width);
  result.height = static_cast<uint32_t>(height);
  result.active = state == "active";
  return result;
}

struct Producer::Impl {
  uint8_t *base = nullptr;
  std::filesystem::path path;
  Request request{};
  Layout dimensions{};
  uint32_t back = 1, seen = 0;
  uint64_t counter = 0, heartbeat = 0, observed = 0;
  std::string metadata;
  bool owned = false, pending = false;
  stellar_shared::Mapping file;
  void release() {
    pending = false;
    metadata.clear();
    file.close();
    base = nullptr;
    owned = false;
  }
  ~Impl() { release(); }
  bool map_file(const std::filesystem::path &target, Layout d) {
    dimensions = d;
    if (!file.open(target, d.bytes))
      return false;
    base = file.base;
    return true;
  }
  bool valid_header(const Request &r, uint64_t now) const {
    if (std::memcmp(base, "SBMCFRM1", 8) || plain<uint32_t>(base, 8) != 1 ||
        plain<uint32_t>(base, 12) != Header ||
        plain<uint32_t>(base, 16) != r.width ||
        plain<uint32_t>(base, 20) != r.height ||
        plain<uint32_t>(base, 24) != dimensions.stride ||
        plain<uint32_t>(base, 28) != dimensions.bytes ||
        std::memcmp(base + 32, r.run.data(), 32) ||
        std::memcmp(base + 64, r.session.data(), 32) ||
        !plain<uint32_t>(base, 96) || load32(base, 128) != 0 ||
        load32(base, 192) != 0 || load32(base, 196) != 0 ||
        load32(base, 200) != 0 || load64(base, 208) != 0 ||
        load64(base, 216) != 0 || load32(base, 256) != 1 ||
        !fresh(load64(base, 264), now) || load64(base, 272) != 0)
      return false;
    for (auto range : {std::pair<size_t, size_t>{100, 128},
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
bool Producer::open(const std::wstring &directory, const Request &request,
                    uint32_t pid, uint64_t wallMs) {
  auto &self = *impl_;
  if (!request.active || !identity(request.run) || !identity(request.session) ||
      !pid)
    return false;
  auto dimensions = layout(request.width, request.height);
  if (!dimensions)
    return false;
  auto path =
      std::filesystem::path(directory) / ("eve-frames-" + request.run + ".bin");
  if (self.base && self.path == path && self.request.run == request.run &&
      self.request.session == request.session &&
      self.request.width == request.width &&
      self.request.height == request.height) {
    heartbeat(wallMs);
    return ready();
  }
  stop();
  if (!self.map_file(path, *dimensions))
    return false;
  if (!self.valid_header(request, wallMs) ||
      !stellar_shared::claim(self.base, 192, pid)) {
    self.release();
    return false;
  }
  self.owned = true;
  self.path = path;
  self.request = request;
  self.back = 1;
  self.seen = 0;
  self.counter = 0;
  self.pending = false;
  self.heartbeat = load64(self.base, 264);
  self.observed = monotonic_now();
  store64(self.base, 208, wallMs);
  exchange32(self.base, 196, 1);
  return true;
}
bool Producer::ready() const {
  const auto &self = *impl_;
  return self.base && self.owned && load32(self.base, 196) == 1;
}
bool Producer::active(uint64_t wallMs) const {
  const auto &self = *impl_;
  return ready() && load32(self.base, 256) == 1 &&
         middle_valid(load32(self.base, 128)) &&
         fresh(load64(self.base, 264), wallMs) &&
         monotonic_now() - self.observed < Lease;
}
void Producer::heartbeat(uint64_t wallMs) {
  auto &self = *impl_;
  if (!ready())
    return;
  if (!middle_valid(load32(self.base, 128))) {
    fail(1);
    return;
  }
  if (!active(wallMs)) {
    stop();
    return;
  }
  auto beat = load64(self.base, 264);
  if (beat < self.heartbeat) {
    stop();
    return;
  }
  if (beat > self.heartbeat) {
    self.heartbeat = beat;
    self.observed = monotonic_now();
  }
  store64(self.base, 208, wallMs);
}
bool Producer::begin(std::string_view metadata, uint64_t counter) {
  auto &self = *impl_;
  if (!active(wall_now())) {
    stop();
    return false;
  }
  if (self.pending) {
    if (self.counter == counter && self.metadata == metadata)
      return true;
    fail(1);
    return false;
  }
  auto parsed = stellar_raw::parse_metadata(metadata);
  if (counter <= self.counter || counter > Maximum || !parsed ||
      parsed->run != self.request.run ||
      parsed->session != self.request.session ||
      parsed->width != self.request.width ||
      parsed->height != self.request.height || parsed->counter != counter) {
    fail(1);
    return false;
  }
  self.counter = counter;
  self.metadata = std::string(metadata);
  self.seen = 0;
  self.pending = true;
  auto *slot = self.base + Header + self.dimensions.stride * self.back;
  std::memset(slot, 0, Header);
  put(slot, 0, counter);
  put(slot, 16, static_cast<uint32_t>(metadata.size()));
  put(slot, 20, uint32_t(1));
  std::memcpy(slot + 64, metadata.data(), metadata.size());
  return true;
}
bool Producer::plane(int kind, const void *pixels, size_t bytes, uint32_t width,
                     uint32_t height) {
  auto &self = *impl_;
  if (!active(wall_now())) {
    stop();
    return false;
  }
  // Frame-v6 has color (0) and depth (2) planes; kind 1 is a retired mask.
  if (!self.pending || (kind != 0 && kind != 2) || (self.seen & (1u << kind)) ||
      !pixels || width != self.request.width || height != self.request.height ||
      bytes != size_t(self.dimensions.pixels) * (kind == 2 ? 8 : 4)) {
    fail(2);
    return false;
  }
  auto *slot = self.base + Header + self.dimensions.stride * self.back;
  auto *rgba = slot + Header;
  auto *depth = rgba + self.dimensions.plane;
  auto *input = static_cast<const uint8_t *>(pixels);
  if (kind == 0) {
    for (size_t i = 0; i < self.dimensions.pixels; i++) {
      rgba[4 * i] = input[4 * i + 2];
      rgba[4 * i + 1] = input[4 * i + 1];
      rgba[4 * i + 2] = input[4 * i];
    }
  } else {
    for (size_t i = 0; i < self.dimensions.pixels; i++) {
      auto value = half_float(plain<uint16_t>(input, 8 * i));
      value = std::isfinite(value) && value > 0 ? value : 0.0f;
      put(depth, 4 * i, value);
      // Captures clear unrendered pixels to the largest finite binary16 depth.
      // Coverage is exactly the nearer depths; the color plane never owns alpha.
      rgba[4 * i + 3] = value > 0 && value < 65504.0f ? 255 : 0;
    }
  }
  self.seen |= 1u << kind;
  if (self.seen != 5)
    return true;
  put(slot, 8, wall_now());
  // Adapted from SkyCraft's MIT-licensed overlay triple buffer: publish a
  // private back slot and take only the previous middle, never the held front.
  auto previous = exchange32(self.base, 128, self.back | Dirty);
  if (!middle_valid(previous)) {
    fail(1);
    return false;
  }
  self.back = previous & 3u;
  self.pending = false;
  store64(self.base, 216, self.counter);
  return true;
}
bool Producer::pending() const { return impl_->pending; }
void Producer::fail(uint32_t error) {
  auto &self = *impl_;
  self.pending = false;
  if (self.base && self.owned) {
    exchange32(self.base, 200, error >= 1 && error <= 3 ? error : 3);
    exchange32(self.base, 196, 3);
  }
}
void Producer::stop() {
  auto &self = *impl_;
  // State 2 acknowledges release even after a failure. Keep producerError so
  // the consumer can report the cause and still retire this owned mapping.
  if (self.base && self.owned)
    exchange32(self.base, 196, 2);
  self.release();
}
} // namespace stellar_frame
