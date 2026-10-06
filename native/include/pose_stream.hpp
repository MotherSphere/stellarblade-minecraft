#pragma once
// EVE pose stream: Lua names the skeletal mesh components, the game-thread
// gameplay poll copies their transforms, Minecraft reads a v1 pose mapping.
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace stellar_pose {
constexpr uint32_t MaxComponents = 16, MaxBones = 256, MaxMorphs = 1024;
constexpr uint32_t Header = 4096, SlotBytes = 65536, SlotHeader = 128;
constexpr uint32_t Capacity = (SlotBytes - SlotHeader) / 4; // Payload floats.
constexpr uint32_t TotalBytes = Header + 3 * SlotBytes;
// Status bits per component in a published slot.
constexpr uint32_t WorldValid = 1, BonesValid = 2, MorphsValid = 4;

struct Request {
  std::string run;
  uint64_t time = 0;
  bool active = false;
};
std::optional<Request> parse_request(std::string_view);

struct Component {
  uint64_t object = 0, cls = 0, mesh = 0;
  uint32_t bones = 0, morphs = 0;
};
// The local player controller (ControlRotation Pitch, Yaw, Roll at offset),
// its pawn, and the controller's InputYawScale and InputPitchScale.
struct Controller {
  uint64_t object = 0, cls = 0, pawn = 0, pawn_cls = 0;
  uint32_t offset = 0;
  float yaw_scale = 0, pitch_scale = 0;
};
struct Table {
  std::string run;
  uint64_t generation = 0;
  std::optional<Controller> controller;
  std::vector<Component> components;
  uint32_t floats = 0; // 10 world + 10 per bone + 1 per morph, per component.
};
std::optional<Table> parse_components(std::string_view);
// The newer valid table of two alternating Lua slots for this run.
std::optional<Table> select_components(std::string_view slot0,
                                       std::string_view slot1,
                                       std::string_view run);

// Engine memory of the pinned build. Reads must run on the game thread, where
// garbage collection cannot free objects concurrently.
struct Engine {
  uintptr_t objects = 0; // FUObjectArray (GUObjectArray).
};
// Copies one component into out (10 + 10 * bones + morphs floats, zeroed when
// invalid) and returns its status bits. The object must still be the live,
// non-pending-kill object of the published class with the published mesh.
uint32_t read_component(const Engine &, const Component &, float *out);
// Copies the controller's Pitch, Yaw and Roll; false (and zeros) unless live.
bool read_controller(const Engine &, const Controller &, float *out);
// Turns the camera by pitch and yaw degrees through the pawn's
// AddControllerPitchInput and AddControllerYawInput, the path of Stellar
// Blade's own mouse look, if the pawn is the live object of its published
// class and its virtual table lies in the game image.
bool turn_controller(const Engine &, const Controller &, float pitch, float yaw);
// Look input for one gameplay poll: Minecraft's camera motion since the last
// poll plus a share of the remaining gap to the controller's rotation, so the
// game's camera follows like it follows a mouse. Degrees.
struct Follow {
  bool previous = false;
  float pitch = 0, yaw = 0;
  uint32_t apart = 0; // Consecutive polls with a yaw gap above FarDegrees.
};
constexpr float FollowGain = 0.15f, FarDegrees = 30, MaxTurn = 45;
constexpr uint32_t FarPolls = 60;
struct Turn {
  float pitch = 0, yaw = 0;
  bool engaged = false; // False after FarPolls far polls: the camera is not following.
};
Turn follow(Follow &, float look_pitch, float look_yaw, float pitch, float yaw);
// Bits of the controller status word in a slot.
constexpr uint32_t RotationValid = 1, LookApplied = 2;

// Producer side of eve-pose-<RUN>.bin. The caller serializes every method.
class Producer {
  struct Impl;
  std::unique_ptr<Impl> impl_;

public:
  Producer();
  ~Producer();
  Producer(const Producer &) = delete;
  Producer &operator=(const Producer &) = delete;
  bool open(const std::wstring &directory, const Request &, uint32_t pid,
            uint64_t wall);
  void heartbeat(uint64_t wall);
  bool active(uint64_t wall) const;
  const std::string &run() const;
  bool publish(uint64_t generation, const uint32_t *status, uint32_t count,
               const float *payload, uint32_t floats, const float *rotation,
               bool applied, uint64_t wall, uint32_t read_us);
  // Minecraft's camera (Unreal Pitch, Yaw in degrees), if active, fresh
  // within 200 ms and finite; pitch within [-90, 90], yaw in [0, 360).
  bool look(uint64_t wall, float &pitch, float &yaw) const;
  void fail(uint32_t);
  void stop();
};

// Adapter entry points. initialize receives GUObjectArray's address. The
// worker passes the request file and both component slots; the verified
// gameplay XInput poll reads the controller, turns the game's camera toward
// Minecraft's when owner (the run whose input is forwarded) is this stream's
// run, then reads the components and publishes. None of
// them throws.
void initialize(uintptr_t objects);
void poll_files(const std::wstring &directory, std::string_view request,
                std::string_view slot0, std::string_view slot1, uint64_t wall);
void on_gameplay_poll(std::string_view owner = {});
} // namespace stellar_pose
