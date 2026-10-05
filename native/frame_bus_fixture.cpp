// Standalone transport fixture; no Unreal objects, injection or game input.
#include "frame_bus.hpp"
#include <chrono>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

static uint64_t now_ms() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::system_clock::now().time_since_epoch())
      .count();
}
static void require(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
static std::string descriptor(const stellar_frame::Request &request,
                              uint64_t counter) {
  auto stamp = std::to_string(now_ms() / 1000);
  auto id = stamp + "-" + std::to_string(counter);
  auto bytes = std::to_string(uint64_t(request.width) * request.height * 4);
  return "SBMC_FRAME 5 " + id + " " + stamp + "\nSIZE " +
         std::to_string(request.width) + " " + std::to_string(request.height) +
         "\nCAMERA 0 -300 200 0 0 0 75\nPAWN 0 0 90 90\nCONTEXT 11 12\nLINK " +
         request.session + " 1 100 0 0 0\nCOLOR raw " + bytes +
         "\nMASK none 0\nDEPTH raw " + bytes + "\nRUN " + request.run +
         "\nEND " + id + "\n";
}
int main(int argc, char **argv) {
  try {
    require(argc == 7,
            "Expected directory RUN SESSION width height frameCount");
    auto width = std::stoul(argv[4]), height = std::stoul(argv[5]);
    auto frames = std::stoul(argv[6]);
    require(width > 0 && width <= 3840 && height > 0 && height <= 2160 &&
                frames > 0 && frames <= 4096,
            "Fixture dimensions/count exceed limits");
    stellar_frame::Request request{argv[2],
                                   argv[3],
                                   static_cast<uint32_t>(width),
                                   static_cast<uint32_t>(height),
                                   now_ms() / 1000,
                                   true};
    std::filesystem::path directory(argv[1]);
    stellar_frame::Producer producer;
    require(producer.open(directory.wstring(), request, 12345, now_ms()),
            "Cannot claim reader-created mapping");
    auto pixels = width * height;
    std::vector<uint8_t> color(pixels * 4), mask(pixels * 4);
    std::vector<uint16_t> depth(pixels * 4);
    for (size_t i = 0; i < pixels; ++i) {
      color[i * 4] = 3;
      color[i * 4 + 1] = 2;
      mask[i * 4 + 3] = 127;
      depth[i * 4] = 0x3c00;
    }
    for (uint64_t counter = 1; counter <= frames; ++counter) {
      producer.heartbeat(now_ms());
      require(producer.ready(), "Consumer stopped during publication");
      for (size_t i = 0; i < pixels; ++i)
        color[i * 4 + 2] = static_cast<uint8_t>(counter);
      require(producer.begin(descriptor(request, counter), counter),
              "Frame metadata rejected");
      const int forward[] = {0, 1, 2}, reverse[] = {2, 1, 0};
      const auto *order = counter % 2 ? forward : reverse;
      for (int i = 0; i < 3; ++i) {
        int kind = order[i];
        const void *data = kind == 0 ? static_cast<const void *>(color.data())
                           : kind == 1
                               ? static_cast<const void *>(mask.data())
                               : static_cast<const void *>(depth.data());
        require(producer.plane(kind, data, pixels * (kind == 2 ? 8 : 4),
                               request.width, request.height),
                "Raw plane rejected");
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (producer.ready()) {
      require(std::chrono::steady_clock::now() < deadline,
              "Consumer stop deadline");
      producer.heartbeat(now_ms());
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    std::cout << "PASS production Producer: " << frames
              << " raw generations, reader stop, mapping released\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "FAIL frame transport fixture: " << error.what() << '\n';
    return 1;
  }
}
