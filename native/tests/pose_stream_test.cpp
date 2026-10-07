#ifdef NDEBUG
#undef NDEBUG
#endif
#include "pose_stream.hpp"
#include "shared_memory.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
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
static uintptr_t boom_table[1]; // Stands in for USBSpringArmComponent's virtual table.

// Fake engine memory with the pinned build's offsets: a GUObjectArray with one
// chunk of FUObjectItems and skeletal mesh components that point into it.
struct Fake {
  alignas(16) uint8_t objects[0x40]{};
  std::vector<uint8_t> items = std::vector<uint8_t>(24 * 8);
  uint8_t *chunks[1];
  struct Object {
    alignas(16) uint8_t bytes[0x1c00]{};
    alignas(16) float space[2][3 * 12]{}; // Two double-buffered 3-bone arrays.
    float morphs[2]{};
  };
  Object a, b, controller, pawn, boom;
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
    std::memset(pawn.bytes + 0x5c0, 0, 8); // CameraBoom (setup's bone arrays reach it): none until a test sets it.
    // EVE's camera boom: owner, dolly used with a track on the default spline (mode -1), alpha 0.5, the arm's
    // last pitch -17, a running blend.
    setup(boom, 4);
    auto boom_vtable = reinterpret_cast<uintptr_t>(boom_table), owner = reinterpret_cast<uintptr_t>(pawn.bytes);
    std::memcpy(boom.bytes, &boom_vtable, 8);
    std::memcpy(boom.bytes + 0xb0, &owner, 8);
    boom.bytes[0x2f4] = 1;
    uint64_t track = 0x3000;
    std::memcpy(boom.bytes + 0x2f8, &track, 8);
    int32_t mode = -1;
    std::memcpy(boom.bytes + 0x3e0, &mode, 4);
    float dolly[3] = {0.25f, 0.5f, 0.9f}; // RemainBlendDollyAlpha, BlendDollyAlphaDuration, BlendTargetDollyAlpha.
    std::memcpy(boom.bytes + 0x3c0, dolly, sizeof dolly);
    arm(0.5f, -17);
  }
  // CameraDollyAlpha and the arm's last pitch (PreviousDesiredRot.Pitch).
  void arm(float alpha, float pitch) {
    std::memcpy(boom.bytes + 0x2f0, &alpha, 4);
    std::memcpy(boom.bytes + 0x2c0, &pitch, 4);
  }
  float alpha() {
    float v;
    std::memcpy(&v, boom.bytes + 0x2f0, 4);
    return v;
  }
  void attach() { // The pawn's CameraBoom.
    auto self = reinterpret_cast<uintptr_t>(boom.bytes);
    std::memcpy(pawn.bytes + 0x5c0, &self, 8);
  }
  void setup(Object &o, int32_t index) {
    auto self = reinterpret_cast<uintptr_t>(o.bytes);
    std::memcpy(items.data() + 24 * index, &self, 8);
    std::memcpy(o.bytes + 0x0c, &index, 4);
    uint64_t cls = 0x1000, mesh = 0x2000;
    std::memcpy(o.bytes + 0x10, &cls, 8);
    std::memcpy(o.bytes + 0x568, &mesh, 8);
    o.bytes[0x170] = 0x20; // USceneComponent::bVisible.
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
  assert(table && table->generation == 4 && table->components.size() == 2 && table->session == "none");
  {
    auto linked = components(4, 0x10, 0x20);
    linked.replace(linked.find("SESSION none"), 12, "SESSION " + std::string(32, 'c'));
    assert(parse_components(linked)->session == std::string(32, 'c') && "The link's session, for the lock file");
  }
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
    // What Stellar Blade draws: Hidden when the component is not visible, hidden in game or owned by a live hidden
    // actor (EVE's drone between its appearances); its floats are copied all the same.
    Fake seen;
    auto e = seen.engine();
    auto drone = Fake::component(seen.a);
    std::vector<float> values(10 + 30 + 2, -1);
    constexpr uint32_t All = WorldValid | BonesValid | MorphsValid;
    assert(read_component(e, drone, values.data()) == All);
    seen.a.bytes[0x170] = 0;
    assert(read_component(e, drone, values.data()) == (All | Hidden) && values[4] == 100 && "Not visible, still read");
    seen.a.bytes[0x170] = 0x20;
    seen.a.bytes[0x171] = 0x04;
    assert(read_component(e, drone, values.data()) == (All | Hidden) && "Hidden in game");
    seen.a.bytes[0x171] = 0;
    auto actor = reinterpret_cast<uintptr_t>(seen.b.bytes);
    std::memcpy(seen.a.bytes + 0xb0, &actor, 8);
    assert(read_component(e, drone, values.data()) == All && "A shown actor");
    seen.b.bytes[0x58] = 0x20;
    assert(read_component(e, drone, values.data()) == (All | Hidden) && "A hidden actor");
    int32_t pending = 1 << 29;
    std::memcpy(seen.items.data() + 24 * 5 + 8, &pending, 4);
    assert(read_component(e, drone, values.data()) == All && "A dead owner hides nothing");
  }
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
    // Stellar Blade's camera dolly, written only into the live boom of the live published pawn while the dolly
    // carries the camera's pitch.
    Controller c;
    c.pawn = reinterpret_cast<uint64_t>(fake.pawn.bytes);
    c.pawn_cls = 0x1000;
    auto dolly = engine;
    dolly.boom_vtable = reinterpret_cast<uintptr_t>(boom_table);
    float camera = 9;
    DollyWrite written;
    assert(!steer_dolly(dolly, c, 13, camera, written) && fake.alpha() == 0.5f && camera == 9 && "No boom on the pawn, nothing written");
    fake.attach();
    assert(!steer_dolly(engine, c, 13, camera, written) && fake.alpha() == 0.5f && "Without the pinned virtual table, nothing written");
    assert(steer_dolly(dolly, c, 13, camera, written) && std::fabs(fake.alpha() - (0.5f + 30 * DollyStep)) < 1e-6f && camera == -17 &&
           "The alpha moves by DollyStep per degree from the arm's pitch to Minecraft's");
    float blend[3];
    std::memcpy(blend, fake.boom.bytes + 0x3c0, sizeof blend);
    assert(blend[0] == 0 && blend[1] == 0 && blend[2] == -1 && "The game's own dolly blend is cancelled, as its pitch input does");
    fake.arm(0.95f, 343); // A pitch kept in 0..360.
    assert(steer_dolly(dolly, c, 90, camera, written) && fake.alpha() == 1 && camera == -17 && "Alpha stays within 0..1");
    fake.arm(0.05f, -17);
    assert(steer_dolly(dolly, c, -90, camera, written) && fake.alpha() == 0 && "Down to 0 as well");
    fake.arm(0.5f, -17);
    std::vector<uint32_t> reasons; // Each refusal's reason, in order.
    // Nothing written (alpha, blend) and nothing reported, and the previous write forgotten.
    auto refused = [&](uint8_t *object, size_t at, auto value, const char *why) {
      decltype(value) saved;
      std::memcpy(&saved, object + at, sizeof value);
      std::memcpy(object + at, &value, sizeof value);
      float seeded[3] = {0.25f, 0.5f, 0.9f}, blend[3];
      std::memcpy(fake.boom.bytes + 0x3c0, seeded, sizeof seeded);
      uint32_t before, after;
      std::memcpy(&before, fake.boom.bytes + 0x2f0, 4);
      camera = 9;
      DollyWrite previous{0.5f, -17, true};
      uint32_t reason = DollyNone;
      bool wrote = steer_dolly(dolly, c, 13, camera, previous, &reason);
      reasons.push_back(reason);
      std::memcpy(&after, fake.boom.bytes + 0x2f0, 4);
      std::memcpy(blend, fake.boom.bytes + 0x3c0, sizeof blend);
      std::memcpy(object + at, &saved, sizeof value);
      assert(!wrote && before == after && camera == 9 && !previous.moved &&
             std::memcmp(blend, seeded, sizeof seeded) == 0 && why);
    };
    refused(fake.boom.bytes, 0x370, uint8_t(1), "Nor while the arm follows the controller (dolly blocked)");
    refused(fake.boom.bytes, 0x374, 0.1f, "Nor while the arm eases its rotation after a mode change");
    refused(fake.boom.bytes, 0x284, uint8_t(0x40), "Nor with rotation lag");
    refused(fake.boom.bytes, 0x3e0, int32_t(0), "Nor on another dolly spline");
    refused(fake.boom.bytes, 0x3d8, int32_t(1), "Nor while a spline blend places the camera");
    refused(fake.boom.bytes, 0x2f4, uint8_t(0), "Nor while the arm does not use the dolly");
    refused(fake.pawn.bytes, 0x1528, uint8_t(1), "Nor in TPS aim, whose view sets the dolly");
    refused(fake.pawn.bytes, 0x1690, uint8_t(2), "Nor while fishing");
    refused(fake.boom.bytes, 0x2f8, uint64_t(0), "Nor without a dolly track");
    refused(fake.boom.bytes, 0xb0, uint64_t(0x40), "Nor into another actor's boom");
    refused(fake.boom.bytes, 0, reinterpret_cast<uint64_t>(pawn_table), "Nor into another component class");
    refused(fake.boom.bytes, 0x2f0, std::nanf(""), "Nor from a non-finite alpha");
    refused(fake.boom.bytes, 0x2f0, INFINITY, "Nor from an infinite alpha");
    refused(fake.boom.bytes, 0x2c0, std::nanf(""), "Nor from a non-finite arm pitch");
    refused(fake.items.data() + 24 * 4, 8, int32_t(1 << 29), "Nor into a pending-kill boom");
    // Each refusal names its reason, in the order above.
    std::vector<uint32_t> expected = {DollyController, DollyEasing, DollyLag, DollySpline, DollySpline, DollyUnused, DollyAim,
                                      DollyFishing, DollyUnused, DollyNoBoom, DollyNoBoom, DollyUnreadable, DollyUnreadable,
                                      DollyUnreadable, DollyNoBoom};
    assert(reasons == expected && "Refusal reasons");
    // The slope camera sets the alpha after the arm while it has a type, runs and its running time is past its
    // start (0x2028900).
    float times[3] = {0.5f, 0, 0.2f}; // CurrentCameraSlopeMoveRunningTime, RemainBlockCameraSlopeMove, CameraSlopeRunTimeFromStart.
    std::memcpy(fake.pawn.bytes + 0x1b64, times, sizeof times);
    fake.pawn.bytes[0x1b5c] = 3;
    assert(steer_dolly(dolly, c, 13, camera, written) && "A slope camera that does not run leaves the dolly to Minecraft");
    fake.arm(0.5f, -17);
    refused(fake.pawn.bytes, 0x1b5d, uint8_t(1), "Nor while the slope camera sets the alpha");
    assert(reasons.back() == DollySlope && "The slope camera's reason");
    fake.pawn.bytes[0x1b5d] = 1;
    fake.pawn.bytes[0x1b5c] = 0;
    assert(steer_dolly(dolly, c, 13, camera, written) && "Nor without a slope move type");
    fake.pawn.bytes[0x1b5c] = 3;
    std::memcpy(fake.pawn.bytes + 0x1b64, &times[2], 4);
    fake.arm(0.5f, -17);
    assert(steer_dolly(dolly, c, 13, camera, written) && "Nor at its start time");
    std::memset(fake.pawn.bytes + 0x1b5c, 0, 0x14);
    fake.arm(0.5f, -17);
    c.pawn_cls = 0x1008;
    assert(!steer_dolly(dolly, c, 13, camera, written) && fake.alpha() == 0.5f && "Nor through another pawn class");
    c.pawn_cls = 0x1000;
    // Against a stand-in arm whose pitch spans -89.9..57.5 over alpha 0..1, an 85 degree gap closes within half a
    // degree in eight polls, from one side.
    fake.arm(0.2f, 0);
    float last = 180;
    for (int poll = 0; poll < 8; poll++) {
      fake.arm(fake.alpha(), -89.9f + 147.4f * fake.alpha());
      assert(steer_dolly(dolly, c, 25, camera, written));
      float gap = 25 - camera;
      assert(gap >= 0 && gap <= last && "The camera closes on Minecraft's pitch from one side");
      last = gap;
    }
    fake.arm(fake.alpha(), -89.9f + 147.4f * fake.alpha());
    assert(steer_dolly(dolly, c, 25, camera, written) && std::fabs(25 - camera) < 0.5f);
    // A poll that finds the alpha it wrote and the arm's pitch unchanged since a write that moved the alpha (the arm
    // has not ticked: Stellar Blade paused or hitching) holds; the next arm update resumes the loop. At a rail nothing
    // moved, so the write goes on.
    fake.arm(0.5f, -17);
    assert(steer_dolly(dolly, c, 13, camera, written) && written.moved);
    float seeded[3] = {0.25f, 0.5f, 0.9f}, blend_now[3];
    std::memcpy(fake.boom.bytes + 0x3c0, seeded, sizeof seeded);
    auto wrote = fake.alpha();
    assert(steer_dolly(dolly, c, 13, camera, written) && fake.alpha() == wrote && camera == -17 && written.moved &&
           std::memcmp(fake.boom.bytes + 0x3c0, seeded, sizeof seeded) == 0 && "No arm update since the write: held");
    fake.arm(fake.alpha(), -10);
    assert(steer_dolly(dolly, c, 13, camera, written) && std::fabs(fake.alpha() - (wrote + 23 * DollyStep)) < 1e-6f &&
           "The next arm update resumes it");
    fake.arm(1, 57);
    assert(steer_dolly(dolly, c, 90, camera, written) && fake.alpha() == 1 && !written.moved);
    std::memcpy(fake.boom.bytes + 0x3c0, seeded, sizeof seeded);
    assert(steer_dolly(dolly, c, 90, camera, written));
    std::memcpy(blend_now, fake.boom.bytes + 0x3c0, sizeof blend_now);
    assert(blend_now[2] == -1 && "At a rail the write goes on");
    std::memset(fake.pawn.bytes + 0x5c0, 0, 8);
    fake.arm(0.5f, -17);
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
    fake.controller.bytes[0x170] = 0;
    assert(read_effects(engine, t, records.data()) == 0 && "An effect Stellar Blade does not draw is no effect");
    fake.controller.bytes[0x170] = 0x20;
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
  initialize(reinterpret_cast<uintptr_t>(live.objects), reinterpret_cast<uintptr_t>(boom_table));
  auto directory = std::filesystem::temp_directory_path() / ("stellar-pose-" + std::to_string(now));
  std::filesystem::create_directories(directory);
  auto path = directory / ("eve-pose-" + Run + ".bin");
  auto mapping = [&](const std::filesystem::path &file) {
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
    std::ofstream(file, std::ios::binary).write(reinterpret_cast<char *>(bytes.data()), bytes.size());
  };
  mapping(path);
  {
    // The evade words follow the gauges in the header, written with each slot; the rest stays reserved.
    auto alone = directory / "alone";
    std::filesystem::create_directories(alone);
    mapping(alone / ("eve-pose-" + Run + ".bin"));
    Producer direct;
    assert(direct.open(alone.wstring(), *parse_request(request(now)), 77, now));
    uint32_t state = WorldValid, evades[EvadeWords] = {1, 2, 3, 4, 5, 6, 7};
    float pose[10] = {};
    assert(direct.publish(1, &state, 1, pose, 10, nullptr, 0, now, 0, nullptr, evades));
    stellar_shared::Mapping view;
    assert(view.open(alone / ("eve-pose-" + Run + ".bin"), TotalBytes));
    for (uint32_t i = 0; i < EvadeWords; i++)
      assert(stellar_shared::plain<uint32_t>(view.base, 344 + 4 * i) == i + 1 && "Evade words in order at 344");
    assert(std::all_of(view.base + 344 + 4 * EvadeWords, view.base + Header, [](uint8_t b) { return b == 0; }) &&
           "Nothing after them");
    // EVE's stats: floats at StatsAt, state 1 at StatsState, cleared to 0 when unreadable; the rest stays reserved.
    float stats[StatFloats] = {2000, 2, 450, 5, 1.5f, 0, 0, 0};
    assert(direct.publish(2, &state, 1, pose, 10, nullptr, 0, now, 0, nullptr, evades, stats));
    assert(stellar_shared::load32(view.base, StatsState) == 1 && stellar_shared::plain<float>(view.base, StatsAt) == 2000 &&
           stellar_shared::plain<float>(view.base, StatsAt + 4 * 4) == 1.5f && "Stats in order");
    assert(std::all_of(view.base + 344 + 4 * EvadeWords, view.base + StatsState, [](uint8_t b) { return b == 0; }) &&
           std::all_of(view.base + StatsAt + 4 * StatFloats, view.base + Header, [](uint8_t b) { return b == 0; }) &&
           "Reserved bytes around them");
    assert(direct.publish(3, &state, 1, pose, 10, nullptr, 0, now, 0, nullptr, evades) &&
           stellar_shared::load32(view.base, StatsState) == 0 && "Unreadable stats clear their state");
    // Her drone's shots: one word at ShotsAt, right after the stats; the rest stays reserved. The dolly refusal, the
    // argument before them, keeps its own slot word.
    float turned[4] = {0, 90, 0, 0};
    assert(direct.publish(4, &state, 1, pose, 10, turned, 0, now, 0, nullptr, evades, stats, DollyLag, 5));
    assert(ShotsAt == StatsAt + 4 * StatFloats && stellar_shared::plain<uint32_t>(view.base, ShotsAt) == 5 && "Shots at 484");
    uint32_t refusals = 0;
    for (uint32_t i = 0; i < 3; i++) {
      auto *slot = view.base + Header + SlotBytes * i;
      if (stellar_shared::plain<uint64_t>(slot, 16) == 4)
        refusals += stellar_shared::plain<uint32_t>(slot, 56) == DollyLag;
    }
    assert(refusals == 1 && "The refusal in the published slot at 56");
    assert(std::all_of(view.base + ShotsAt + 4, view.base + Header, [](uint8_t b) { return b == 0; }) && "Nothing after them");
    // Format 4: the adapter's report after the shot count (heal count, mirror state, losses, Beta and Burst granted),
    // then the player actor's whole stat array with state 1 at 508; the rest stays reserved.
    float actor_stats[ActorStats];
    for (uint32_t i = 0; i < ActorStats; i++)
      actor_stats[i] = i / 2.0f;
    assert(direct.publish(5, &state, 1, pose, 10, nullptr, 0, now, 0, nullptr, evades, stats, DollyNone, 5,
                          Report{1000, MirrorHeld, 1, {17, 200}, actor_stats}));
    auto *latest = view.base + Header + SlotBytes * (stellar_shared::load32(view.base, 128) & 3);
    assert(stellar_shared::plain<uint64_t>(latest, 16) == 5 && stellar_shared::plain<uint32_t>(latest, 24) == 4 && "Format 4");
    uint32_t report[5] = {1000, 2, 1, 17, 200};
    for (uint32_t i = 0; i < 5; i++)
      assert(stellar_shared::plain<uint32_t>(view.base, 488 + 4 * i) == report[i] && "The report in order at 488");
    assert(stellar_shared::load32(view.base, 508) == 1 && stellar_shared::plain<float>(view.base, 512 + 4 * 130) == 65 &&
           "The actor's stats from 512, state 1 at 508");
    auto zero = [](uint8_t b) { return b == 0; };
    assert(std::all_of(view.base + 1036, view.base + Header, zero) && std::all_of(view.base + 308, view.base + 312, zero) &&
           "Nothing after the actor's stats, nor between the camera and the gains word");
    assert(direct.publish(6, &state, 1, pose, 10, nullptr, 0, now, 0, nullptr, evades));
    assert(std::all_of(view.base + 488, view.base + 512, zero) && "No report: zeros and actor state 0");
    // Minecraft's health and gains words, read only while its camera is fresh.
    Feedback fed;
    assert(!direct.feedback(now, fed) && "No camera, no words");
    float half = 0.5f;
    uint32_t share;
    std::memcpy(&share, &half, 4);
    stellar_shared::store64(view.base, 280, uint64_t(7) << 32 | share);
    stellar_shared::store64(view.base, 312, 30 | uint64_t(200) << 32);
    stellar_shared::store64(view.base, 296, 0);
    stellar_shared::store64(view.base, 288, now);
    stellar_shared::exchange32(view.base, 304, 1);
    assert(direct.feedback(now, fed) && fed.share == 0.5f && fed.acknowledged == 7 && fed.beta == 30 && fed.burst == 200 &&
           "Share and acknowledgement, then Beta and Burst totals");
    stellar_shared::store64(view.base, 288, now - 201);
    assert(!direct.feedback(now, fed) && "Stale camera, stale words");
    stellar_shared::store64(view.base, 288, now);
    stellar_shared::exchange32(view.base, 304, 0);
    assert(!direct.feedback(now, fed) && "An inactive camera, no words");
    direct.stop();
  }
  auto slots = components(7, reinterpret_cast<uint64_t>(live.a.bytes), reinterpret_cast<uint64_t>(live.b.bytes),
                          reinterpret_cast<uint64_t>(live.controller.bytes), reinterpret_cast<uint64_t>(live.pawn.bytes));
  {
    // EVE's gauges: game world -> actor manager -> player actor -> stats at +0x118 + 4 * stat.
    std::vector<uint8_t> world(0x100), manager(0x100), actor(0x2700);
    uintptr_t actors[2] = {0, reinterpret_cast<uintptr_t>(actor.data())};
    auto put_ptr = [](std::vector<uint8_t> &b, size_t at, uintptr_t v) { std::memcpy(b.data() + at, &v, 8); };
    auto put_i32 = [](std::vector<uint8_t> &b, size_t at, int32_t v) { std::memcpy(b.data() + at, &v, 4); };
    auto stat = [&](uint32_t index, float v) { std::memcpy(actor.data() + 0x118 + 4 * index, &v, 4); };
    put_ptr(world, 0xc8, reinterpret_cast<uintptr_t>(manager.data()));
    put_ptr(manager, 0x28, reinterpret_cast<uintptr_t>(actors));
    put_i32(manager, 0x30, 2);
    put_i32(manager, 0x3c, 1);
    put_i32(actor, 0x2600, 1);
    stat(14, 400); stat(15, 1000); stat(18, 130); stat(19, 1600); stat(116, 1);
    float g[GaugeFloats];
    auto w = reinterpret_cast<uintptr_t>(world.data());
    assert(read_gauges(w, g) && g[0] == 400 && g[1] == 1000 && g[2] == 130 && g[3] == 1600 && g[4] == 1 && "Beta, Burst and their maximums");
    assert(!read_gauges(0, g) && "No game world, no gauges");
    put_i32(actor, 0x2600, 3);
    assert(!read_gauges(w, g) && "A dead or despawned player has none");
    put_i32(actor, 0x2600, 6);
    stat(18, 2000);
    assert(!read_gauges(w, g) && "A gauge above its maximum is not trusted");
    stat(18, std::nanf(""));
    assert(!read_gauges(w, g) && "Nor a non-finite one");
    stat(18, 130);
    // Her stats for Minecraft, from the same array: MaxHP, ShieldBlock, attack power, critical rate and damage,
    // then the melee, ranged and final HP damage reduction rates (a negative one adds damage).
    stat(2, 2000); stat(11, 2); stat(26, 450); stat(38, 5); stat(39, 1.5f); stat(55, 0.1f); stat(56, 0); stat(71, -0.2f);
    float s[StatFloats];
    assert(read_stats(w, s) && s[0] == 2000 && s[1] == 2 && s[2] == 450 && s[3] == 5 && s[4] == 1.5f && s[5] == 0.1f &&
           s[6] == 0 && s[7] == -0.2f && "EVE's stats");
    assert(!read_stats(0, s) && "No game world, no stats");
    stat(26, 0);
    assert(!read_stats(w, s) && "No attack power is not her actor's");
    stat(26, 450);
    stat(71, std::nanf(""));
    assert(!read_stats(w, s) && "Nor is a non-finite rate");
    stat(71, -0.2f);
    stat(2, 2 * GaugeLimit);
    assert(!read_stats(w, s) && "Nor a maximum beyond the gauges' limit");
    stat(2, 2000);
    put_i32(manager, 0x3c, 2);
    assert(!read_gauges(w, g) && !read_stats(w, s) && "The player index stays within the actor count");
    put_i32(manager, 0x3c, 1);
    // AddStat's gauge gain: the rate capped, truncated to thousandths, the bonus rounded up with the rate's sign.
    assert(gauge_gain(15, 0, BetaRateCap) == 15 && gauge_gain(15, .06f, BetaRateCap) == 16 &&
           gauge_gain(15, .13f, BetaRateCap) == 17 && gauge_gain(65, .13f, BetaRateCap) == 74 &&
           gauge_gain(200, .27f, BurstRateCap) == 254 && "Points plus ceil(points x rate)");
    assert(gauge_gain(15, .9f, BetaRateCap) == 21 && gauge_gain(200, .9f, BurstRateCap) == 350 && "The rate is capped");
    assert(gauge_gain(15, .0669f, BetaRateCap) == 16 && "The rate is truncated to thousandths (ceil(15 x 0.0669) would give 17)");
    assert(gauge_gain(100, .1009f, BetaRateCap) == 110 && "Thousandths truncated, not rounded (101 would give 111)");
    assert(gauge_gain(15, -.1f, BetaRateCap) == 13 && "A negative rate takes its bonus away");
    // Minecraft's points land in Beta and Burst as AddStat adds a hit's.
    auto value = [&](uint32_t index) {
      float v;
      std::memcpy(&v, actor.data() + 0x118 + 4 * index, 4);
      return v;
    };
    stat(64, .13f); stat(111, 0);
    Grants grants;
    auto feed = [&](uint32_t beta, uint32_t burst) {
      Feedback f;
      f.beta = beta;
      f.burst = burst;
      grant_poll(w, true, &f, grants);
    };
    feed(15, 0);
    assert(grants.known && value(14) == 400 && value(18) == 130 && "First sighting is a baseline");
    feed(30, 0);
    assert(value(14) == 417 && grants.granted[0] == 17 && "15 points at rate 0.13");
    feed(30, 0);
    assert(value(14) == 417 && grants.granted[0] == 17 && "A step applies once");
    feed(30, 200);
    assert(value(18) == 330 && grants.granted[1] == 200 && "Burst at its own rate");
    grant_poll(w, true, nullptr, grants);
    feed(45, 200);
    assert(value(14) == 434 && grants.granted[0] == 34 && "Stale words skip: the step lands at the next fresh poll");
    grant_poll(w, false, nullptr, grants);
    assert(!grants.known && "Input or stream lost: the totals are forgotten");
    feed(60, 200);
    assert(value(14) == 434 && "Rebaseline after losing the input");
    feed(10061, 200);
    assert(value(14) == 434 && "A step above 10000 is adopted, not granted");
    stat(116, 0);
    feed(10061, 400);
    assert(value(18) == 330 && grants.granted[1] == 200 && "Locked Burst gains nothing");
    feed(10076, 400);
    assert(value(14) == 451 && "Beta still gains");
    stat(14, 995);
    auto before = grants.granted[0];
    feed(10091, 400);
    assert(value(14) == 1000 && grants.granted[0] == before + 5 && "Clamped at its maximum");
    stat(14, 400);
    stat(116, 1);
    put_i32(actor, 0x2600, 3);
    feed(10106, 400);
    assert(value(14) == 400 && "No spawned player, no write");
    put_i32(actor, 0x2600, 1);
    feed(10106, 400);
    assert(value(14) == 400 && "A failed grant is not retried later");
    stat(64, std::nanf(""));
    feed(10121, 600);
    assert(value(14) == 400 && value(18) == 330 && "A non-finite rate writes nothing");
    stat(64, .13f);
    Grants wrapped;
    wrapped.known = true;
    wrapped.beta = 0xFFFFFFF0;
    Feedback over;
    over.beta = 0x0F;
    grant_poll(w, true, &over, wrapped);
    // 31 points (0x0F - 0xFFFFFFF0 in u32) at rate 0.13: 31 + ceil(4.03) = 35.
    assert(value(14) == 435 && wrapped.granted[0] == 35 && "U32 wrap");
    stat(14, 400); stat(18, 130); stat(64, .9f); stat(111, .9f);
    Grants capped;
    capped.known = true;
    auto step = [&](uint32_t beta, uint32_t burst) {
      Feedback f;
      f.beta = beta;
      f.burst = burst;
      grant_poll(w, true, &f, capped);
    };
    step(15, 200);
    assert(value(14) == 421 && value(18) == 480 && "Each gauge's rate capped at its own maximum");
    step(15, 10201);
    assert(value(18) == 480 && "A Burst step above 10000 is adopted, not granted");
    step(15, 11401);
    assert(value(18) == 1600 && "1200 points in one poll (a charged swing through six mobs) are granted");
    stat(18, 130);
    step(15, 21401);
    assert(value(18) == 1600 && "A step of exactly 10000 is granted");
    stat(18, 130);
    for (float bad : {std::nanf(""), 2 * GaugeLimit}) {
      stat(111, bad);
      step(15, capped.burst + 15);
      assert(value(18) == 130 && "A Burst rate non-finite or beyond the limit writes nothing");
    }
    // The HP mirror: Minecraft's share of MaxHP, a quarter at least, 1 per mille under full, never 0.
    assert(mirrored(1, 2000) == 2000 && mirrored(.5f, 2000) == 1000 && mirrored(.9999f, 2000) == 1998 &&
           mirrored(.1f, 2000) == 500 && mirrored(.01f, 2) == 1 && mirrored(.5f, 2) == 1 && "Never 0");
    assert(mirrored(.6669f, 2000) == 1333 && "Floored");
    stat(1, 2000);
    HealthMirror m;
    mirror_health(w, .5f, 0, true, 1000, m);
    assert(value(1) == 1000 && m.state == MirrorWritten && m.active && m.original == 2000 && "Minecraft's share written");
    stat(1, 2000); // Stellar Blade heals.
    mirror_health(w, .5f, 0, true, 1050, m);
    assert(m.healed == 1000 && m.state == MirrorHeld && value(1) == 2000 && "A heal waits for its acknowledgement");
    mirror_health(w, 1, 1000, true, 1100, m);
    assert(value(1) == 2000 && m.state == MirrorWritten && "Acknowledged: written again");
    mirror_health(w, .75f, 1000, true, 1150, m);
    assert(value(1) == 1500);
    stat(1, 1400); // Stellar Blade's own damage.
    mirror_health(w, .75f, 1000, true, 1200, m);
    assert(m.lost == 1 && value(1) == 1500 && "A loss is counted and overwritten");
    // The lease counts from the last usable share (1200), not from the activation (1000).
    mirror_health(w, 0, 0, false, 2100, m);
    assert(m.state == MirrorHeld && value(1) == 1500 && "A stale share holds");
    stat(1, 1600);
    mirror_health(w, 0, 0, false, 2150, m);
    assert(m.healed == 1100 && "Counted while stale");
    mirror_health(w, 0, 0, false, 2201, m);
    assert(value(1) == 2000 && m.state == MirrorOff && !m.active && "Restored after the lease");
    mirror_health(w, .5f, 1100, true, 2300, m);
    assert(value(1) == 1000 && m.healed == 1100 && m.state == MirrorWritten && "The restore is not a heal");
    for (float bad : {std::nanf(""), 1.5f}) {
      mirror_health(w, bad, 1100, true, 2350, m);
      assert(m.state == MirrorHeld && value(1) == 1000 && "An unusable share holds, nothing written");
    }
    put_i32(actor, 0x2600, 3);
    mirror_health(w, .75f, 1100, true, 2400, m);
    assert(m.state == MirrorOff && value(1) == 1000 && m.active && "No spawned player: nothing written, the record kept");
    stat(1, 2000); // Her respawn refills her HP while she is away.
    put_i32(actor, 0x2600, 1);
    mirror_health(w, .75f, 1100, true, 2450, m);
    assert(value(1) == 1500 && m.state == MirrorWritten && m.original == 2000 && m.healed == 1100 &&
           "Back: her HP on return is no heal, and it continues with her original HP");
    std::vector<uint8_t> next(0x2700);
    put_i32(next, 0x2600, 1);
    float next_hp[2] = {3000, 4000};
    std::memcpy(next.data() + 0x11c, next_hp, sizeof next_hp);
    actors[0] = uintptr_t(next.data());
    put_i32(manager, 0x3c, 0);
    mirror_health(w, .5f, 1100, true, 2500, m);
    std::memcpy(next_hp, next.data() + 0x11c, 4);
    assert(next_hp[0] == 2000 && m.original == 3000 && value(1) == 1500 && "Another actor: its own original, the first untouched");
    actors[0] = 0;
    put_i32(manager, 0x3c, 1);
    HealthMirror idle;
    stat(1, 1234);
    mirror_health(w, .5f, 0, false, 3000, idle);
    assert(idle.state == MirrorOff && !idle.active && !idle.actor && value(1) == 1234 && "Idle: nothing read or written");
    HealthMirror checked;
    stat(1, 2000);
    mirror_health(w, .5f, 0, true, 4000, checked);
    stat(1, 2500);
    mirror_health(w, .5f, 0, true, 4050, checked);
    assert(checked.state == MirrorOff && value(1) == 2500 && checked.healed == 0 && "HP above MaxHP is refused, not a heal");
    stat(1, 1000);
    stat(2, 1800);
    mirror_health(w, 0, 0, false, 5100, checked);
    assert(value(1) == 1800 && !checked.active && "The restore is clamped to MaxHP");
    // The player actor's whole stat array, in order.
    for (uint32_t i = 0; i < ActorStats; i++)
      stat(i, i + 0.25f);
    float copied[ActorStats];
    assert(read_actor(w, copied));
    for (uint32_t i = 0; i < ActorStats; i++)
      assert(copied[i] == i + 0.25f && "Stats 0..130 in order");
    put_i32(actor, 0x2600, 3);
    assert(!read_actor(w, copied) && "Not a dead player's");
    put_i32(actor, 0x2600, 1);
    // Minecraft's lock: EVE's lock-gated evades (globals under the root, double dodges one below) are opened.
    std::vector<uint8_t> set(0x180), root(0x20), normal(0xb8), lockon(0xb8), chain(0xb8), air(0xb8), attack(0xb8);
    uintptr_t top[4] = {uintptr_t(normal.data()), uintptr_t(lockon.data()), uintptr_t(air.data()), uintptr_t(attack.data())};
    uintptr_t below[1] = {uintptr_t(chain.data())};
    assert(patch_evades(w, true) == 0 && "No command set, nothing changed");
    put_ptr(actor, 0x530, uintptr_t(set.data()));
    put_ptr(set, 8, uintptr_t(root.data()));
    assert(patch_evades(w, true) == 0 && "A set owned by another actor is not EVE's");
    put_ptr(set, 0x178, uintptr_t(actor.data()));
    put_ptr(root, 8, uintptr_t(top));
    put_i32(root, 0x10, 4);
    put_ptr(lockon, 8, uintptr_t(below));
    put_i32(lockon, 0x10, 1);
    for (auto *n : {&normal, &lockon, &chain, &air})
      put_i32(*n, 0x18, 24);
    put_i32(attack, 0x18, 3);
    put_i32(normal, 0x68, 1); put_i32(lockon, 0x68, 2); put_i32(chain, 0x68, 2); put_i32(air, 0x68, 0); put_i32(attack, 0x68, 2);
    auto cond = [](std::vector<uint8_t> &n) { int32_t v; std::memcpy(&v, n.data() + 0x68, 4); return v; };
    assert(patch_evades(w, true) == 3 && cond(normal) == 3 && cond(lockon) == 0 && cond(chain) == 0 && cond(air) == 0 &&
           cond(attack) == 2 && "Lock-on evades for any target, plain ones closed, others untouched");
    assert(patch_evades(w, true) == 3 && "Idempotent while held");
    put_i32(lockon, 0x68, 2); // Written back by the game while held.
    assert(patch_evades(w, true) == 3 && cond(lockon) == 0 && "Patched again, one record per node");
    put_i32(root, 0x10, 1); // The lock-on evade left the tree.
    assert(patch_evades(w, false) == 2 && cond(normal) == 1 && cond(lockon) == 0 && "Live nodes restored, the others kept");
    put_i32(root, 0x10, 4);
    assert(patch_evades(w, false) == 0 && cond(lockon) == 2 && cond(chain) == 2 && "Restored once back in her tree");
    assert(patch_evades(w, true) == 3);
    put_i32(chain, 0x68, 1); // Rewritten by the game meanwhile.
    put_i32(actor, 0x2600, 3);
    assert(patch_evades(w, false) == 3 && "Records kept while her tree is unreadable");
    put_i32(actor, 0x2600, 1);
    assert(patch_evades(w, false) == 0 && cond(normal) == 1 && cond(lockon) == 2 && cond(chain) == 1 &&
           "Restored once readable, a value the game rewrote is left alone");
    // Her evade starts: the command set's current node and its start time, as each skill starts.
    EvadeStarts starts;
    auto start = [&](std::vector<uint8_t> &node, float time) {
      put_ptr(set, 0x70, uintptr_t(node.data()));
      std::memcpy(set.data() + 0x78, &time, 4);
    };
    auto count = [&](bool locked, uint64_t wall, std::array<uint32_t, EvadeWords> expected) {
      count_evades(w, locked, wall, starts);
      return std::equal(expected.begin(), expected.end(), starts.counts);
    };
    normal[0xb0] = lockon[0xb0] = air[0xb0] = attack[0xb0] = 1; // The stance's own rows; chain is a combo child.
    float window = 0.8f; // The first dodges' combo window, as the tick keeps it.
    std::memcpy(normal.data() + 0xac, &window, 4);
    std::memcpy(lockon.data() + 0xac, &window, 4);
    start(root, 1);
    assert(count(false, 1000, {}) && "Not an evade");
    start(normal, 2);
    assert(count(false, 1000, {1, 0, 0, 0, 0, 0}) && count(false, 1010, {1, 0, 0, 0, 0, 0}) && "A start counts once");
    start(chain, 2.4f);
    assert(count(false, 1400, {1, 1, 0, 0, 0, 0}) && "Her combo child is a double dodge");
    start(root, 0);
    assert(count(false, 1410, {1, 1, 0, 0, 0, 0}) && "A double dodge's window ends at once, never cut");
    start(lockon, 3);
    assert(count(true, 2000, {1, 1, 0, 1, 0, 0}) && "A first dodge while locked");
    start(root, 0);
    assert(count(true, 2050, {1, 1, 0, 1, 0, 1}) && "Its window closed 50 ms in: cut");
    start(lockon, 4);
    assert(count(true, 3000, {1, 1, 0, 2, 0, 1}) && "A restart at a new time counts again");
    start(lockon, 0);
    assert(count(true, 3100, {1, 1, 0, 2, 0, 2}) && "A skill out of her tree zeroes the time: no start, the window cut");
    start(lockon, 5);
    assert(count(true, 4000, {1, 1, 0, 3, 0, 2}) && "A later real start counts");
    start(root, 0);
    assert(count(true, 4600, {1, 1, 0, 3, 0, 2}) && "A window ended within the margin of its end is not cut");
    start(attack, 7);
    assert(count(true, 5000, {1, 1, 0, 3, 0, 2}) && "Other commands never count");
    start(normal, 8);
    count_evades(w, false, 6000, starts);
    start(root, 0);
    assert(count(true, 6100, {2, 1, 1, 3, 0, 2}) && "A cut counts with the lock state its dodge started in");
    // Her drone's shots: real starts of TPSFire nodes whose action fires (RangedFireItem 1, RangedFireSkill 2).
    std::vector<uint8_t> slug(0xb8), railgun(0xb8), misfire(0xb8);
    for (auto *n : {&slug, &railgun, &misfire})
      put_i32(*n, 0x18, 44);
    put_i32(slug, 0x30, 1); put_i32(railgun, 0x30, 2); put_i32(misfire, 0x30, 0);
    assert(starts.shots == 0);
    start(slug, 10);
    assert(count(false, 6200, {2, 1, 1, 3, 0, 2}) && starts.shots == 1 && "A shot, no evade");
    assert(count(false, 6210, {2, 1, 1, 3, 0, 2}) && starts.shots == 1 && "Counted once");
    start(slug, 10.1f);
    assert(count(true, 6300, {2, 1, 1, 3, 0, 2}) && starts.shots == 2 && "Held fire restarts it: locked or not");
    start(railgun, 11);
    assert(count(false, 6400, {2, 1, 1, 3, 0, 2}) && starts.shots == 3 && "A skill shot (railgun, missile)");
    start(misfire, 12);
    assert(count(false, 6500, {2, 1, 1, 3, 0, 2}) && starts.shots == 3 && "The empty gun's misfire is no shot");
    start(slug, 0);
    assert(count(false, 6600, {2, 1, 1, 3, 0, 2}) && starts.shots == 3 && "A zeroed time is no shot");
    start(normal, 13);
    count_evades(w, false, 6700, starts);
    start(slug, 14);
    assert(count(false, 6750, {3, 1, 1, 3, 0, 2}) && starts.shots == 4 && "A shot ends a dodge's window early without a cut");
    put_ptr(set, 0x178, 0);
    start(normal, 9);
    assert(count(false, 7000, {3, 1, 1, 3, 0, 2}) && starts.shots == 4 && "Not her command set, nothing counted");
    put_ptr(set, 0x178, uintptr_t(actor.data()));
    assert(patch_evades(w, true) == 3);
    std::vector<uint8_t> other(0x2700);
    put_i32(other, 0x2600, 1);
    actors[0] = uintptr_t(other.data());
    put_i32(manager, 0x3c, 0); // Another player actor, whose command set has no lock-gated evades.
    put_ptr(other, 0x530, uintptr_t(set.data()));
    put_ptr(set, 0x178, uintptr_t(other.data()));
    put_i32(root, 0x10, 0);
    assert(patch_evades(w, false) == 0 && "Records of a gone actor are dropped");
    put_i32(manager, 0x3c, 1);
    put_ptr(actor, 0x530, 0);
    // Minecraft's lock: EVE's battle stance stamped with the turn clock while she is in battle mode.
    std::vector<uint8_t> clock(0x78);
    uintptr_t clock_table[1] = {};
    auto turn = uintptr_t(clock.data()), turn_table = uintptr_t(clock_table);
    auto tick = [&](float seconds) { std::memcpy(clock.data() + 0xc, &seconds, 4); };
    auto stamp = [&]() { float v; std::memcpy(&v, actor.data() + 0x558, 4); return v; };
    put_ptr(clock, 0, turn_table);
    float stamped = 40;
    std::memcpy(actor.data() + 0x558, &stamped, 4);
    tick(42);
    assert(!hold_battle(w, turn, turn_table) && stamp() == 40 && "Out of battle mode, left alone");
    actor[0xb58] = 1;
    assert(hold_battle(w, turn, turn_table) && stamp() == 42 && "In battle mode, stamped with the turn clock");
    assert(!hold_battle(w, turn, turn_table) && stamp() == 42 && "The same time again: nothing written");
    tick(41);
    assert(!hold_battle(w, turn, turn_table) && stamp() == 42 && "Never backward");
    tick(std::nanf(""));
    assert(!hold_battle(w, turn, turn_table) && stamp() == 42 && "Nor from a non-finite clock");
    tick(INFINITY);
    assert(!hold_battle(w, turn, turn_table) && stamp() == 42 && "Nor from an infinite one");
    tick(43);
    assert(!hold_battle(w, turn, turn_table + 8) && !hold_battle(w, 0, turn_table) && stamp() == 42 &&
           "Only the live turn manager's time");
    put_i32(actor, 0x2600, 3);
    assert(!hold_battle(w, turn, turn_table) && stamp() == 42 && "Not a dead player's");
    put_i32(actor, 0x2600, 1);
    assert(hold_battle(w, turn, turn_table) && stamp() == 43);
    actor[0xb58] = 0;
    auto lock_file = [](const char *seq, const char *target, const char *end) {
      return "SBMC_LOCK 1\nSESSION " + Run + "\nSEQ " + seq + "\nTIME 1000\nTARGET " + target + "\nEND " + end + "\n";
    };
    assert(parse_lock(lock_file("3", "1.000 2.000 3.000", "3"), Run) == 1000 && "A held target gives its time");
    assert(parse_lock(lock_file("3", "1.000 2.000 3.000", "3"), std::string(32, 'b')) == 0 && "Not for another session");
    assert(parse_lock(lock_file("3", "1.000 2.000 3.000", "3"), "none") == 0 && "Nor without a link");
    {
      std::ifstream shared("protocol/fixtures/lock.txt", std::ios::binary); // The file Java and Lua parse too.
      std::string text((std::istreambuf_iterator<char>(shared)), std::istreambuf_iterator<char>());
      assert(parse_lock(text, std::string(32, 'a')) == 1760000000000ull && "The shared lock fixture parses");
    }
    assert(parse_lock(lock_file("4", "none", "4"), Run) == 0 && "A release gives none");
    assert(parse_lock(lock_file("4", "1.000 2.000 3.000", "5"), Run) == 0 && "A torn file gives none");
    assert(parse_lock("", Run) == 0);
  }
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
    assert(stellar_shared::plain<uint32_t>(slot, 24) == 4 && "Format 4 carries the adapter's report");
    assert(stellar_shared::plain<uint32_t>(slot, 28) == 2 && stellar_shared::plain<uint32_t>(slot, 32) == 54);
    assert(stellar_shared::plain<uint32_t>(slot, 64) == 7 && stellar_shared::plain<uint32_t>(slot, 68) == (WorldValid | MorphsValid));
    assert(stellar_shared::plain<float>(slot, 128 + 4 * 4) == 100);
    assert(stellar_shared::plain<uint32_t>(slot, 36) == 1 && stellar_shared::plain<float>(slot, 44) == 135 &&
           "The controller rotation travels in the slot header");
    assert(stellar_shared::plain<float>(slot, 128 + 4 * (42 + 10)) == 0.25f && "Follower morphs follow its world transform");
    assert(stellar_shared::load32(base, 320) == 0 && stellar_shared::load32(base, StatsState) == 0 &&
           "Without the pinned game world the gauges and stats stay unreadable");
    for (uint32_t i = 0; i < EvadeWords; i++)
      assert(stellar_shared::plain<uint32_t>(base, 344 + 4 * i) == 0 && "Nor are evades counted");
    assert(stellar_shared::plain<uint32_t>(base, ShotsAt) == 0 && "Nor shots");
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
    assert(controller_status() == (RotationValid | LookApplied | InputGranted) && inputs.size() == 1 &&
           inputs[0].first == 'y' && std::fabs(inputs[0].second - 0.15f * 135 / 2.5f) < 1e-4f &&
           "Turned through the pawn's look input");
    assert(stellar_shared::load32(base, 508) == 0 && stellar_shared::plain<uint32_t>(base, 492) == MirrorOff &&
           std::all_of(base + 1036, base + Header, [](uint8_t b) { return b == 0; }) &&
           "Without the pinned game world, no actor stats and no mirror");
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
    assert(controller_status() == (RotationValid | InputGranted) && inputs.empty() && "A stale look is ignored");
    look(95, 30, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    assert(inputs.empty() && "Pitch beyond the poles is ignored");
    look(10, 30, stellar_shared::wall_now(), 0);
    on_gameplay_poll(Run);
    assert(inputs.empty() && "An inactive look is ignored");
    // With EVE's boom on the pawn, Minecraft's pitch steers the camera dolly and pitch input stops.
    live.attach();
    auto camera_pitch = [&]() {
      return stellar_shared::plain<float>(base + Header + SlotBytes * (stellar_shared::load32(base, 128) & 3), 52);
    };
    look(-25, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    live.arm(live.alpha(), -16); // The arm's update after that write.
    look(-15, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    assert(controller_status() == (RotationValid | LookApplied | PitchSynced | InputGranted) && camera_pitch() == -16 &&
           std::fabs(live.alpha() - (0.5f - 7 * DollyStep)) < 1e-6f && !inputs.empty() &&
           std::none_of(inputs.begin(), inputs.end(), [](auto &i) { return i.first == 'p'; }) &&
           "The dolly takes the pitch; yaw still goes through look input; the slot carries the arm's pitch");
    auto steered = live.alpha();
    look(-15, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll(Run);
    assert(controller_status() == (RotationValid | LookApplied | PitchSynced | InputGranted) && live.alpha() == steered &&
           std::none_of(inputs.begin(), inputs.end(), [](auto &i) { return i.first == 'p'; }) &&
           "Without an arm update since, the dolly holds and pitch input stays off");
    assert(stellar_shared::plain<uint32_t>(base + Header + SlotBytes * (stellar_shared::load32(base, 128) & 3), 56) == DollyNone &&
           "A synced frame names no refusal");
    live.arm(live.alpha(), -17);
    inputs.clear();
    live.pawn.bytes[0x1528] = 1; // TPS aim: Stellar Blade's view sets the dolly and pitch input turns the aim.
    look(-5, -90, stellar_shared::wall_now(), 1);
    auto held = live.alpha();
    on_gameplay_poll(Run);
    assert(controller_status() == (RotationValid | LookApplied | InputGranted) && camera_pitch() == 0 &&
           live.alpha() == held && inputs.size() == 2 && inputs[1].first == 'p' &&
           std::fabs(inputs[1].second - 10 / -2.5f) < 1e-4f && "Where the dolly is not the camera's, pitch motion returns");
    auto dolly_refusal = [&]() {
      return stellar_shared::plain<uint32_t>(base + Header + SlotBytes * (stellar_shared::load32(base, 128) & 3), 56);
    };
    assert(dolly_refusal() == DollyAim && "The slot names why the dolly was left alone");
    live.pawn.bytes[0x1528] = 0;
    look(10, 30, stellar_shared::wall_now(), 0);
    on_gameplay_poll(Run);
    assert(controller_status() == (RotationValid | InputGranted) && camera_pitch() == 0 && live.alpha() == held &&
           "An inactive look leaves the dolly alone");
    look(-5, -90, stellar_shared::wall_now(), 1);
    on_gameplay_poll();
    assert(controller_status() == RotationValid && camera_pitch() == 0 && live.alpha() == held &&
           "Without forwarded input the dolly is left alone");
    on_gameplay_poll(std::string(32, '9'));
    assert(controller_status() == RotationValid && camera_pitch() == 0 && live.alpha() == held &&
           "Another run's input cannot set it");
    look(-5, -90, stellar_shared::wall_now() - 1000, 1);
    on_gameplay_poll(Run);
    assert(controller_status() == (RotationValid | InputGranted) && camera_pitch() == 0 && live.alpha() == held && "Nor a stale look");
    // A camera whose yaw stays far for FarPolls polls gets no more look input; the dolly is still set (status 5).
    for (uint32_t poll = 0; poll < FarPolls; poll++) {
      look(-17, -90, stellar_shared::wall_now(), 1);
      on_gameplay_poll(Run);
    }
    assert(controller_status() == (RotationValid | PitchSynced | InputGranted) && camera_pitch() == -17 && live.alpha() == held &&
           "Yaw follow stopped, the dolly still set");
    inputs.clear();
    std::memset(live.pawn.bytes + 0x5c0, 0, 8);
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
