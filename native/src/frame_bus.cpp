#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif
#include "frame_bus.hpp"
#include "raw_capture.hpp"
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
uint64_t wall_now() {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::system_clock::now().time_since_epoch())
          .count());
}
uint64_t monotonic_now() {
#ifdef _WIN32
  return GetTickCount64();
#else
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
#endif
}
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
template <class T> T plain(const uint8_t *base, size_t off) {
  T value;
  std::memcpy(&value, base + off, sizeof value);
  return value;
}
template <class T> void put(uint8_t *base, size_t off, T value) {
  std::memcpy(base + off, &value, sizeof value);
}
uint32_t load32(const uint8_t *base, size_t offset) {
#ifdef _WIN32
  return static_cast<uint32_t>(InterlockedCompareExchange(
      reinterpret_cast<volatile LONG *>(const_cast<uint8_t *>(base) + offset),
      0, 0));
#else
  return __atomic_load_n(reinterpret_cast<const uint32_t *>(base + offset),
                         __ATOMIC_ACQUIRE);
#endif
}
uint64_t load64(const uint8_t *base, size_t offset) {
#ifdef _WIN32
  return static_cast<uint64_t>(InterlockedCompareExchange64(
      reinterpret_cast<volatile LONG64 *>(const_cast<uint8_t *>(base) + offset),
      0, 0));
#else
  return __atomic_load_n(reinterpret_cast<const uint64_t *>(base + offset),
                         __ATOMIC_ACQUIRE);
#endif
}
uint32_t exchange32(uint8_t *base, size_t offset, uint32_t value) {
#ifdef _WIN32
  return static_cast<uint32_t>(
      InterlockedExchange(reinterpret_cast<volatile LONG *>(base + offset),
                          static_cast<LONG>(value)));
#else
  return __atomic_exchange_n(reinterpret_cast<uint32_t *>(base + offset), value,
                             __ATOMIC_ACQ_REL);
#endif
}
void store64(uint8_t *base, size_t offset, uint64_t value) {
#ifdef _WIN32
  InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(base + offset),
                        static_cast<LONG64>(value));
#else
  __atomic_store_n(reinterpret_cast<uint64_t *>(base + offset), value,
                   __ATOMIC_RELEASE);
#endif
}
bool claim(uint8_t *base, uint32_t pid) {
#ifdef _WIN32
  return InterlockedCompareExchange(
             reinterpret_cast<volatile LONG *>(base + 192),
             static_cast<LONG>(pid), 0) == 0;
#else
  uint32_t expected = 0;
  return __atomic_compare_exchange_n(reinterpret_cast<uint32_t *>(base + 192),
                                     &expected, pid, false, __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE);
#endif
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
#ifdef _WIN32
  HANDLE file = INVALID_HANDLE_VALUE, mapping = nullptr;
#else
  int file = -1;
#endif
  void release() {
    pending = false;
    metadata.clear();
#ifdef _WIN32
    if (base)
      UnmapViewOfFile(base);
    if (mapping)
      CloseHandle(mapping);
    if (file != INVALID_HANDLE_VALUE)
      CloseHandle(file);
    file = INVALID_HANDLE_VALUE;
    mapping = nullptr;
#else
    if (base)
      munmap(base, dimensions.bytes);
    if (file >= 0)
      ::close(file);
    file = -1;
#endif
    base = nullptr;
    owned = false;
  }
  ~Impl() { release(); }
  bool map_file(const std::filesystem::path &target, Layout d) {
    dimensions = d;
#ifdef _WIN32
    file = CreateFileW(target.c_str(), GENERIC_READ | GENERIC_WRITE,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       nullptr, OPEN_EXISTING,
                       FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
                       nullptr);
    if (file == INVALID_HANDLE_VALUE)
      return false;
    BY_HANDLE_FILE_INFORMATION info{};
    LARGE_INTEGER size{};
    if (!GetFileInformationByHandle(file, &info) ||
        (info.dwFileAttributes &
         (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) ||
        !GetFileSizeEx(file, &size) || size.QuadPart != d.bytes) {
      release();
      return false;
    }
    mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 0, nullptr);
    if (!mapping) {
      release();
      return false;
    }
    base = static_cast<uint8_t *>(
        MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, d.bytes));
#else
    file = ::open(target.c_str(), O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0)
      return false;
    struct stat info{};
    if (fstat(file, &info) != 0 || !S_ISREG(info.st_mode) ||
        info.st_size != d.bytes) {
      release();
      return false;
    }
    auto memory =
        mmap(nullptr, d.bytes, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
    base = memory == MAP_FAILED ? nullptr : static_cast<uint8_t *>(memory);
#endif
    if (!base) {
      release();
      return false;
    }
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
  if (!self.valid_header(request, wallMs) || !claim(self.base, pid)) {
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
  if (!self.pending || kind < 0 || kind > 2 || (self.seen & (1u << kind)) ||
      !pixels || width != self.request.width || height != self.request.height ||
      bytes != size_t(self.dimensions.pixels) * (kind == 2 ? 8 : 4)) {
    fail(2);
    return false;
  }
  auto *slot = self.base + Header + self.dimensions.stride * self.back;
  auto *rgba = slot + Header;
  auto *depth = rgba + self.dimensions.plane;
  auto *input = static_cast<const uint8_t *>(pixels);
  const bool have_mask = (self.seen & 2u) != 0, have_depth = (self.seen & 4u) != 0;
  for (size_t i = 0; i < self.dimensions.pixels; i++) {
    if (kind == 0) {
      rgba[4 * i] = input[4 * i + 2];
      rgba[4 * i + 1] = input[4 * i + 1];
      rgba[4 * i + 2] = input[4 * i];
    } else if (kind == 1) {
      // Whichever coverage/depth plane arrives second clears invalid pixels.
      rgba[4 * i + 3] = have_depth && plain<float>(depth, 4 * i) <= 0
                            ? 0
                            : 255 - input[4 * i + 3];
    } else {
      auto value = half_float(plain<uint16_t>(input, 8 * i));
      value = std::isfinite(value) && value > 0 ? value : 0.0f;
      put(depth, 4 * i, value);
      if (have_mask && value <= 0)
        rgba[4 * i + 3] = 0;
    }
  }
  self.seen |= 1u << kind;
  if (self.seen != 7)
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
