#ifdef NDEBUG
#undef NDEBUG
#endif
#include "pose_stream.hpp"
#include "shared_memory.hpp"
#include <cassert>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>

using namespace stellar_pose;
static const std::string Run = "33333333333333333333333333333333";

// Fake engine memory with the pinned build's offsets: a GUObjectArray with one
// chunk of FUObjectItems and skeletal mesh components that point into it.
struct Fake {
  alignas(16) uint8_t objects[0x40]{};
  std::vector<uint8_t> items = std::vector<uint8_t>(24 * 8);
  uint8_t *chunks[1];
  struct Object {
    alignas(16) uint8_t bytes[0x800]{};
    alignas(16) float space[2][3 * 12]{}; // Two double-buffered 3-bone arrays.
    float morphs[2]{};
  };
  Object a, b, controller;
  Fake() {
    chunks[0] = items.data();
    auto table = reinterpret_cast<uintptr_t>(chunks);
    std::memcpy(objects + 0x10, &table, 8);
    int32_t count = 8;
    std::memcpy(objects + 0x24, &count, 4);
    setup(a, 3);
    setup(b, 5);
    setup(controller, 6);
    float rotation[3] = {-10, 135, 0};
    std::memcpy(controller.bytes + 0x318, rotation, sizeof rotation);
  }
  void setup(Object &o, int32_t index) {
    auto self = reinterpret_cast<uintptr_t>(o.bytes);
    std::memcpy(items.data() + 24 * index, &self, 8);
    std::memcpy(o.bytes + 0x0c, &index, 4);
    uint64_t cls = 0x1000, mesh = 0x2000;
    std::memcpy(o.bytes + 0x10, &cls, 8);
    std::memcpy(o.bytes + 0x568, &mesh, 8);
    float world[12] = {0, 0, 0.5f, 0.75f, 100, 200, 300, 9, 1, 1, 1, 9};
    std::memcpy(o.bytes + 0x1f0, world, sizeof world);
    for (int buffer = 0; buffer < 2; buffer++) {
      for (int k = 0; k < 3; k++) {
        float *t = o.space[buffer] + 12 * k;
        float values[12] = {0, 0, 0, 1, float(k), float(buffer), 0, 7, 1, 2, 3, 7};
        std::memcpy(t, values, sizeof values);
      }
      auto data = reinterpret_cast<uintptr_t>(o.space[buffer]);
      int32_t num = 3, max = 3;
      std::memcpy(o.bytes + 0x5a8 + 16 * buffer, &data, 8);
      std::memcpy(o.bytes + 0x5a8 + 16 * buffer + 8, &num, 4);
      std::memcpy(o.bytes + 0x5a8 + 16 * buffer + 12, &max, 4);
    }
    o.morphs[0] = 0.25f;
    o.morphs[1] = 1;
    auto morphs = reinterpret_cast<uintptr_t>(o.morphs);
    int32_t two = 2;
    std::memcpy(o.bytes + 0x738, &morphs, 8);
    std::memcpy(o.bytes + 0x740, &two, 4);
  }
  Engine engine() { return Engine{reinterpret_cast<uintptr_t>(objects)}; }
  static Component component(Object &o) {
    return Component{reinterpret_cast<uint64_t>(o.bytes), 0x1000, 0x2000, 3, 2};
  }
};

static std::string hex(uint64_t value) {
  char text[17];
  std::snprintf(text, sizeof text, "%016llx", static_cast<unsigned long long>(value));
  return text;
}
static std::string components(uint64_t generation, uint64_t a, uint64_t b, uint64_t controller = 0) {
  return "SBMC_POSE_COMPONENTS 1\nRUN " + Run + "\nGENERATION " +
         std::to_string(generation) + "\nSESSION none\nANCHOR 1 2 3 100\n" +
         (controller ? "CONTROLLER " + hex(controller) + " 0000000000001000 00000318\n" : std::string("CONTROLLER none\n")) +
         "COUNT 2\n"
         "COMPONENT 1 " + hex(a) + " 0000000000001000 0000000000002000 3 2 Body Mesh -\n"
         "BONES 1 Root Hip Head\nMORPHS 1 Smile Blink\n"
         "COMPONENT 2 " + hex(b) + " 0000000000001000 0000000000002000 0 2 Face FaceMesh Body\n"
         "MORPHS 2 Smile Blink\nEND " + std::to_string(generation) + "\n";
}
static std::string request(uint64_t time, const char *state = "active") {
  return "SBMC_POSE_REQUEST 1\nRUN " + Run + "\nTIME " + std::to_string(time) +
         "\nSTATE " + state + "\nEND\n";
}

int main() {
  uint64_t now = stellar_shared::wall_now();
  assert(parse_request(request(now)) && parse_request(request(now))->active);
  assert(!parse_request(request(now, "paused")));
  assert(!parse_request("SBMC_POSE_REQUEST 1\nRUN abc\nTIME 1\nSTATE active\nEND\n"));

  {
    std::ifstream fixture("protocol/fixtures/pose-components.txt", std::ios::binary);
    std::string shared((std::istreambuf_iterator<char>(fixture)), {});
    auto parsed = parse_components(shared);
    assert(parsed && parsed->generation == 1 && parsed->components.size() == 2 &&
           parsed->components[0].object == 0x7f0000001000 &&
           parsed->components[0].bones == 3 && parsed->components[1].morphs == 2 &&
           parsed->floats == 40 + 12 && "The Lua fixture parses natively");
  }
  auto table = parse_components(components(4, 0x10, 0x20));
  assert(table && table->generation == 4 && table->components.size() == 2);
  assert(table->floats == (10 + 30 + 2) + (10 + 2));
  assert(table->components[1].bones == 0 && table->components[1].morphs == 2);
  std::string good = components(4, 0x10, 0x20);
  auto broken = [&](std::string from, std::string to) {
    auto text = good;
    auto at = text.find(from);
    assert(at != std::string::npos);
    text.replace(at, from.size(), to);
    return !parse_components(text);
  };
  assert(broken("BONES 1 Root Hip Head", "BONES 1 Root Hip"));
  assert(broken("COUNT 2", "COUNT 3"));
  assert(broken("END 4", "END 5"));
  assert(broken("0000000000001000 0000000000002000 3", "0000000000001001 0000000000002000 3"));
  assert(broken("ANCHOR 1 2 3 100", "ANCHOR 1 2"));
  assert(broken("CONTROLLER none", "CONTROLLER"));
  {
    auto with = parse_components(components(4, 0x10, 0x20, 0x30));
    assert(with && with->controller && with->controller->object == 0x30 && with->controller->offset == 0x318);
    auto text = components(4, 0x10, 0x20, 0x30);
    for (auto bad : {"00000319", "00004000", "00000000", "318"}) {
      auto copy = text;
      copy.replace(copy.find("00000318"), 8, bad);
      assert(!parse_components(copy) && "Controller offsets are aligned, bounded and eight hex digits");
    }
  }
  assert(broken("MORPHS 2 Smile Blink\n", ""));
  assert(broken("3 2 Body", "300 2 Body"));
  // Payload must fit one slot.
  std::string huge = "SBMC_POSE_COMPONENTS 1\nRUN " + Run +
                     "\nGENERATION 1\nSESSION none\nANCHOR none\nCOUNT 16\n";
  for (int i = 1; i <= 16; i++) {
    huge += "COMPONENT " + std::to_string(i) + " 0000000000000010 0000000000001000 0000000000002000 256 0 C M -\nBONES " + std::to_string(i);
    for (int k = 0; k < 256; k++) huge += " b";
    huge += "\n";
  }
  assert(!parse_components(huge + "END 1\n"));
  auto other = components(9, 0x10, 0x20);
  other.replace(other.find(Run), 32, std::string(32, '4'));
  assert(select_components(components(4, 0x10, 0x20), components(5, 0x10, 0x20), Run)->generation == 5);
  assert(select_components(components(4, 0x10, 0x20), other, Run)->generation == 4);
  assert(select_components("partial", components(6, 0x10, 0x20).substr(0, 40), Run) == std::nullopt);

  Fake fake;
  auto engine = fake.engine();
  auto body = Fake::component(fake.a);
  std::vector<float> out(10 + 30 + 2, -1);
  assert(read_component(engine, body, out.data()) == (WorldValid | BonesValid | MorphsValid));
  assert(out[2] == 0.5f && out[3] == 0.75f && out[4] == 100 && out[6] == 300 && out[7] == 1 && out[9] == 1);
  assert(out[10 + 10 * 2 + 4] == 2 && out[10 + 10 * 2 + 5] == 0 && out[10 + 7] == 1 && out[10 + 9] == 3);
  assert(out[40] == 0.25f && out[41] == 1);
  int32_t second = 1;
  std::memcpy(fake.a.bytes + 0x674, &second, 4);
  read_component(engine, body, out.data());
  assert(out[10 + 5] == 1 && "The read buffer index selects the stable array");
  int32_t bad = 2;
  std::memcpy(fake.a.bytes + 0x674, &bad, 4);
  assert(read_component(engine, body, out.data()) == (WorldValid | MorphsValid));
  std::memcpy(fake.a.bytes + 0x674, &second, 4);
  int32_t fewer = 2;
  std::memcpy(fake.a.bytes + 0x5a8 + 16 + 8, &fewer, 4);
  assert(read_component(engine, body, out.data()) == (WorldValid | MorphsValid));
  int32_t one = 1;
  std::memcpy(fake.a.bytes + 0x740, &one, 4);
  assert(read_component(engine, body, out.data()) == WorldValid);
  int32_t pending = 1 << 29;
  std::memcpy(fake.items.data() + 24 * 3 + 8, &pending, 4);
  assert(read_component(engine, body, out.data()) == 0 && out[4] == 0 && "Pending kill is never read");
  int32_t clear = 0;
  std::memcpy(fake.items.data() + 24 * 3 + 8, &clear, 4);
  assert(read_component(engine, body, out.data()) == WorldValid);
  auto moved = body;
  moved.mesh = 0x3000;
  assert(read_component(engine, moved, out.data()) == 0 && "A swapped mesh invalidates the table");
  moved = body;
  moved.cls = 0x1008;
  assert(read_component(engine, moved, out.data()) == 0);
  uint64_t stranger = 0x1234;
  std::memcpy(fake.items.data() + 24 * 3, &stranger, 8);
  assert(read_component(engine, body, out.data()) == 0 && "The index must still name this object");
  int32_t outside = 8;
  std::memcpy(fake.b.bytes + 0x0c, &outside, 4);
  assert(read_component(engine, Fake::component(fake.b), out.data()) == 0);
  assert(read_component(Engine{}, Fake::component(fake.b), out.data()) == 0);
  {
    float rotation[3] = {9, 9, 9};
    Controller c{reinterpret_cast<uint64_t>(fake.controller.bytes), 0x1000, 0x318};
    assert(read_controller(engine, c, rotation) && rotation[0] == -10 && rotation[1] == 135 && rotation[2] == 0);
    c.cls = 0x1008;
    assert(!read_controller(engine, c, rotation) && rotation[1] == 0 && "Another class is never read");
  }

  // End to end on a real mapping: worker installs the table, game thread publishes.
  Fake live;
  initialize(reinterpret_cast<uintptr_t>(live.objects));
  auto directory = std::filesystem::temp_directory_path() / ("stellar-pose-" + std::to_string(now));
  std::filesystem::create_directories(directory);
  auto path = directory / ("eve-pose-" + Run + ".bin");
  {
    std::vector<uint8_t> bytes(TotalBytes);
    std::memcpy(bytes.data(), "SBMCPOS1", 8);
    stellar_shared::put(bytes.data(), 8, uint32_t(1));
    stellar_shared::put(bytes.data(), 12, Header);
    stellar_shared::put(bytes.data(), 16, SlotBytes);
    stellar_shared::put(bytes.data(), 20, Capacity);
    stellar_shared::put(bytes.data(), 28, TotalBytes);
    std::memcpy(bytes.data() + 32, Run.data(), 32);
    stellar_shared::put(bytes.data(), 96, uint32_t(4242));
    stellar_shared::put(bytes.data(), 256, uint32_t(1));
    stellar_shared::put(bytes.data(), 264, now);
    std::ofstream(path, std::ios::binary).write(reinterpret_cast<char *>(bytes.data()), bytes.size());
  }
  auto slots = components(7, reinterpret_cast<uint64_t>(live.a.bytes), reinterpret_cast<uint64_t>(live.b.bytes),
                          reinterpret_cast<uint64_t>(live.controller.bytes));
  on_gameplay_poll(); // Nothing installed yet.
  poll_files(directory.wstring(), request(now), slots, "", now);
  on_gameplay_poll();
  {
    stellar_shared::Mapping view;
    assert(view.open(path, TotalBytes));
    auto *base = view.base;
    assert(stellar_shared::load32(base, 196) == 1 && stellar_shared::load32(base, 192) != 0);
    auto middle = stellar_shared::load32(base, 128);
    assert(middle == (1 | 4) && stellar_shared::load64(base, 216) == 1);
    auto *slot = base + Header + SlotBytes * 1;
    assert(stellar_shared::plain<uint64_t>(slot, 0) == 1 && stellar_shared::plain<uint64_t>(slot, 16) == 7);
    assert(stellar_shared::plain<uint32_t>(slot, 28) == 2 && stellar_shared::plain<uint32_t>(slot, 32) == 54);
    assert(stellar_shared::plain<uint32_t>(slot, 64) == 7 && stellar_shared::plain<uint32_t>(slot, 68) == (WorldValid | MorphsValid));
    assert(stellar_shared::plain<float>(slot, 128 + 4 * 4) == 100);
    assert(stellar_shared::plain<uint32_t>(slot, 36) == 1 && stellar_shared::plain<float>(slot, 44) == 135 &&
           "The controller rotation travels in the slot header");
    assert(stellar_shared::plain<float>(slot, 128 + 4 * (42 + 10)) == 0.25f && "Follower morphs follow its world transform");
    on_gameplay_poll();
    assert(stellar_shared::load64(base, 216) == 2 && stellar_shared::load32(base, 128) == (0 | 4));
#ifdef _WIN32
    // An object freed after Lua published it may fault; the read is contained.
    poll_files(directory.wstring(), request(now),
               components(8, reinterpret_cast<uint64_t>(live.a.bytes), 0x1000), "", now);
    on_gameplay_poll();
    auto *third = base + Header + SlotBytes * 1;
    assert(stellar_shared::load64(base, 216) == 3 && stellar_shared::plain<uint64_t>(third, 16) == 8);
    assert(stellar_shared::plain<uint32_t>(third, 64) == 7 && stellar_shared::plain<uint32_t>(third, 68) == 0);
#endif
    // A stopped request releases the run (state 2), after which nothing is published.
    auto published = stellar_shared::load64(base, 216);
    poll_files(directory.wstring(), request(now, "stopped"), slots, "", now);
    assert(stellar_shared::load32(base, 196) == 2);
    on_gameplay_poll();
    assert(stellar_shared::load64(base, 216) == published);
    // A stale request never reopens a mapping.
    poll_files(directory.wstring(), request(now - 10000), slots, "", now);
    assert(stellar_shared::load32(base, 196) == 2);
  }
  std::filesystem::remove_all(directory);
  std::cout << "Pose stream tests passed: request/table grammar and bounds, live-object validation, double-buffered reads, mapping publication and release\n";
}
