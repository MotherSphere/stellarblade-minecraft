#ifdef NDEBUG
#undef NDEBUG
#endif
#include "frame_bus.hpp"
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>

using namespace stellar_frame;
static uint64_t now_ms() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::system_clock::now().time_since_epoch())
      .count();
}
template <class T>
static void put(std::vector<uint8_t> &b, size_t at, T value) {
  std::memcpy(b.data() + at, &value, sizeof value);
}
template <class T> static T read(const std::filesystem::path &path, size_t at) {
  std::ifstream f(path, std::ios::binary);
  f.seekg(at);
  T value{};
  f.read(reinterpret_cast<char *>(&value), sizeof value);
  assert(f);
  return value;
}
template <class T>
static void write(const std::filesystem::path &path, size_t at, T value) {
  std::fstream f(path, std::ios::in | std::ios::out | std::ios::binary);
  f.seekp(at);
  f.write(reinterpret_cast<const char *>(&value), sizeof value);
  assert(f);
}
static std::string metadata(const Request &r, uint64_t seq) {
  auto id = std::to_string(r.time) + "-" + std::to_string(seq);
  auto bytes = std::to_string(uint64_t(r.width) * r.height * 4);
  return "SBMC_FRAME 6 " + id + " " + std::to_string(r.time) + "\nSIZE " +
         std::to_string(r.width) + " " + std::to_string(r.height) +
         "\nCAMERA 1 2 3 -10 40 0 75\nPAWN 2 3 4 90\nCONTEXT 11 12\nLINK " +
         r.session + " 1 100 0 0 0\nCOLOR raw " + bytes +
         "\nMASK depth 0\nDEPTH raw " + bytes + "\nRUN " + r.run + "\nEND " +
         id + "\n";
}
static std::filesystem::path create(const std::filesystem::path &dir,
                                    const Request &r, uint64_t now) {
  uint32_t stride = (4096 + r.width * r.height * 8 + 63) & ~63u;
  std::vector<uint8_t> bytes(4096 + 3 * stride);
  std::memcpy(bytes.data(), "SBMCFRM1", 8);
  put(bytes, 8, uint32_t(1));
  put(bytes, 12, uint32_t(4096));
  put(bytes, 16, r.width);
  put(bytes, 20, r.height);
  put(bytes, 24, stride);
  put(bytes, 28, uint32_t(bytes.size()));
  std::memcpy(bytes.data() + 32, r.run.data(), 32);
  std::memcpy(bytes.data() + 64, r.session.data(), 32);
  put(bytes, 96, uint32_t(42));
  put(bytes, 256, uint32_t(1));
  put(bytes, 264, now);
  auto path = dir / ("eve-frames-" + r.run + ".bin");
  std::ofstream f(path, std::ios::binary);
  f.write(reinterpret_cast<char *>(bytes.data()), bytes.size());
  assert(f);
  return path;
}
static void all_half_depths(const std::filesystem::path &dir) {
  auto now = now_ms();
  Request r{std::string(32, '5'), std::string(32, 'b'), 256, 256,
            now / 1000, true};
  auto path = create(dir, r, now);
  Producer producer;
  assert(producer.open(dir.wstring(), r, 123, now));
  constexpr size_t count = 65536;
  std::vector<uint8_t> color(count * 4, 32);
  std::vector<uint16_t> depth(count * 4, 0x7c00);
  std::vector<uint8_t> result(count * 8);
  for (uint64_t seq = 1; seq <= 4; seq++) {
    // Flipping every sign bit contradicts the previous coverage of reused
    // slots; either plane may arrive last.
    for (size_t i = 0; i < count; i++)
      depth[4 * i] = uint16_t(seq % 2 ? i : i ^ 0x8000);
    assert(producer.begin(metadata(r, seq), seq));
    for (auto kind : seq % 2 ? std::array<int, 2>{0, 2} : std::array<int, 2>{2, 0})
      assert(producer.plane(kind, kind ? static_cast<const void *>(depth.data()) : color.data(),
                            count * (kind == 2 ? 8 : 4), r.width, r.height));
    assert(read<uint64_t>(path, 216) == seq);
    auto slot = 4096 + (read<uint32_t>(path, 128) & 3u) * read<uint32_t>(path, 24);
    std::ifstream file(path, std::ios::binary);
    file.seekg(slot + 4096);
    file.read(reinterpret_cast<char *>(result.data()), result.size());
    assert(file);
    for (size_t i = 0; i < count; i++) {
      // Independent numerical reference: positive finite binary16 values survive;
      // negative values, signed zero, infinities and every NaN become zero.
      unsigned bits = depth[4 * i], exponent = (bits >> 10) & 31, fraction = bits & 1023;
      float expected = 0;
      if (!(bits & 0x8000) && exponent != 31)
        expected = float(exponent ? std::ldexp(1.0 + fraction / 1024.0,
                                               int(exponent) - 15)
                                  : std::ldexp(double(fraction), -24));
      assert(std::memcmp(result.data() + count * 4 + i * 4, &expected, 4) == 0);
      // 0x7bff (65504) is the capture clear value, not EVE.
      assert(result[i * 4 + 3] == (expected > 0 && bits < 0x7bff ? 255 : 0));
      assert(result[i * 4] == 32 && result[i * 4 + 1] == 32 &&
             result[i * 4 + 2] == 32);
    }
  }
  producer.stop();
}
int main() {
  auto text = "SBMC_CAPTURE_SESSION 2\nRUN " + std::string(32, 'a') +
              "\nSESSION " + std::string(32, 'b') +
              "\nTIME 1234\nSIZE 2 1\nSTATE active\nEND\n";
  auto parsed = parse_request(text);
  assert(parsed && parsed->width == 2 && parsed->height == 1 && parsed->active);
  for (auto invalid :
       {text + "x", std::string("bad"), text.substr(0, text.size() - 1)})
    assert(!parse_request(invalid));
  for (auto size : {"0 1", "3841 1", "2 2161", "-2 1", "2 1 2"}) {
    auto invalid = text;
    invalid.replace(invalid.find("SIZE 2 1"), 8, std::string("SIZE ") + size);
    assert(!parse_request(invalid));
  }
  auto now = now_ms();
  Request r{std::string(32, 'a'), std::string(32, 'b'), 2, 1, now / 1000, true};
  auto dir = std::filesystem::temp_directory_path() /
             ("stellar-frame-contract-" + std::to_string(now));
  assert(std::filesystem::create_directory(dir));
  auto path = create(dir, r, now);
  Producer producer;
  assert(producer.open(dir.wstring(), r, 123, now));
  assert(producer.ready());
  assert(producer.open(dir.wstring(), r, 123, now));
  Producer second;
  assert(!second.open(dir.wstring(), r, 124, now));
  auto meta = metadata(r, 1);
  assert(producer.begin(meta, 1));
  assert(producer.begin(meta, 1));
  const uint8_t color[] = {10, 20, 30, 40, 50, 60, 70, 80};
  const uint16_t depth[] = {0x3c00, 0, 0, 0, 0x7e00, 0, 0, 0};
  const uint16_t background[] = {0x7bff, 0, 0, 0, 0x7bfe, 0, 0, 0};
  assert(producer.plane(2, depth, sizeof depth, 2, 1));
  assert(read<uint64_t>(path, 216) == 0);
  assert(producer.plane(0, color, sizeof color, 2, 1));
  assert(read<uint32_t>(path, 128) == 5 && read<uint64_t>(path, 216) == 1);
  auto stride = read<uint32_t>(path, 24);
  auto slot = 4096 + stride;
  assert(read<uint64_t>(path, slot) == 1 &&
         read<uint32_t>(path, slot + 16) == meta.size());
  // Color alpha is ignored; coverage belongs only to the depth plane.
  assert(read<uint32_t>(path, slot + 4096) == 0xff0a141e &&
         read<uint32_t>(path, slot + 4100) == 0x00323c46);
  assert(read<float>(path, slot + 4104) == 1 &&
         read<float>(path, slot + 4108) == 0);
  // The consumer holds slot 1 while returning its old front 2 as the middle.
  // Subsequent writes must cycle only through 0/2, without changing that front.
  write(path, 128, uint32_t(2));
  for (uint64_t n = 2; n <= 6; n++) {
    assert(producer.begin(metadata(r, n), n));
    // Even generations carry the cleared background and the nearest lower depth.
    const void *planes[] = {color, nullptr, n % 2 ? depth : background};
    for (auto kind : n % 2 ? std::array<int, 2>{0, 2} : std::array<int, 2>{2, 0})
      assert(producer.plane(kind, planes[kind], kind == 2 ? sizeof depth : sizeof color,
                            2, 1));
    assert(read<uint64_t>(path, slot) == 1 &&
           read<uint32_t>(path, slot + 4096) == 0xff0a141e);
    auto current = 4096 + (read<uint32_t>(path, 128) & 3u) * stride;
    assert(read<uint64_t>(path, current) == n);
    if (n % 2)
      assert(read<uint32_t>(path, current + 4096) == 0xff0a141e &&
             read<uint32_t>(path, current + 4100) == 0x00323c46 &&
             read<float>(path, current + 4108) == 0);
    else
      assert(read<uint32_t>(path, current + 4096) == 0x000a141e &&
             read<float>(path, current + 4104) == 65504 &&
             read<uint32_t>(path, current + 4100) == 0xff323c46 &&
             read<float>(path, current + 4108) == 65472);
  }
  assert(!producer.plane(0, color, sizeof color, 2, 1));
  assert(read<uint32_t>(path, 196) == 3);
  assert(read<uint64_t>(path, 216) == 6);
  producer.stop();
  assert(!producer.ready());
  // Failed is not an ownership-release acknowledgement. stop must publish
  // stopped while retaining the failure code, allowing the consumer to retire
  // this mapping and create a fresh RUN.
  assert(read<uint32_t>(path, 196) == 2 && read<uint32_t>(path, 200) == 2 &&
         !producer.open(dir.wstring(), r, 123, now));
  r.run.assign(32, 'c');
  path = create(dir, r, now);
  assert(producer.open(dir.wstring(), r, 123, now));
  assert(producer.begin(metadata(r, 1), 1));
  assert(!producer.plane(0, color, sizeof color - 1, 2, 1));
  assert(read<uint64_t>(path, 216) == 0);
  assert(read<uint32_t>(path, 200) == 2);
  producer.stop();
  r.run.assign(32, 'd');
  path = create(dir, r, now);
  assert(producer.open(dir.wstring(), r, 123, now));
  auto wrong = metadata(r, 1);
  wrong.replace(wrong.find("CONTEXT 11 12"), 13, "CONTEXT 0 12");
  assert(!producer.begin(wrong, 1));
  assert(read<uint32_t>(path, 196) == 3);
  producer.stop();
  r.run.assign(32, 'e');
  path = create(dir, r, now);
  assert(producer.open(dir.wstring(), r, 123, now));
  producer.heartbeat(now + 3001);
  assert(!producer.ready());
  assert(read<uint32_t>(path, 196) == 2);
  r.run.assign(32, 'f');
  path = create(dir, r, now);
  assert(producer.open(dir.wstring(), r, 123, now));
  assert(producer.begin(metadata(r, 1), 1));
  assert(producer.plane(0, color, sizeof color, 2, 1));
  write(path, 256, uint32_t(2));
  assert(!producer.plane(2, depth, sizeof depth, 2, 1));
  assert(read<uint64_t>(path, 216) == 0 && read<uint32_t>(path, 196) == 2);
  // A malformed new mapping is rejected before claiming it or changing its
  // control word.
  r.run.assign(32, '1');
  path = create(dir, r, now);
  write(path, 128, uint32_t(7));
  assert(!producer.open(dir.wstring(), r, 123, now));
  assert(read<uint32_t>(path, 192) == 0);
  r.run.assign(32, '2');
  path = create(dir, r, now);
  write(path, 132, uint32_t(1));
  assert(!producer.open(dir.wstring(), r, 123, now));
  assert(read<uint32_t>(path, 192) == 0);
  r.run.assign(32, '3');
  path = create(dir, r, now);
  std::filesystem::resize_file(path, 4096);
  assert(!producer.open(dir.wstring(), r, 123, now));
  r.run.assign(32, '4');
  now = now_ms();
  r.time = now / 1000;
  path = create(dir, r, now);
  assert(producer.open(dir.wstring(), r, 123, now));
  std::this_thread::sleep_for(std::chrono::milliseconds(3050));
  write(path, 264,
        now + 1); // Still fresh under this deliberately unchanged wall clock.
  producer.heartbeat(now + 1);
  assert(!producer.ready());
  assert(read<uint32_t>(path, 196) == 2);
  assert(!producer.open(dir.wstring(), r, 123, now + 1));
  producer.stop();
  // A retired mask plane is a contract violation, never a partial generation.
  r.run.assign(32, '6');
  path = create(dir, r, now_ms());
  assert(producer.open(dir.wstring(), r, 123, now_ms()));
  assert(producer.begin(metadata(r, 1), 1));
  assert(!producer.plane(1, color, sizeof color, 2, 1));
  assert(read<uint32_t>(path, 196) == 3 && read<uint32_t>(path, 200) == 2 &&
         read<uint64_t>(path, 216) == 0);
  producer.stop();
  all_half_depths(dir);
  std::filesystem::remove_all(dir);
  std::cout << "PASS raw frame contract: request bounds, exclusive claim, "
               "both plane orders, 65536 half depths with depth-derived coverage, "
               "retired mask rejection, duplicate failure and lease\n";
}
