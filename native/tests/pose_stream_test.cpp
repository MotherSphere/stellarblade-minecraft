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

// The pawn's virtual table: AddControllerPitchInput at 0x798 and
// AddControllerYawInput at 0x7a0 record the values they receive.
static std::vector<std::pair<char, float>> inputs;
static void add_pitch(void *, float value) { inputs.push_back({'p', value}); }
static void add_yaw(void *, float value) { inputs.push_back({'y', value}); }
static uintptr_t pawn_table[0x7a0 / 8 + 1];

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
  Object a, b, controller, pawn;
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
    setup(pawn, 7);
    pawn_table[0x798 / 8] = reinterpret_cast<uintptr_t>(&add_pitch);
    pawn_table[0x7a0 / 8] = reinterpret_cast<uintptr_t>(&add_yaw);
    auto vtable = reinterpret_cast<uintptr_t>(pawn_table);
    std::memcpy(pawn.bytes, &vtable, 8);
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
static std::string components(uint64_t generation, uint64_t a, uint64_t b, uint64_t controller = 0,
                              uint64_t pawn = 0x40, std::string parameters = "PARAMETERS 0\nEFFECTS none\n") {
  return "SBMC_POSE_COMPONENTS 1\nRUN " + Run + "\nGENERATION " +
         std::to_string(generation) + "\nSESSION none\nANCHOR 1 2 3 100\n" +
         (controller ? "CONTROLLER " + hex(controller) + " 0000000000001000 00000318 " + hex(pawn) +
                           " 0000000000001000 2.5 -2.5\n"
                     : std::string("CONTROLLER none\n")) +
         "COUNT 2\n"
         "COMPONENT 1 " + hex(a) + " 0000000000001000 0000000000002000 3 2 Body Mesh -\n"
         "BONES 1 Root Hip Head\nMORPHS 1 Smile Blink\n"
         "COMPONENT 2 " + hex(b) + " 0000000000001000 0000000000002000 0 2 Face FaceMesh Body\n"
         "MORPHS 2 Smile Blink\n" + parameters + "END " + std::to_string(generation) + "\n";
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
  assert(broken("PARAMETERS 0\n", "") && "The parameter count is required");
  assert(broken("EFFECTS none\n", "") && "The effects line is required");
  {
    // Effects: offsets, mask, both classes, then the assets Minecraft draws; records follow the parameters.
    std::string fx = "PARAMETERS 0\nEFFECTS 00000300 00000200 00000208 00000088 10 0000000000001000 0000000000001008 2\n"
                     "EFFECT 1 0000000000abc000 NS_A_ParTrail_SwordDefaultAtk_01\nEFFECT 2 0000000000abd000 NS_A_Weapon_Signal_Sword\n";
    auto with = parse_components(components(4, 0x10, 0x20, 0, 0x40, fx));
    assert(with && with->effects && with->effects->children == 0x300 && with->effects->active_mask == 0x10 &&
           with->effects->assets.size() == 2 && with->floats == (10 + 30 + 2) + (10 + 2) + MaxEffects * EffectFloats);
    for (auto bad : {std::make_pair(" 00000300 ", " 00000304 "), std::make_pair(" 10 ", " 00 "), std::make_pair("EFFECT 2 ", "EFFECT 3 "),
                     std::make_pair(" 2\nEFFECT 1", " 3\nEFFECT 1")}) {
      auto text = components(4, 0x10, 0x20, 0, 0x40, fx);
      text.replace(text.find(bad.first), std::string(bad.first).size(), bad.second);
      assert(!parse_components(text) && "Effects carry aligned offsets, a mask and exactly their assets");
    }
  }
  {
    // Material parameters: a scalar and a vector of the face's dynamic material instance, after the components.
    std::string two = "PARAMETERS 2\nPARAMETER 1 2 Edge Opacity 0000000000000050 0000000000003000 00000100 1 77 1\n"
                      "PARAMETER 2 2 Edge Emissive_Color 0000000000000050 0000000000003000 00000110 0 78 4\nEFFECTS none\n";
    auto with = parse_components(components(4, 0x10, 0x20, 0, 0x40, two));
    assert(with && with->parameters.size() == 2 && with->parameters[0].offset == 0x100 && with->parameters[0].index == 1 &&
           with->parameters[0].name == 77 && with->parameters[1].floats == 4 && with->floats == (10 + 30 + 2) + (10 + 2) + 5);
    for (auto bad : {std::make_pair("PARAMETER 1 2 ", "PARAMETER 1 3 "), std::make_pair(" 00000100 ", " 00000104 "),
                     std::make_pair(" 77 1\n", " 77 2\n"), std::make_pair(" 77 1\n", " 0 1\n"),
                     std::make_pair(" 00000100 1 ", " 00000100 1024 "), std::make_pair("PARAMETERS 2", "PARAMETERS 3")}) {
      auto text = components(4, 0x10, 0x20, 0, 0x40, two);
      text.replace(text.find(bad.first), std::string(bad.first).size(), bad.second);
      assert(!parse_components(text) && "Parameters name a component, an aligned array, an element, a name and a width");
    }
  }
  {
    auto with = parse_components(components(4, 0x10, 0x20, 0x30));
    assert(with && with->controller && with->controller->object == 0x30 && with->controller->offset == 0x318 &&
           with->controller->pawn == 0x40 && with->controller->yaw_scale == 2.5f &&
           with->controller->pitch_scale == -2.5f);
    auto text = components(4, 0x10, 0x20, 0x30);
    for (auto bad : {" 2.5 -2.5", " 2.5 0", " nan -2.5", " 2.5 -2.5x", " 2.5 1e9", " 2.5"}) {
      auto copy = text;
      copy.replace(copy.find(" 2.5 -2.5"), 9, bad);
      assert((std::string(bad) == " 2.5 -2.5") == bool(parse_components(copy)) &&
             "Input scales are finite, nonzero and bounded");
    }
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
    Controller c;
    c.object = reinterpret_cast<uint64_t>(fake.controller.bytes);
    c.cls = 0x1000;
    c.offset = 0x318;
    assert(read_controller(engine, c, rotation) && rotation[0] == -10 && rotation[1] == 135 && rotation[2] == 0);
    c.cls = 0x1008;
    assert(!read_controller(engine, c, rotation) && rotation[1] == 0 && "Another class is never read");
    c.pawn = reinterpret_cast<uint64_t>(fake.pawn.bytes);
    c.pawn_cls = 0x1000;
    c.yaw_scale = 2.5f;
    c.pitch_scale = -2.5f;
    assert(turn_controller(engine, c, 5, -10) && inputs.size() == 2 && inputs[0] == std::make_pair('y', -4.0f) &&
           inputs[1] == std::make_pair('p', -2.0f) && "Degrees become input through the controller's scales");
    inputs.clear();
    assert(turn_controller(engine, c, 0, 0) && inputs.empty() && "No turn, no input");
    c.pawn_cls = 0x1008;
    assert(!turn_controller(engine, c, 5, 5) && inputs.empty() && "Another pawn class is never called");
    c.pawn_cls = 0x1000;
    std::memset(fake.pawn.bytes, 0, 8);
    assert(!turn_controller(engine, c, 5, 5) && inputs.empty() && "A pawn without a virtual table is never called");
  }
  {
    // A dynamic material instance (the still-live controller object stands in): scalars and vectors read by
    // element after checking the element's name.
    alignas(8) uint8_t scalars[3 * 36]{}, vectors[2 * 48]{};
    int32_t opacity = 77, glow = 78, other = 5;
    float half = 0.5f, color[4] = {1, 0.5f, 0.25f, 1};
    std::memcpy(scalars + 36, &opacity, 4);
    std::memcpy(scalars + 36 + 16, &half, 4);
    std::memcpy(vectors, &other, 4);
    std::memcpy(vectors + 48, &glow, 4);
    std::memcpy(vectors + 48 + 16, color, 16);
    auto put_array = [&](size_t at, void *data, int32_t count) {
      auto pointer = reinterpret_cast<uintptr_t>(data);
      std::memcpy(fake.controller.bytes + at, &pointer, 8);
      std::memcpy(fake.controller.bytes + at + 8, &count, 4);
    };
    put_array(0x100, scalars, 3);
    put_array(0x110, vectors, 2);
    Parameter q;
    q.object = reinterpret_cast<uint64_t>(fake.controller.bytes);
    q.cls = 0x1000;
    q.offset = 0x100;
    q.index = 1;
    q.name = 77;
    q.floats = 1;
    float value[4] = {9, 9, 9, 9};
    assert(read_parameter(engine, q, value) && value[0] == 0.5f);
    Parameter v = q;
    v.offset = 0x110;
    v.name = 78;
    v.floats = 4;
    assert(read_parameter(engine, v, value) && value[0] == 1 && value[2] == 0.25f && value[3] == 1);
    v.index = 0;
    assert(!read_parameter(engine, v, value) && value[0] == 0 && "Another name at the element is never read");
    q.index = 3;
    assert(!read_parameter(engine, q, value) && "An element past the array's end is never read");
    q.index = 1;
    q.cls = 0x1008;
    assert(!read_parameter(engine, q, value) && "Another class is never read");
    put_array(0x100, scalars, 0);
    put_array(0x110, vectors, 0);
  }
  {
    // An active Niagara component attached to a published component (the pawn and controller objects stand in).
    Table t;
    t.components.push_back(Component{reinterpret_cast<uint64_t>(fake.pawn.bytes), 0x1000, 0x2000, 0, 0});
    Effects fx;
    fx.children = 0x300;
    fx.asset = 0x240; // Clear of the world transform at 0x1f0.
    fx.templ = 0x248;
    fx.active_byte = 0x88;
    fx.active_mask = 0x10;
    fx.niagara = 0x1000;
    fx.particle = 0x1008;
    fx.assets = {0xabc000, 0xabd000};
    t.effects = fx;
    static uintptr_t children[1];
    children[0] = reinterpret_cast<uintptr_t>(fake.controller.bytes);
    auto list = reinterpret_cast<uintptr_t>(children);
    int32_t one = 1;
    std::memcpy(fake.pawn.bytes + 0x300, &list, 8);
    std::memcpy(fake.pawn.bytes + 0x308, &one, 4);
    uint64_t signal = 0xabd000;
    std::memcpy(fake.controller.bytes + 0x240, &signal, 8);
    fake.controller.bytes[0x88] = 0x10;
    std::vector<float> records(MaxEffects * EffectFloats, 7);
    assert(read_effects(engine, t, records.data()) == 1 && records[0] == 1 && records[5] == 100 && records[7] == 300 &&
           records[EffectFloats] == -1 && "The signal plays at its component's world transform");
    fake.controller.bytes[0x88] = 0;
    assert(read_effects(engine, t, records.data()) == 0 && records[0] == -1 && "An inactive component is no effect");
    fake.controller.bytes[0x88] = 0x10;
    uint64_t other = 0xabe000;
    std::memcpy(fake.controller.bytes + 0x240, &other, 8);
    assert(read_effects(engine, t, records.data()) == 0 && "An asset Minecraft does not draw is skipped");
    std::memset(fake.pawn.bytes + 0x300, 0, 16);
  }
  {
    // Minecraft's motion goes forward; 15 % of the remaining yaw gap closes per poll.
    Follow f;
    auto t = follow(f, 10, 100, 90);
    assert(t.engaged && std::fabs(t.yaw - 1.5f) < 1e-4f && t.pitch == 0 && "First poll: yaw gap only");
    t = follow(f, 4, 110, 100);
    assert(std::fabs(t.yaw - (10 + 1.5f)) < 1e-4f && "Then Minecraft's motion since the last poll");
    assert(std::fabs(t.pitch - -6) < 1e-4f && "Pitch gets Minecraft's motion alone");
    Follow wrapped;
    follow(wrapped, 0, 350, 350);
    t = follow(wrapped, 0, 10, 355);
    assert(std::fabs(t.yaw - (20 + 0.15f * 15)) < 1e-3f && "Turns take the short way across 0/360");
    Follow spin;
    t = follow(spin, 0, 0, 0);
    t = follow(spin, 0, 170, 0);
    assert(t.yaw == MaxTurn && "One poll never turns more than MaxTurn");
    Follow stuck;
    for (uint32_t k = 0; k < FarPolls; k++)
      t = follow(stuck, 0, 90, 0);
    assert(!t.engaged && t.yaw == 0 && "A camera far away for FarPolls polls is not following: stop");
    t = follow(stuck, 0, 10, 0);
    assert(t.engaged && "Back within FarDegrees, input resumes");
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
                          reinterpret_cast<uint64_t>(live.controller.bytes), reinterpret_cast<uint64_t>(live.pawn.bytes));
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
    // Minecraft's camera: applied only for the run that owns forwarded input, fresh and in range.
    auto look = [&](float pitch, float yaw, uint64_t time, uint32_t state) {
      uint64_t packed;
      float pair[2] = {pitch, yaw};
      std::memcpy(&packed, pair, 8);
      stellar_shared::store64(base, 296, packed);
      stellar_shared::store64(base, 288, time);
      stellar_shared::exchange32(base, 304, state);
    };
    auto controller_status = [&]() {
      auto middle = stellar_shared::load32(base, 128) & 3;
      return stellar_shared::plain<uint32_t>(base + Header + SlotBytes * middle, 36);
    };
    inputs.clear();
    look(-15, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll();
    assert(controller_status() == RotationValid && inputs.empty() && "No forwarded input, no camera input");
    on_gameplay_poll(std::string(32, '9'));
    assert(controller_status() == RotationValid && inputs.empty() && "Another run's input cannot turn this camera");
    on_gameplay_poll(Run);
    // Controller yaw 135, Minecraft 270: 15 % of the gap, through the yaw scale; no pitch motion yet.
    assert(controller_status() == (RotationValid | LookApplied) && inputs.size() == 1 &&
           inputs[0].first == 'y' && std::fabs(inputs[0].second - 0.15f * 135 / 2.5f) < 1e-4f &&
           "Turned through the pawn's look input");
    inputs.clear();
    look(-25, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    assert(inputs.size() == 2 && inputs[1].first == 'p' && std::fabs(inputs[1].second - -10 / -2.5f) < 1e-4f &&
           "Minecraft's pitch motion goes through the pitch scale");
    float unturned;
    std::memcpy(&unturned, base + Header + SlotBytes * (stellar_shared::load32(base, 128) & 3) + 44, 4);
    assert(unturned == 135 && "The slot carries the rotation read before the turn");
    inputs.clear();
    look(10, 30, stellar_shared::wall_now() - 1000, 1);
    on_gameplay_poll(Run);
    assert(controller_status() == RotationValid && inputs.empty() && "A stale look is ignored");
    look(95, 30, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    assert(inputs.empty() && "Pitch beyond the poles is ignored");
    look(10, 30, stellar_shared::wall_now(), 0);
    on_gameplay_poll(Run);
    assert(inputs.empty() && "An inactive look is ignored");
#ifdef _WIN32
    // An object freed after Lua published it may fault; the read is contained.
    auto before = stellar_shared::load64(base, 216);
    poll_files(directory.wstring(), request(now),
               components(8, reinterpret_cast<uint64_t>(live.a.bytes), 0x1000), "", now);
    on_gameplay_poll();
    auto *third = base + Header + SlotBytes * (stellar_shared::load32(base, 128) & 3);
    assert(stellar_shared::load64(base, 216) == before + 1 && stellar_shared::plain<uint64_t>(third, 16) == 8);
    assert(stellar_shared::plain<uint32_t>(third, 64) == 7 && stellar_shared::plain<uint32_t>(third, 68) == 0);
#endif
    // A stopped request releases the run (state 2), after which nothing is published.
    look(0, 0, 0, 0);
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
  std::cout << "Pose stream tests passed: request/table grammar and bounds, live-object validation, double-buffered reads, material parameters, effects, camera input, mapping publication and release\n";
}
