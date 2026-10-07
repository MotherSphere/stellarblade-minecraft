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
constexpr uint32_t MaxComponents = 16, MaxBones = 256, MaxMorphs = 1024, MaxParameters = 32;
// Active effects per published slot, each an effect index then its world transform (11 floats).
constexpr uint32_t MaxEffects = 8, EffectFloats = 11, MaxEffectAssets = 32;
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
// One scalar (1 float) or vector (4 floats) parameter of a component's dynamic
// material instance: the TArray of FScalarParameterValue or FVectorParameterValue
// at offset, the element at index, whose name has this FName comparison index.
struct Parameter {
  uint64_t object = 0, cls = 0;
  uint32_t offset = 0, index = 0, floats = 0;
  int32_t name = 0;
};
// Particle effects (Niagara and Cascade components) attached to the components:
// USceneComponent::AttachChildren, UNiagaraComponent::Asset and
// UParticleSystemComponent::Template offsets, UActorComponent::bIsActive's byte
// and mask, both component classes and the effect assets Minecraft draws.
struct Effects {
  uint32_t children = 0, asset = 0, templ = 0, active_byte = 0;
  uint8_t active_mask = 0;
  uint64_t niagara = 0, particle = 0;
  std::vector<uint64_t> assets;
};
struct Table {
  std::string run;
  uint64_t generation = 0;
  std::optional<Controller> controller;
  std::vector<Component> components;
  std::vector<Parameter> parameters;
  std::optional<Effects> effects;
  // 10 world + 10 per bone + 1 per morph, per component, then the parameters'
  // floats, then MaxEffects effect records when effects are configured.
  uint32_t floats = 0;
};
std::optional<Table> parse_components(std::string_view);
// The newer valid table of two alternating Lua slots for this run.
std::optional<Table> select_components(std::string_view slot0,
                                       std::string_view slot1,
                                       std::string_view run);

// Engine memory of the pinned build. Reads must run on the game thread, where
// garbage collection cannot free objects concurrently.
struct Engine {
  uintptr_t objects = 0;     // FUObjectArray (GUObjectArray).
  uintptr_t boom_vtable = 0; // USBSpringArmComponent's virtual table (image + BoomVtableRva), 0 if unknown.
};
// Copies one component into out (10 + 10 * bones + morphs floats, zeroed when
// invalid) and returns its status bits. The object must still be the live,
// non-pending-kill object of the published class with the published mesh.
uint32_t read_component(const Engine &, const Component &, float *out);
// Copies a material parameter; false (and zeros) unless the instance is live
// with its class and the element still carries the published name.
bool read_parameter(const Engine &, const Parameter &, float *out);
// Writes MaxEffects records: for each active effect component attached to a
// live published component, the index of its asset in Effects::assets and its
// world transform; unused records carry index -1 and zeros. Returns the count.
uint32_t read_effects(const Engine &, const Table &, float *out);
// Copies the controller's Pitch, Yaw and Roll; false (and zeros) unless live.
bool read_controller(const Engine &, const Controller &, float *out);
// Turns the camera by pitch and yaw degrees through the pawn's
// AddControllerPitchInput and AddControllerYawInput, the path of Stellar
// Blade's own mouse look, if the pawn is the live object of its published
// class and its virtual table lies in the game image.
bool turn_controller(const Engine &, const Controller &, float pitch, float yaw);
// Stellar Blade's camera pitch is its camera dolly, not ControlRotation: while
// EVE's boom takes its rotation from the dolly, pitch input only moves the
// boom's CameraDollyAlpha (0 looks down from above her, 1 up from her hips) and
// the spring arm rebuilds the camera from it at every tick. steer_dolly moves
// that alpha by DollyStep per degree from the arm's last pitch to look_pitch
// (Unreal degrees), clamped to 0..1, and cancels the game's own dolly blend as
// its pitch input does; camera then receives the arm's pitch read before the
// write. It writes only into the live boom (by virtual table and owner) of the
// published live pawn, and only while the arm's next pitch follows that alpha
// alone: dolly used, with a track, in dolly mode on the default track with no
// spline or mode blend, without rotation lag, outside TPS aim and fishing (whose
// views set the dolly and take pitch input as aim) and while the slope camera
// does not set the alpha after the arm. False, with nothing written, otherwise:
// pitch input then carries Minecraft's motion. The boom is a final class; its
// constructor (0x2404d80) stores this table. last keeps the previous write: a
// write that moved the alpha always changes the arm's pitch at its next update,
// so a poll that finds both unchanged (the arm has not ticked: Stellar Blade
// paused or hitching) holds, true with nothing written, instead of adding the
// same gap again; a refusal clears it.
constexpr uintptr_t BoomVtableRva = 0x5ce42a0;
// About half the gap per poll at ~150 degrees per unit alpha (the track gives 117-192).
constexpr float DollyStep = 1.0f / 300;
struct DollyWrite {
  float alpha = 0, arm = 0; // The alpha written and the arm's raw pitch it answered.
  bool moved = false;       // The write moved the alpha by more than MovedAlpha.
};
constexpr float MovedAlpha = 1e-4f; // At least about 0.01 degree of arm pitch.
bool steer_dolly(const Engine &, const Controller &, float look_pitch, float &camera, DollyWrite &last);

// EVE's Beta and Burst gauges, kept by Stellar Blade only in its native player
// actor (FSBActor, not a UObject). world is the pinned build's game world
// pointer (the global at GameWorldRva). Writes Beta, its maximum, Burst, its
// maximum and the Burst unlock stat; false unless the player actor is spawned
// and every value is finite, within 0..GaugeLimit, the gauges within their
// maximums.
constexpr uintptr_t GameWorldRva = 0x7032570;
constexpr uint32_t GaugeFloats = 5;
constexpr float GaugeLimit = 100000;
bool read_gauges(uintptr_t world, float *out);
// Minecraft's lock-on for EVE's evade. Each skill command node keeps
// FSBSkillCommandTableProperty.bLockOn at +0x68, checked against the
// simulation's own lock target (0 any, 1 none, 2 one, other values never);
// Minecraft's lock never sets that target, so a locked evade took the plain
// row. locked: EVE's lock-gated evade nodes go 2 -> 0 and 1 -> 3; unlocked:
// those in her tree get their value back, those out of it once they return
// (while her actor lives). Returns the nodes still changed. world as for
// read_gauges.
uint32_t patch_evades(uintptr_t world, bool locked);
// EVE's evade starts as her command set records them. A skill whose row is in
// her tree makes its node current (+0x70) and stores its start time (f32,
// +0x78; 0x1d2d540, store at 0x1d2d5bd); one whose row is not zeroes the time
// and keeps the node (0x1d2e335). The root comes back with time 0 when the
// node's combo window ends (+0xac: its children's longest InputTime +
// InputDuration, kept by the tick 0x1d2c520, reset at 0x1d2c895) or on other
// resets (0x1c52b72). A real start (a new non-zero time) of a node whose
// command is Evade counts: a double dodge when its byte at +0xb0 is clear (the
// builder 0x1d285e0 sets it for her stance's own rows, store at 0x1d288e7, not
// for combo children), else a first dodge, which covers every Evade row of her
// stance (air evade, fast getups and Evade-command attacks too). A first dodge
// whose time goes to 0 more than EvadeMarginMs before its window's end counts
// as cut. counts, the header's words after the gauges: first, double, cut
// while unlocked, the same while locked, then the caller's word (the nodes the
// latest locked poll's patch_evades left changed). world as for read_gauges;
// wall in milliseconds.
constexpr uint32_t EvadeWords = 7;
struct EvadeStarts {
  uintptr_t node = 0;
  uint32_t start = 0;  // The start time's bits.
  uint64_t opened = 0; // Wall time of the newest first dodge while its window is open.
  bool opened_locked = false;
  uint32_t counts[EvadeWords] = {};
};
// ponytail: one fixed margin for both polls' frame lag; below about 10 fps a window that ended on time may count as cut.
constexpr uint64_t EvadeMarginMs = 200;
void count_evades(uintptr_t world, bool locked, uint64_t wall, EvadeStarts &);
// Minecraft's lock-on for EVE's battle stance. Stellar Blade's actor update
// (0x1a57de0) keeps her battle mode (byte +0xB58) while a hold applies (its
// own lock target +0x630, enemies targeting her, a melee or battle-switching
// skill, ActorState_BattleMode, the pin +0x2646), stamping +0x558 with the turn
// clock at each such update (0x1a5832a); once the stamp is
// BattleStanceCancelTime (3 s) old it ends battle mode and plays her sheathe
// (0x1a582f0). Minecraft's lock never sets that target, so 3 s after an attack
// she sheathed while locked. hold_battle stamps it like a held update, only
// while she is in battle mode and only forward. The turn clock is the turn
// manager at the global TurnClockRva (virtual table TurnClockVtableRva,
// seconds since the game mode (re)started, 0x1c72138, as an f32 at +0xc,
// advanced by the game-mode tick 0x1c71c3a). Besides that update only entering
// battle (0x1a7d070), the TPS cancel (0x1a8c8d3/0x1a8c8f4, which only moves an
// old stamp forward) and the actor reset (0x1a6a668) touch the stamp. Stopping is the undo: Stellar Blade
// sheathes BattleStanceCancelTime after the last stamp, as after its own
// unlock. world as for read_gauges; clock the global's value, vtable the
// image's TurnClockVtableRva. True if it stamped.
constexpr uintptr_t TurnClockRva = 0x7032560, TurnClockVtableRva = 0x5bf9120;
bool hold_battle(uintptr_t world, uintptr_t clock, uintptr_t vtable);
// eve-lock.txt (SBMC_LOCK 1): its TIME while it names a target, else 0.
uint64_t parse_lock(std::string_view text);
// Look input for one gameplay poll: Minecraft's camera motion since the last
// poll, plus for yaw a share of the remaining gap to the controller's rotation,
// so the game's camera follows like it follows a mouse. The controller's pitch
// does not track Stellar Blade's camera pitch, so pitch gets the motion alone
// (used only while steer_dolly cannot set the camera's pitch). Degrees.
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
Turn follow(Follow &, float look_pitch, float look_yaw, float yaw);
// Bits of the controller status word in a slot.
// InputGranted: this stream's run owns forwarded input this frame (Stellar Blade grants it: not paused, no cutscene).
constexpr uint32_t RotationValid = 1, LookApplied = 2, PitchSynced = 4, InputGranted = 8;

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
  // rotation: the controller's Pitch, Yaw, Roll, then the camera's pitch
  // (zero unless look carries PitchSynced); look: LookApplied, PitchSynced,
  // InputGranted.
  // evades: EvadeWords words for the header, or none.
  bool publish(uint64_t generation, const uint32_t *status, uint32_t count,
               const float *payload, uint32_t floats, const float *rotation,
               uint32_t look, uint64_t wall, uint32_t read_us,
               const float *gauges = nullptr, const uint32_t *evades = nullptr);
  // Minecraft's camera (Unreal Pitch, Yaw in degrees), if active, fresh
  // within 200 ms and finite; pitch within [-90, 90], yaw in [0, 360).
  bool look(uint64_t wall, float &pitch, float &yaw) const;
  void fail(uint32_t);
  void stop();
};

// Adapter entry points. initialize receives GUObjectArray's address and the
// boom's virtual table (image + BoomVtableRva; 0 never sets the dolly). The
// worker passes the request file, both component slots and Minecraft's lock
// file; the verified gameplay XInput poll gives EVE the lock-on evades while
// Minecraft locks and owner (the run whose input is forwarded) is set and keeps
// her in the battle mode an attack started meanwhile, counts her evade starts,
// reads the controller, turns the game's camera toward Minecraft's when owner
// is this stream's run, then reads the components and publishes. None of them
// throws.
void initialize(uintptr_t objects, uintptr_t boom_vtable = 0);
void poll_files(const std::wstring &directory, std::string_view request,
                std::string_view slot0, std::string_view slot1, uint64_t wall,
                std::string_view lock = {});
void on_gameplay_poll(std::string_view owner = {});
} // namespace stellar_pose
