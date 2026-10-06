// Standalone pose stream fixture: fake engine memory in this process, no Unreal
// objects, injection or game input. Publishes into a Minecraft-created mapping.
#include "pose_stream.hpp"
#include "shared_memory.hpp"
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
struct Object {
  alignas(16) uint8_t bytes[0x800]{};
  alignas(16) float space[3 * 12]{};
  float morphs[2]{};
};
alignas(16) uint8_t objects[0x40]{};
std::vector<uint8_t> items(24 * 8);
uint8_t *chunks[1];
Object body, face;

template <class T> void put(uint8_t *at, T value) { std::memcpy(at, &value, sizeof value); }
void live(Object &o, int32_t index) {
  auto self = reinterpret_cast<uintptr_t>(o.bytes);
  std::memcpy(items.data() + 24 * index, &self, 8);
  put(o.bytes + 0x0c, index);
  put(o.bytes + 0x10, uint64_t(0x1000));
  put(o.bytes + 0x568, uint64_t(0x2000));
  float world[12] = {0, 0, 0, 1, 100, 200, 300, 0, 1, 1, 1, 0};
  std::memcpy(o.bytes + 0x1f0, world, sizeof world);
  for (int k = 0; k < 3; k++) {
    float bone[12] = {0, 0, 0, 1, 0, 0, float(k), 0, 1, 1, 1, 0};
    std::memcpy(o.space + 12 * k, bone, sizeof bone);
  }
  put(o.bytes + 0x5a8, reinterpret_cast<uintptr_t>(o.space));
  put(o.bytes + 0x5b0, int32_t(3));
  put(o.bytes + 0x738, reinterpret_cast<uintptr_t>(o.morphs));
  put(o.bytes + 0x740, int32_t(2));
}
std::string read(const std::filesystem::path &path) {
  std::ifstream f(path, std::ios::binary);
  std::stringstream s;
  s << f.rdbuf();
  return s.str();
}
std::string hex(const void *pointer) {
  char text[17];
  std::snprintf(text, sizeof text, "%016llx",
                static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(pointer)));
  return text;
}
void require(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 3, "Expected directory maximumPoses");
    std::filesystem::path directory(argv[1]);
    auto poses = std::stoul(argv[2]);
    require(poses > 0 && poses <= 10000, "Pose count exceeds limits");
    chunks[0] = items.data();
    put(objects + 0x10, reinterpret_cast<uintptr_t>(chunks));
    put(objects + 0x24, int32_t(8));
    live(body, 3);
    live(face, 5);
    auto request = stellar_pose::parse_request(read(directory / "eve-pose-request.txt"));
    require(request && request->active, "No active pose request");
    // What Lua would publish for these two components.
    auto table = "SBMC_POSE_COMPONENTS 1\nRUN " + request->run +
                 "\nGENERATION 1\nSESSION none\nANCHOR none\nCOUNT 2\nCOMPONENT 1 " + hex(body.bytes) +
                 " 0000000000001000 0000000000002000 3 0 CharacterMesh0 CH_P_EVE_52_TypeB -\n"
                 "BONES 1 Root Bip001 Bip001-Head\nCOMPONENT 2 " + hex(face.bytes) +
                 " 0000000000001000 0000000000002000 0 2 Mesh_Face CH_P_EVE_Face_Jali3 CharacterMesh0\n"
                 "MORPHS 2 jawOpen blink\nEND 1\n";
    std::ofstream(directory / "eve-pose-components.1", std::ios::binary) << table;
    stellar_pose::initialize(reinterpret_cast<uintptr_t>(objects));
    // Publish until Minecraft stops its request; poll_files then releases the run.
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    for (unsigned long published = 0;;) {
      require(std::chrono::steady_clock::now() < deadline && published <= poses, "Fixture deadline");
      auto text = read(directory / "eve-pose-request.txt");
      stellar_pose::poll_files(directory.wstring(), text, "", table, stellar_shared::wall_now());
      auto current = stellar_pose::parse_request(text);
      if (current && !current->active)
        break;
      // The pose changes every publication: bone 1 moves and the face blinks.
      body.space[12 + 4] = float(published + 1);
      face.morphs[1] = float((published + 1) % 2);
      stellar_pose::on_gameplay_poll();
      auto mapping = directory / ("eve-pose-" + request->run + ".bin");
      stellar_shared::Mapping view;
      if (view.open(mapping, stellar_pose::TotalBytes))
        published = stellar_shared::load64(view.base, 216);
      std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    std::cout << "PASS pose fixture released its run\n";
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 1;
  }
}
