#include "pose_stream.hpp"
#include "input.hpp"
#include "shared_memory.hpp"
#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <utility>
#ifndef _WIN32
#include <unistd.h>
#endif

namespace stellar_pose {
namespace {
// 2: component status bit 3 (Hidden); 3: the dolly refusal at slot byte 56 and the drone shot count at header byte ShotsAt.
constexpr uint32_t Slots = 3, Dirty = 4, Format = 3;
constexpr uint64_t Lease = 3000, LockLease = 600; // LockLease: Lua's own lock lease.
using stellar_input::detail::identity;
using stellar_input::detail::Lines;
using stellar_input::detail::number;
using stellar_shared::exchange32;
using stellar_shared::load32;
using stellar_shared::load64;
using stellar_shared::monotonic_now;
using stellar_shared::plain;
using stellar_shared::put;
using stellar_shared::store64;

// USkeletalMeshComponent and FUObjectArray layout of the pinned 4.26 build,
// taken from execSnapshotPose, USkeletalMeshComponent::SnapshotPose,
// execK2_GetComponentToWorld and inlined FUObjectArray::IndexToObject.
constexpr size_t InternalIndex = 0x0c, ClassPrivate = 0x10, WorldTransform = 0x1f0,
                 SpaceTransforms = 0x5a8, SkeletalMesh = 0x568,
                 ReadTransforms = 0x674, MorphWeights = 0x738;
// GUObjectArray: ObjObjects at 0x10 (chunk table pointer first), NumElements
// at ObjObjects + 0x14; 64K items per chunk, 24-byte FUObjectItem.
constexpr size_t ChunkTable = 0x10, ElementCount = 0x24, ItemBytes = 24,
                 ItemFlags = 8;
constexpr int32_t PendingKill = 1 << 29, Unreachable = 1 << 28;
// FScalarParameterValue (FMaterialParameterInfo, float, FGuid) and
// FVectorParameterValue (FMaterialParameterInfo, FLinearColor, FGuid) without
// editor data; FMaterialParameterInfo starts with the FName.
constexpr size_t ScalarStride = 36, VectorStride = 48, ParameterValue = 16;

bool fresh(uint64_t value, uint64_t now, uint64_t lease = Lease) {
  return value && (value <= now ? now - value <= lease : value - now <= 100);
}
bool middle_valid(uint32_t state) {
  return (state & ~7u) == 0 && (state & 3u) < Slots;
}
std::vector<std::string_view> split(std::string_view line) {
  std::vector<std::string_view> parts;
  size_t p = 0;
  while (p <= line.size()) {
    size_t e = line.find(' ', p);
    if (e == std::string_view::npos)
      e = line.size();
    parts.push_back(line.substr(p, e - p));
    if (e == line.size())
      break;
    p = e + 1;
  }
  return parts;
}
bool address(std::string_view s, uint64_t &out) {
  if (s.size() != 16)
    return false;
  out = 0;
  for (char c : s) {
    int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    if (digit < 0)
      return false;
    out = out << 4 | uint64_t(digit);
  }
  return out != 0 && (out & 7) == 0;
}
bool hex32(std::string_view s, uint64_t &out) {
  out = 0;
  for (char c : s) {
    int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
    if (digit < 0)
      return false;
    out = out << 4 | uint64_t(digit);
  }
  return !s.empty();
}
// InputYawScale or InputPitchScale: finite, nonzero, magnitude 0.01 to 100.
bool scale(std::string_view s, float &out) {
  auto end = s.data() + s.size();
  auto [last, error] = std::from_chars(s.data(), end, out);
  return error == std::errc() && last == end && std::isfinite(out) &&
         std::fabs(out) >= 0.01f && std::fabs(out) <= 100;
}
bool names(std::string_view line, std::string_view key, uint32_t index,
           uint32_t count) {
  auto parts = split(line);
  uint64_t n = 0;
  if (parts.size() != count + 2 || parts[0] != key || !number(parts[1], n) ||
      n != index)
    return false;
  for (size_t i = 2; i < parts.size(); i++)
    if (parts[i].empty())
      return false;
  return true;
}
template <class T> T at(uintptr_t where) {
  T value;
  std::memcpy(&value, reinterpret_cast<const void *>(where), sizeof value);
  return value;
}
bool alive(uintptr_t objects, uintptr_t object) {
  auto index = at<int32_t>(object + InternalIndex);
  if (index < 0 || index >= at<int32_t>(objects + ElementCount))
    return false;
  auto chunks = at<uintptr_t>(objects + ChunkTable);
  auto chunk = chunks ? at<uintptr_t>(chunks + 8 * uintptr_t(index >> 16)) : 0;
  if (!chunk)
    return false;
  auto item = chunk + ItemBytes * uintptr_t(index & 0xffff);
  return at<uintptr_t>(item) == object &&
         !(at<int32_t>(item + ItemFlags) & (PendingKill | Unreachable));
}
// USceneComponent::bVisible (byte 0x170, bit 5) and bHiddenInGame (0x171, bit 2)
// and AActor::bHidden (0x58, bit 5), Engine_classes.hpp:230, 235, 3220; the
// component's owner is UActorComponent's unreflected pointer at 0xb0 (read so
// at 0x24052d4).
constexpr size_t VisibleByte = 0x170, HiddenInGameByte = 0x171, ActorHiddenByte = 0x58, Owner = 0xb0;
constexpr uint8_t VisibleBit = 0x20, HiddenInGameBit = 0x04, ActorHiddenBit = 0x20;
// A live scene component Stellar Blade leaves out of its rendering, as
// USceneComponent::ShouldRender does in a game world: not visible, hidden in
// game, or owned by a live hidden actor.
bool hidden(uintptr_t objects, uintptr_t component) {
  auto owner = at<uintptr_t>(component + Owner);
  return !(at<uint8_t>(component + VisibleByte) & VisibleBit) ||
         (at<uint8_t>(component + HiddenInGameByte) & HiddenInGameBit) ||
         (owner && alive(objects, owner) && (at<uint8_t>(owner + ActorHiddenByte) & ActorHiddenBit));
}
// FTransform in memory: rotation xyzw, translation xyz + pad, scale xyz + pad.
void transform(uintptr_t source, float *out) {
  float raw[12];
  std::memcpy(raw, reinterpret_cast<const void *>(source), sizeof raw);
  std::memcpy(out, raw, 7 * sizeof(float));
  std::memcpy(out + 7, raw + 8, 3 * sizeof(float));
}
} // namespace

std::optional<Request> parse_request(std::string_view text) {
  Lines lines(text, 512);
  Request result;
  std::string state;
  if (!lines.literal("SBMC_POSE_REQUEST 1") || !lines.text("RUN", result.run) ||
      !identity(result.run) || !lines.num("TIME", result.time) ||
      !lines.text("STATE", state) || (state != "active" && state != "stopped") ||
      !lines.literal("END") || lines.at != lines.rows.size())
    return {};
  result.active = state == "active";
  return result;
}

std::optional<Table> parse_components(std::string_view text) {
  Lines lines(text, 65536);
  Table table;
  uint64_t count = 0;
  if (!lines.literal("SBMC_POSE_COMPONENTS 1") || !lines.text("RUN", table.run) ||
      !identity(table.run) || !lines.num("GENERATION", table.generation) ||
      !table.generation || !lines.text("SESSION", table.session) ||
      (table.session != "none" && !identity(table.session)) || lines.at >= lines.rows.size())
    return {};
  auto anchor = split(lines.rows[lines.at++]);
  bool none = anchor.size() == 2 && anchor[1] == "none";
  if (anchor.empty() || anchor[0] != "ANCHOR" || (!none && anchor.size() != 5))
    return {};
  if (lines.at >= lines.rows.size())
    return {};
  auto controller = split(lines.rows[lines.at++]);
  if (controller.size() == 8 && controller[0] == "CONTROLLER") {
    Controller c;
    uint64_t offset = 0;
    if (!address(controller[1], c.object) || !address(controller[2], c.cls) ||
        controller[3].size() != 8 || !hex32(controller[3], offset) || !offset ||
        offset >= 0x4000 || offset % 4 || !address(controller[4], c.pawn) ||
        !address(controller[5], c.pawn_cls) || !scale(controller[6], c.yaw_scale) ||
        !scale(controller[7], c.pitch_scale))
      return {};
    c.offset = static_cast<uint32_t>(offset);
    table.controller = c;
  } else if (controller.size() != 2 || controller[0] != "CONTROLLER" || controller[1] != "none")
    return {};
  if (!lines.num("COUNT", count) || !count || count > MaxComponents)
    return {};
  uint64_t floats = 0;
  for (uint32_t i = 1; i <= count; i++) {
    if (lines.at >= lines.rows.size())
      return {};
    auto f = split(lines.rows[lines.at++]);
    Component c;
    uint64_t index = 0, bones = 0, morphs = 0;
    if (f.size() != 10 || f[0] != "COMPONENT" || !number(f[1], index) ||
        index != i || !address(f[2], c.object) || !address(f[3], c.cls) ||
        !address(f[4], c.mesh) || !number(f[5], bones) || bones > MaxBones ||
        !number(f[6], morphs) || morphs > MaxMorphs || f[7].empty() ||
        f[8].empty() || f[9].empty())
      return {};
    c.bones = static_cast<uint32_t>(bones);
    c.morphs = static_cast<uint32_t>(morphs);
    if (c.bones && (lines.at >= lines.rows.size() ||
                    !names(lines.rows[lines.at++], "BONES", i, c.bones)))
      return {};
    if (c.morphs && (lines.at >= lines.rows.size() ||
                     !names(lines.rows[lines.at++], "MORPHS", i, c.morphs)))
      return {};
    floats += 10 + 10 * uint64_t(c.bones) + c.morphs;
    table.components.push_back(c);
  }
  uint64_t parameters = 0;
  if (!lines.num("PARAMETERS", parameters) || parameters > MaxParameters)
    return {};
  for (uint32_t i = 1; i <= parameters; i++) {
    if (lines.at >= lines.rows.size())
      return {};
    // PARAMETER index component slot name object class offset element nameIndex floats
    auto f = split(lines.rows[lines.at++]);
    Parameter q;
    uint64_t index = 0, component = 0, offset = 0, element = 0, name = 0, width = 0;
    if (f.size() != 11 || f[0] != "PARAMETER" || !number(f[1], index) || index != i ||
        !number(f[2], component) || !component || component > count || f[3].empty() ||
        f[4].empty() || !address(f[5], q.object) || !address(f[6], q.cls) ||
        f[7].size() != 8 || !hex32(f[7], offset) || !offset || offset >= 0x4000 ||
        offset % 8 || !number(f[8], element) || element >= 1024 || !number(f[9], name) ||
        !name || name > INT32_MAX || !number(f[10], width) || (width != 1 && width != 4))
      return {};
    q.offset = static_cast<uint32_t>(offset);
    q.index = static_cast<uint32_t>(element);
    q.name = static_cast<int32_t>(name);
    q.floats = static_cast<uint32_t>(width);
    floats += q.floats;
    table.parameters.push_back(q);
  }
  if (lines.at >= lines.rows.size())
    return {};
  // EFFECTS none, or EFFECTS children asset template activeByte activeMask niagaraClass particleClass count
  auto e = split(lines.rows[lines.at++]);
  if (e.size() == 9 && e[0] == "EFFECTS") {
    Effects fx;
    uint64_t children = 0, asset = 0, templ = 0, byte = 0, mask = 0, assets = 0;
    auto field = [](std::string_view s, uint64_t &out) { return s.size() == 8 && hex32(s, out) && out && out < 0x4000; };
    if (!field(e[1], children) || children % 8 || !field(e[2], asset) || asset % 8 || !field(e[3], templ) ||
        templ % 8 || !field(e[4], byte) || e[5].size() != 2 || !hex32(e[5], mask) || !mask ||
        !address(e[6], fx.niagara) || !address(e[7], fx.particle) || !number(e[8], assets) ||
        assets > MaxEffectAssets)
      return {};
    fx.children = uint32_t(children);
    fx.asset = uint32_t(asset);
    fx.templ = uint32_t(templ);
    fx.active_byte = uint32_t(byte);
    fx.active_mask = uint8_t(mask);
    for (uint32_t i = 1; i <= assets; i++) {
      if (lines.at >= lines.rows.size())
        return {};
      auto a = split(lines.rows[lines.at++]);
      uint64_t index = 0, object = 0;
      if (a.size() != 4 || a[0] != "EFFECT" || !number(a[1], index) || index != i ||
          !address(a[2], object) || a[3].empty())
        return {};
      fx.assets.push_back(object);
    }
    floats += MaxEffects * EffectFloats;
    table.effects = std::move(fx);
  } else if (e.size() != 2 || e[0] != "EFFECTS" || e[1] != "none")
    return {};
  if (floats > Capacity || !lines.end(table.generation))
    return {};
  table.floats = static_cast<uint32_t>(floats);
  return table;
}

std::optional<Table> select_components(std::string_view slot0,
                                       std::string_view slot1,
                                       std::string_view run) {
  auto a = parse_components(slot0), b = parse_components(slot1);
  if (a && a->run != run)
    a.reset();
  if (b && b->run != run)
    b.reset();
  if (a && b)
    return a->generation >= b->generation ? a : b;
  return a ? a : b;
}

uint32_t read_component(const Engine &engine, const Component &c, float *out) {
  std::memset(out, 0, sizeof(float) * (10 + 10 * size_t(c.bones) + c.morphs));
  auto object = static_cast<uintptr_t>(c.object);
  if (!engine.objects || !alive(engine.objects, object) ||
      at<uint64_t>(object + ClassPrivate) != c.cls ||
      at<uint64_t>(object + SkeletalMesh) != c.mesh)
    return 0;
  uint32_t status = WorldValid | (hidden(engine.objects, object) ? Hidden : 0);
  transform(object + WorldTransform, out);
  if (c.bones) {
    auto read = at<int32_t>(object + ReadTransforms);
    auto array = object + SpaceTransforms + 16 * uintptr_t(read);
    auto data = read == 0 || read == 1 ? at<uintptr_t>(array) : 0;
    if (data && at<int32_t>(array + 8) == int32_t(c.bones)) {
      for (uint32_t k = 0; k < c.bones; k++)
        transform(data + 48 * uintptr_t(k), out + 10 + 10 * k);
      status |= BonesValid;
    }
  }
  if (c.morphs) {
    auto data = at<uintptr_t>(object + MorphWeights);
    if (data && at<int32_t>(object + MorphWeights + 8) == int32_t(c.morphs)) {
      std::memcpy(out + 10 + 10 * c.bones, reinterpret_cast<const void *>(data),
                  sizeof(float) * c.morphs);
      status |= MorphsValid;
    }
  }
  return status;
}

bool read_parameter(const Engine &engine, const Parameter &q, float *out) {
  std::memset(out, 0, sizeof(float) * q.floats);
  auto object = static_cast<uintptr_t>(q.object);
  if (!engine.objects || !alive(engine.objects, object) ||
      at<uint64_t>(object + ClassPrivate) != q.cls)
    return false;
  auto data = at<uintptr_t>(object + q.offset);
  auto count = at<int32_t>(object + q.offset + 8);
  if (!data || count <= int32_t(q.index) || count > 1024)
    return false;
  auto element = data + uintptr_t(q.index) * (q.floats == 1 ? ScalarStride : VectorStride);
  if (at<int32_t>(element) != q.name)
    return false;
  float values[4];
  std::memcpy(values, reinterpret_cast<const void *>(element + ParameterValue), sizeof(float) * q.floats);
  for (uint32_t k = 0; k < q.floats; k++)
    if (!std::isfinite(values[k]))
      return false;
  std::memcpy(out, values, sizeof(float) * q.floats);
  return true;
}

uint32_t read_effects(const Engine &engine, const Table &table, float *out) {
  for (uint32_t r = 0; r < MaxEffects; r++) {
    std::memset(out + r * EffectFloats, 0, sizeof(float) * EffectFloats);
    out[r * EffectFloats] = -1;
  }
  if (!table.effects || !engine.objects)
    return 0;
  const auto &fx = *table.effects;
  uint32_t count = 0;
  for (const auto &c : table.components) {
    auto parent = static_cast<uintptr_t>(c.object);
    if (!alive(engine.objects, parent) || at<uint64_t>(parent + ClassPrivate) != c.cls)
      continue;
    auto data = at<uintptr_t>(parent + fx.children);
    auto children = at<int32_t>(parent + fx.children + 8);
    if (!data || children <= 0 || children > 256)
      continue;
    for (int32_t k = 0; k < children && count < MaxEffects; k++) {
      auto child = at<uintptr_t>(data + 8 * uintptr_t(k));
      if (!child || !alive(engine.objects, child))
        continue;
      auto cls = at<uint64_t>(child + ClassPrivate);
      if ((cls != fx.niagara && cls != fx.particle) ||
          !(at<uint8_t>(child + fx.active_byte) & fx.active_mask) || hidden(engine.objects, child))
        continue;
      auto asset = at<uint64_t>(child + (cls == fx.niagara ? fx.asset : fx.templ));
      auto found = std::find(fx.assets.begin(), fx.assets.end(), asset);
      if (!asset || found == fx.assets.end())
        continue;
      out[count * EffectFloats] = float(found - fx.assets.begin());
      transform(child + WorldTransform, out + count * EffectFloats + 1);
      count++;
    }
  }
  return count;
}

bool read_controller(const Engine &engine, const Controller &c, float *out) {
  std::memset(out, 0, 3 * sizeof(float));
  auto object = static_cast<uintptr_t>(c.object);
  if (!engine.objects || !alive(engine.objects, object) ||
      at<uint64_t>(object + ClassPrivate) != c.cls)
    return false;
  std::memcpy(out, reinterpret_cast<const void *>(object + c.offset), 3 * sizeof(float));
  return true;
}

// APawn virtual table slots of AddControllerPitchInput and
// AddControllerYawInput in the pinned build (execAddControllerPitchInput,
// execAddControllerYawInput).
constexpr size_t AddPitchSlot = 0x798, AddYawSlot = 0x7a0;
static bool in_image(uintptr_t where) {
#ifdef _WIN32
  // The executable's image: base and SizeOfImage from its PE header.
  static const auto base = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
  static const auto size = at<uint32_t>(base + at<int32_t>(base + 0x3c) + 0x50);
  return where >= base && where - base < size;
#else
  return where != 0;
#endif
}
bool turn_controller(const Engine &engine, const Controller &c, float pitch, float yaw) {
  auto pawn = static_cast<uintptr_t>(c.pawn);
  if (!engine.objects || !alive(engine.objects, pawn) ||
      at<uint64_t>(pawn + ClassPrivate) != c.pawn_cls)
    return false;
  auto table = at<uintptr_t>(pawn);
  if (!in_image(table))
    return false;
  using Input = void (*)(void *, float); // this in the first register, the value in the second.
  auto add_pitch = at<uintptr_t>(table + AddPitchSlot), add_yaw = at<uintptr_t>(table + AddYawSlot);
  if (!in_image(add_pitch) || !in_image(add_yaw))
    return false;
  // APlayerController::AddYawInput multiplies by InputYawScale (likewise pitch).
  if (yaw != 0)
    reinterpret_cast<Input>(add_yaw)(reinterpret_cast<void *>(pawn), yaw / c.yaw_scale);
  if (pitch != 0)
    reinterpret_cast<Input>(add_pitch)(reinterpret_cast<void *>(pawn), pitch / c.pitch_scale);
  return true;
}

// ASBCharacter (SB_classes.hpp:6109, 6731, 6785, 6826): CameraBoom 0x5c0,
// bTPSMode 0x1528 and FishingModeType 0x1690 (their views set the dolly at
// 0x23af115; their pitch input turns an aim, 0x2050576, 0x205138e), and in
// MovementCheckInfo (0x1770; SB_structs.hpp:10354-10360) the slope camera:
// while CameraSlopeMoveType 0x1b5c and bRunCameraSlopeMoveAlpha 0x1b5d are set
// and CurrentCameraSlopeMoveRunningTime 0x1b64 exceeds CameraSlopeRunTimeFromStart
// 0x1b6c (0x2028900), the boom's tick sets the alpha after the arm update
// (0x24074c4-0x24074f7), and only nonzero pitch input stops it (0x2051ab9).
// USBSpringArmComponent (SB_classes.hpp:25472-25512): CameraDollyAlpha 0x2f0,
// bUseCameraDolly 0x2f4 and CameraDollyTrackData 0x2f8 (without either the arm
// does not place the camera from the alpha, 0x24055b0, 0x2408758),
// bLastUseCharacterControllerRot 0x370 (the arm's dolly-blocked test 0x2406180,
// cached each tick at 0x240869f), RemainBlendCamera 0x374 (started when 0x370
// flips, 0x24086de; while above 0 the arm eases its rotation from the last one,
// 0x2408abe), RemainBlendDollyAlpha and BlendDollyAlphaDuration 0x3c0,
// BlendTargetDollyAlpha 0x3c8 (0, 0 and -1 end a blend, as 0x2406140 does at
// 0x2406166), DollyBlends' count 0x3d8 and CurrentDollyMode 0x3e0 (another
// spline, or a blend from an earlier one, places the camera: 0x2405070,
// 0x2407954). USpringArmComponent: bEnableCameraRotationLag, bit 6 of 0x284
// (Engine_classes.hpp:1257; passed at 0x24070a3, eases the rotation at
// 0x2408cf4). Unreflected: the component's owner at 0xb0 (read so at 0x24052d4)
// and PreviousDesiredRot at 0x2c0, the arm's final rotation, stored at 0x24090ce
// by each UpdateDesiredArmLocation (0x24085e0), which first evaluates the dolly
// at CameraDollyAlpha (0x24087c6) and in dolly mode pitches the camera from its
// arm location toward the pivot (0x24087f7-0x240893a).
constexpr size_t CameraBoom = 0x5c0, TPSMode = 0x1528, FishingMode = 0x1690, SlopeType = 0x1b5c,
                 SlopeRun = 0x1b5d, SlopeTime = 0x1b64, SlopeStart = 0x1b6c,
                 ArmFlags = 0x284, ArmRotation = 0x2c0, DollyAlpha = 0x2f0, UseDolly = 0x2f4,
                 DollyTrack = 0x2f8, ControllerRotation = 0x370, CameraBlend = 0x374,
                 DollyBlend = 0x3c0, DollyBlendTarget = 0x3c8, DollyBlends = 0x3d8, DollyMode = 0x3e0;
constexpr uint8_t RotationLag = 0x40;
bool steer_dolly(const Engine &engine, const Controller &c, float look_pitch, float &camera,
                 DollyWrite &last, uint32_t *refusal) {
  auto previous = last;
  last = {};
  auto refuse = [&](uint32_t why) {
    if (refusal)
      *refusal = why;
    return false;
  };
  if (refusal)
    *refusal = DollyNone;
  auto pawn = static_cast<uintptr_t>(c.pawn);
  if (!engine.objects || !engine.boom_vtable || !alive(engine.objects, pawn) ||
      at<uint64_t>(pawn + ClassPrivate) != c.pawn_cls)
    return refuse(DollyNoBoom);
  auto boom = at<uintptr_t>(pawn + CameraBoom);
  if (!boom || !alive(engine.objects, boom) || at<uintptr_t>(boom) != engine.boom_vtable ||
      at<uintptr_t>(boom + Owner) != pawn)
    return refuse(DollyNoBoom);
  // The arm's next pitch must come from this alpha alone, along the default
  // track, with no easing and no other writer after the arm reads it.
  if (!at<uint8_t>(boom + UseDolly) || !at<uint64_t>(boom + DollyTrack))
    return refuse(DollyUnused);
  if (at<uint8_t>(boom + ControllerRotation))
    return refuse(DollyController);
  if (!(at<float>(boom + CameraBlend) <= 0))
    return refuse(DollyEasing);
  if (at<uint8_t>(boom + ArmFlags) & RotationLag)
    return refuse(DollyLag);
  if (at<int32_t>(boom + DollyMode) != -1 || at<int32_t>(boom + DollyBlends))
    return refuse(DollySpline);
  if (at<uint8_t>(pawn + TPSMode))
    return refuse(DollyAim);
  if (at<uint8_t>(pawn + FishingMode))
    return refuse(DollyFishing);
  if (at<uint8_t>(pawn + SlopeRun) && at<uint8_t>(pawn + SlopeType) &&
      at<float>(pawn + SlopeTime) - at<float>(pawn + SlopeStart) > 0)
    return refuse(DollySlope);
  auto alpha = at<float>(boom + DollyAlpha), arm = at<float>(boom + ArmRotation);
  auto pitch = std::remainder(arm, 360.0f);
  if (!std::isfinite(alpha) || !std::isfinite(pitch))
    return refuse(DollyUnreadable);
  camera = pitch;
  // Read back each poll: a capped or reset alpha never winds up. One arm
  // update normally runs between two polls, so pitch answers the previous
  // write; when none ran, the same gap is not added again.
  if (previous.moved && alpha == previous.alpha && arm == previous.arm) {
    last = previous;
    return true;
  }
  auto next = std::clamp(alpha + DollyStep * (look_pitch - pitch), 0.0f, 1.0f);
  auto *bytes = reinterpret_cast<uint8_t *>(boom);
  put(bytes, DollyAlpha, next);
  put(bytes, DollyBlend, uint64_t(0));
  put(bytes, DollyBlendTarget, -1.0f);
  last = {next, arm, std::fabs(next - alpha) > MovedAlpha};
  return true;
}

namespace {
// The game world's actor manager (+0xc8) holds the actors (+0x28) and the
// player's index (+0x3c, below the count at +0x30); the player's actor counts
// while its life state (+0x2600) is Spawn (1) or Spawning (6). From the pinned
// build's HUD (FSBUILogicHUDHint).
uintptr_t player_actor(uintptr_t world) {
  auto manager = world ? at<uintptr_t>(world + 0xc8) : 0;
  if (!manager)
    return 0;
  auto index = at<int32_t>(manager + 0x3c), count = at<int32_t>(manager + 0x30);
  auto actors = at<uintptr_t>(manager + 0x28);
  if (!actors || index < 0 || index >= count)
    return 0;
  auto actor = at<uintptr_t>(actors + 8 * uintptr_t(index));
  auto life = actor ? at<int32_t>(actor + 0x2600) : 0;
  return life == 1 || life == 6 ? actor : 0;
}
struct EvadePatch {
  uintptr_t actor, node;
  uint64_t name; // The row's FName: a rebuilt tree may reuse the address.
  uint32_t original;
  bool seen; // Visited by the current restoring walk.
};
std::vector<EvadePatch> evade_patches;
constexpr uint32_t EvadeCommand = 24, TPSFireCommand = 44; // ESBSkillCommandState::Evade, ::TPSFire
constexpr uint32_t RangedFireItem = 1, RangedFireSkill = 2;  // ESBSkillCommandActionType
// EVE's command set (FSBActor +0x530) while it is hers (owner at +0x178).
uintptr_t command_set(uintptr_t actor) {
  auto set = actor ? at<uintptr_t>(actor + 0x530) : 0;
  return set && at<uintptr_t>(set + 0x178) == actor ? set : 0;
}
} // namespace

// An actor's stats are floats at +0x118 + 4 * ESBActorStatType.
bool read_gauges(uintptr_t world, float *out) {
  auto actor = player_actor(world);
  if (!actor)
    return false;
  constexpr uint32_t Stats[GaugeFloats] = {14, 15, 18, 19, 116}; // Beta, max, Burst, max, Burst unlocked.
  for (uint32_t i = 0; i < GaugeFloats; i++) {
    out[i] = at<float>(actor + 0x118 + 4 * uintptr_t(Stats[i]));
    if (!std::isfinite(out[i]) || out[i] < 0 || out[i] > GaugeLimit)
      return false;
  }
  return out[0] <= out[1] && out[2] <= out[3];
}
bool read_stats(uintptr_t world, float *out) {
  auto actor = player_actor(world);
  if (!actor)
    return false;
  constexpr uint32_t Stats[StatFloats] = {2, 11, 26, 38, 39, 55, 56, 71};
  for (uint32_t i = 0; i < StatFloats; i++) {
    out[i] = at<float>(actor + 0x118 + 4 * uintptr_t(Stats[i]));
    if (!std::isfinite(out[i]) || std::fabs(out[i]) > GaugeLimit)
      return false;
  }
  return out[0] > 0 && out[2] > 0;
}

// EVE's command set (FSBActor +0x530, owner back at +0x178) roots her nodes at
// +0x8; a node keeps child pointers at +0x8 (count +0x10), its command at
// +0x18 and its row FName at +0x24 (0x1a61c50, 0x1d28500, 0x1d285e0). Her eight
// lock-gated evades are global commands under the root or their double dodges
// one below.
uint32_t patch_evades(uintptr_t world, bool locked) {
  constexpr int32_t MaxChildren = 4096;
  auto actor = player_actor(world);
  auto set = command_set(actor);
  auto root = set ? at<uintptr_t>(set + 8) : 0;
  if (!root)
    return uint32_t(evade_patches.size()); // Records kept until her tree is back.
  auto visit = [&](uintptr_t node) {
    if (at<uint32_t>(node + 0x18) != EvadeCommand)
      return;
    auto value = at<uint32_t>(node + 0x68);
    auto name = at<uint64_t>(node + 0x24);
    auto *bytes = reinterpret_cast<uint8_t *>(node);
    auto record = std::find_if(evade_patches.begin(), evade_patches.end(),
                               [&](const EvadePatch &p) { return p.node == node && p.name == name; });
    if (locked && (value == 1 || value == 2)) {
      if (record != evade_patches.end())
        record->original = value; // Written back by the game meanwhile.
      else
        evade_patches.push_back({actor, node, name, value, false});
      put(bytes, 0x68, value == 2 ? 0u : 3u);
    } else if (!locked && record != evade_patches.end()) {
      record->seen = true;
      if (value == (record->original == 2 ? 0u : 3u))
        put(bytes, 0x68, record->original);
    }
  };
  auto children = [&](uintptr_t node, auto &&each) {
    auto data = at<uintptr_t>(node + 8);
    auto count = at<int32_t>(node + 0x10);
    for (int32_t i = 0; data && count <= MaxChildren && i < count; i++)
      if (auto child = at<uintptr_t>(data + 8 * uintptr_t(i)))
        each(child);
  };
  children(root, [&](uintptr_t node) {
    visit(node);
    children(node, visit);
  });
  // Nodes out of her tree now keep their records until they are back or her actor is gone.
  if (!locked)
    evade_patches.erase(std::remove_if(evade_patches.begin(), evade_patches.end(),
                                       [&](EvadePatch &p) {
                                         bool done = p.seen || p.actor != actor;
                                         p.seen = false;
                                         return done;
                                       }),
                        evade_patches.end());
  return uint32_t(evade_patches.size());
}

void count_evades(uintptr_t world, bool locked, uint64_t wall, EvadeStarts &e) {
  auto set = command_set(player_actor(world));
  if (!set)
    return;
  auto node = at<uintptr_t>(set + 0x70);
  auto start = at<uint32_t>(set + 0x78);
  if (node == e.node && start == e.start)
    return;
  auto previous = e.node;
  auto opened = std::exchange(e.opened, 0);
  e.node = node;
  e.start = start;
  if (!start) { // Its window closed: cut unless near its end.
    if (opened && previous && float(wall - opened + EvadeMarginMs) < at<float>(previous + 0xac) * 1000)
      e.counts[3 * e.opened_locked + 2]++;
    return;
  }
  if (!node)
    return;
  auto command = at<uint32_t>(node + 0x18);
  if (command == TPSFireCommand) {
    auto action = at<uint32_t>(node + 0x30);
    e.shots += action == RangedFireItem || action == RangedFireSkill;
    return;
  }
  if (command != EvadeCommand)
    return;
  bool first = at<uint8_t>(node + 0xb0) != 0;
  e.counts[3 * locked + !first]++;
  if (first) {
    e.opened = wall;
    e.opened_locked = locked;
  }
}

bool hold_battle(uintptr_t world, uintptr_t clock, uintptr_t vtable) {
  auto actor = player_actor(world);
  if (!actor || at<uint8_t>(actor + 0xb58) != 1 || !clock || at<uintptr_t>(clock) != vtable)
    return false;
  auto now = at<float>(clock + 0xc);
  if (!std::isfinite(now) || !(now > at<float>(actor + 0x558)))
    return false;
  put(reinterpret_cast<uint8_t *>(actor), 0x558, now);
  return true;
}

uint64_t parse_lock(std::string_view text, std::string_view linked) {
  Lines lines(text, 256);
  std::string session;
  uint64_t seq = 0, time = 0;
  bool target = lines.literal("SBMC_LOCK 1") && lines.text("SESSION", session) &&
                identity(session) && session == linked && lines.num("SEQ", seq) && lines.num("TIME", time) &&
                lines.field("TARGET", 3).size() == 3 && lines.end(seq);
  return target ? time : 0;
}

Turn follow(Follow &f, float look_pitch, float look_yaw, float yaw) {
  auto wrap = [](float degrees) { return std::remainder(degrees, 360.0f); };
  auto limit = [](float degrees) { return std::clamp(degrees, -MaxTurn, MaxTurn); };
  float gap_yaw = wrap(look_yaw - yaw);
  Turn turn;
  if (f.previous) {
    turn.yaw = wrap(look_yaw - f.yaw);
    turn.pitch = wrap(look_pitch) - wrap(f.pitch);
  }
  f.previous = true;
  f.pitch = look_pitch;
  f.yaw = look_yaw;
  f.apart = std::fabs(gap_yaw) > FarDegrees ? f.apart + 1 : 0;
  // A camera that stays far for a second is not following this input (look
  // input ignored, or a game that turns differently); stop adding to it.
  turn.engaged = f.apart < FarPolls;
  turn.yaw = turn.engaged ? limit(turn.yaw + FollowGain * gap_yaw) : 0;
  turn.pitch = turn.engaged ? limit(turn.pitch) : 0;
  return turn;
}

struct Producer::Impl {
  stellar_shared::Mapping file;
  uint8_t *base = nullptr;
  std::string run;
  uint32_t back = 1;
  uint64_t counter = 0, heartbeat = 0, observed = 0, total_us = 0;
  uint32_t max_us = 0;
  bool owned = false;
  void release() {
    file.close();
    base = nullptr;
    owned = false;
  }
  bool valid_header(const Request &r, uint64_t now) const {
    if (std::memcmp(base, "SBMCPOS1", 8) || plain<uint32_t>(base, 8) != 1 ||
        plain<uint32_t>(base, 12) != Header ||
        plain<uint32_t>(base, 16) != SlotBytes ||
        plain<uint32_t>(base, 20) != Capacity ||
        plain<uint32_t>(base, 28) != TotalBytes ||
        std::memcmp(base + 32, r.run.data(), 32) || !plain<uint32_t>(base, 96) ||
        load32(base, 128) != 0 || load32(base, 192) != 0 ||
        load32(base, 196) != 0 || load32(base, 200) != 0 ||
        load64(base, 208) != 0 || load64(base, 216) != 0 ||
        load32(base, 256) != 1 || !fresh(load64(base, 264), now) ||
        load64(base, 272) != 0)
      return false;
    for (auto range : {std::pair<size_t, size_t>{24, 28},
                       {64, 96},
                       {100, 128},
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
const std::string &Producer::run() const { return impl_->run; }
bool Producer::open(const std::wstring &directory, const Request &request,
                    uint32_t pid, uint64_t wall) {
  auto &self = *impl_;
  if (!request.active || !identity(request.run) || !pid)
    return false;
  if (self.base && self.run == request.run) {
    heartbeat(wall);
    return active(wall);
  }
  stop();
  auto path = std::filesystem::path(directory) / ("eve-pose-" + request.run + ".bin");
  if (!self.file.open(path, TotalBytes))
    return false;
  self.base = self.file.base;
  if (!self.valid_header(request, wall) ||
      !stellar_shared::claim(self.base, 192, pid)) {
    self.release();
    return false;
  }
  self.owned = true;
  self.run = request.run;
  self.back = 1;
  self.counter = self.total_us = 0;
  self.max_us = 0;
  self.heartbeat = load64(self.base, 264);
  self.observed = monotonic_now();
  store64(self.base, 208, wall);
  exchange32(self.base, 196, 1);
  return true;
}
bool Producer::active(uint64_t wall) const {
  const auto &self = *impl_;
  return self.base && self.owned && load32(self.base, 196) == 1 &&
         load32(self.base, 256) == 1 && middle_valid(load32(self.base, 128)) &&
         fresh(load64(self.base, 264), wall) &&
         monotonic_now() - self.observed < Lease;
}
void Producer::heartbeat(uint64_t wall) {
  auto &self = *impl_;
  if (!self.base || !self.owned || load32(self.base, 196) != 1)
    return;
  if (!middle_valid(load32(self.base, 128))) {
    fail(1);
    return;
  }
  auto beat = load64(self.base, 264);
  if (!active(wall) || beat < self.heartbeat) {
    stop();
    return;
  }
  if (beat > self.heartbeat) {
    self.heartbeat = beat;
    self.observed = monotonic_now();
  }
  store64(self.base, 208, wall);
}
bool Producer::publish(uint64_t generation, const uint32_t *status,
                       uint32_t count, const float *payload, uint32_t floats,
                       const float *rotation, uint32_t look,
                       uint64_t wall, uint32_t read_us, const float *gauges,
                       const uint32_t *evades, const float *stats, DollyRefusal refusal,
                       uint32_t shots) {
  auto &self = *impl_;
  if (!active(wall) || !generation || generation > stellar_input::max_integer ||
      !count || count > MaxComponents || floats > Capacity) {
    return false;
  }
  auto *slot = self.base + Header + size_t(SlotBytes) * self.back;
  std::memset(slot, 0, SlotHeader);
  put(slot, 0, ++self.counter);
  put(slot, 8, wall);
  put(slot, 16, generation);
  put(slot, 24, Format);
  put(slot, 28, count);
  put(slot, 32, floats);
  // Controller rotation: status at 36, Pitch, Yaw, Roll at 40, camera pitch at 52, dolly refusal at 56.
  if (rotation) {
    put(slot, 36, RotationValid | (look & (LookApplied | PitchSynced | InputGranted)));
    std::memcpy(slot + 40, rotation, 4 * sizeof(float));
    put(slot, 56, (look & PitchSynced) || refusal >= DollyRefusals ? uint32_t(DollyNone) : refusal);
  }
  std::memcpy(slot + 64, status, sizeof(uint32_t) * count);
  std::memcpy(slot + SlotHeader, payload, sizeof(float) * floats);
  // Same back/middle/front exchange as the raw frame mapping.
  auto previous = exchange32(self.base, 128, self.back | Dirty);
  if (!middle_valid(previous)) {
    fail(1);
    return false;
  }
  self.back = previous & 3u;
  self.total_us += read_us;
  self.max_us = std::max(self.max_us, read_us);
  // EVE's gauges in the header, beside the slots: floats at 324, then state 1
  // (or 0 when unreadable) at 320.
  if (gauges)
    std::memcpy(self.base + 324, gauges, sizeof(float) * GaugeFloats);
  exchange32(self.base, 320, gauges ? 1 : 0);
  // EVE's evade starts and the nodes the lock left changed: words at 344.
  if (evades)
    std::memcpy(self.base + 344, evades, sizeof(uint32_t) * EvadeWords);
  // EVE's stats for Minecraft: floats at StatsAt, then state 1 (or 0) at StatsState.
  if (stats)
    std::memcpy(self.base + StatsAt, stats, sizeof(float) * StatFloats);
  exchange32(self.base, StatsState, stats ? 1 : 0);
  // Her drone's shots at ShotsAt.
  put(self.base, ShotsAt, shots);
  put(self.base, 224, read_us);
  put(self.base, 228, self.max_us);
  store64(self.base, 232, self.total_us);
  store64(self.base, 216, self.counter);
  return true;
}
bool Producer::look(uint64_t wall, float &pitch, float &yaw) const {
  const auto &self = *impl_;
  // Consumer look: time (u64) at 288, Pitch and Yaw (f32) at 296 as one
  // atomic 64-bit word, state 1 at 304.
  if (!self.base || !self.owned || load32(self.base, 304) != 1)
    return false;
  auto time = load64(self.base, 288);
  if (!time || (time <= wall ? wall - time > 200 : time - wall > 100))
    return false;
  auto packed = load64(self.base, 296);
  std::memcpy(&pitch, &packed, 4);
  std::memcpy(&yaw, reinterpret_cast<const uint8_t *>(&packed) + 4, 4);
  if (!std::isfinite(pitch) || !std::isfinite(yaw) || pitch < -90 || pitch > 90 ||
      std::fabs(yaw) > 1e6f)
    return false;
  yaw = std::fmod(yaw, 360.0f);
  if (yaw < 0)
    yaw += 360;
  return true;
}
void Producer::fail(uint32_t error) {
  auto &self = *impl_;
  if (self.base && self.owned) {
    exchange32(self.base, 200, error >= 1 && error <= 3 ? error : 3);
    exchange32(self.base, 196, 3);
  }
}
void Producer::stop() {
  auto &self = *impl_;
  // State 2 acknowledges release; the consumer may then delete the mapping.
  if (self.base && self.owned)
    exchange32(self.base, 196, 2);
  self.release();
  self.run.clear();
}

namespace {
std::mutex lock; // The game thread only try-locks; it never waits for the worker.
Engine engine;
Producer producer;
std::optional<Table> table;
std::vector<float> payload(Capacity);
std::array<uint32_t, MaxComponents> status{};
Follow following;
DollyWrite dolly_write;
uint64_t minecraft_lock = 0; // eve-lock.txt's TIME while Minecraft holds a target.
EvadeStarts evade_starts;
uint32_t process_id() {
#ifdef _WIN32
  return GetCurrentProcessId();
#else
  return static_cast<uint32_t>(getpid());
#endif
}
uint64_t micros() {
#ifdef _WIN32
  LARGE_INTEGER counter, frequency;
  QueryPerformanceCounter(&counter);
  QueryPerformanceFrequency(&frequency);
  return static_cast<uint64_t>(counter.QuadPart * 1000000 / frequency.QuadPart);
#else
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                   std::chrono::steady_clock::now().time_since_epoch())
                                   .count());
#endif
}
#ifdef _WIN32
// A component freed between Lua's publication and this read faults at worst;
// contain it like an invalid component. No C++ object lives in this frame.
uint32_t guarded(const Engine &e, const Component &c, float *out) {
  __try {
    return read_component(e, c, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    std::memset(out, 0, sizeof(float) * (10 + 10 * size_t(c.bones) + c.morphs));
    return 0;
  }
}
bool guarded(const Engine &e, const Parameter &q, float *out) {
  __try {
    return read_parameter(e, q, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    std::memset(out, 0, sizeof(float) * q.floats);
    return false;
  }
}
bool guarded(const Engine &e, const Controller &c, float *out) {
  __try {
    return read_controller(e, c, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    std::memset(out, 0, 3 * sizeof(float));
    return false;
  }
}
uint32_t guarded_effects(const Engine &e, const Table &t, float *out) {
  __try {
    return read_effects(e, t, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    for (uint32_t r = 0; r < MaxEffects; r++) {
      std::memset(out + r * EffectFloats, 0, sizeof(float) * EffectFloats);
      out[r * EffectFloats] = -1;
    }
    return 0;
  }
}
// A stale chain faults at worst; contained like unreadable gauges.
bool guarded_gauges(uintptr_t world, float *out) {
  __try {
    return read_gauges(world, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return false;
  }
}
bool guarded_stats(uintptr_t world, float *out) {
  __try {
    return read_stats(world, out);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return false;
  }
}
uintptr_t game_world() {
  static const auto image = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
  __try {
    return at<uintptr_t>(image + GameWorldRva);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return 0;
  }
}
// A command tree freed under the walk faults at worst; contained.
uint32_t guarded_evades(uintptr_t world, bool locked) {
  __try {
    return patch_evades(world, locked);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return 0;
  }
}
void guarded_starts(uintptr_t world, bool locked, uint64_t wall, EvadeStarts &e) {
  __try {
    count_evades(world, locked, wall, e);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
  }
}
// A player actor freed under the poll faults before the write at worst; contained.
bool guarded_battle(uintptr_t world) {
  static const auto image = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
  __try {
    return hold_battle(world, at<uintptr_t>(image + TurnClockRva), image + TurnClockVtableRva);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return false;
  }
}
// A boom freed after its pawn was checked faults before the write; contained.
bool guarded_dolly(const Engine &e, const Controller &c, float pitch, float &camera, DollyWrite &last,
                   uint32_t *refusal) {
  __try {
    return steer_dolly(e, c, pitch, camera, last, refusal);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return false;
  }
}
// A pawn freed after Lua published it faults before any call; contained.
bool guarded_turn(const Engine &e, const Controller &c, float pitch, float yaw) {
  __try {
    return turn_controller(e, c, pitch, yaw);
  } __except (EXCEPTION_EXECUTE_HANDLER) {
    return false;
  }
}
#else
bool guarded_gauges(uintptr_t world, float *out) { return read_gauges(world, out); }
bool guarded_stats(uintptr_t world, float *out) { return read_stats(world, out); }
uintptr_t game_world() { return 0; } // Only the pinned Windows executable has one.
uint32_t guarded_evades(uintptr_t world, bool locked) { return patch_evades(world, locked); }
void guarded_starts(uintptr_t world, bool locked, uint64_t wall, EvadeStarts &e) { count_evades(world, locked, wall, e); }
bool guarded_battle(uintptr_t) { return false; } // Nor a turn clock.
uint32_t guarded_effects(const Engine &e, const Table &t, float *out) {
  return read_effects(e, t, out);
}
bool guarded_turn(const Engine &e, const Controller &c, float pitch, float yaw) {
  return turn_controller(e, c, pitch, yaw);
}
bool guarded_dolly(const Engine &e, const Controller &c, float pitch, float &camera, DollyWrite &last,
                   uint32_t *refusal) {
  return steer_dolly(e, c, pitch, camera, last, refusal);
}
uint32_t guarded(const Engine &e, const Component &c, float *out) {
  return read_component(e, c, out);
}
bool guarded(const Engine &e, const Controller &c, float *out) {
  return read_controller(e, c, out);
}
bool guarded(const Engine &e, const Parameter &q, float *out) {
  return read_parameter(e, q, out);
}
#endif
} // namespace

void initialize(uintptr_t objects, uintptr_t boom_vtable) {
  engine.objects = objects;
  engine.boom_vtable = boom_vtable;
}

void poll_files(const std::wstring &directory, std::string_view request_text,
                std::string_view slot0, std::string_view slot1, uint64_t wall,
                std::string_view lock_text) {
  auto request = parse_request(request_text);
  bool wanted = request && request->active && fresh(request->time, wall);
  auto next = wanted ? select_components(slot0, slot1, request->run)
                     : std::optional<Table>{};
  // Only a lock of the session Lua links counts: another Minecraft session's file opens nothing.
  auto locked_at = next ? parse_lock(lock_text, next->session) : 0;
  std::lock_guard<std::mutex> guard(lock);
  minecraft_lock = locked_at;
  if (!wanted) {
    producer.stop();
    table.reset();
    return;
  }
  if (!producer.open(directory, *request, process_id(), wall)) {
    table.reset();
    return;
  }
  if (!next)
    table.reset();
  else if (!table || table->generation != next->generation)
    table = std::move(next);
}

void on_gameplay_poll(std::string_view owner) {
  std::unique_lock<std::mutex> guard(lock, std::try_to_lock);
  if (!guard.owns_lock())
    return;
  auto wall = stellar_shared::wall_now();
  // Minecraft's lock-on gives EVE Stellar Blade's lock-on evades while Minecraft drives her,
  // and keeps her battle stance as Stellar Blade's own lock-on does.
  bool locked = !owner.empty() && fresh(minecraft_lock, wall, LockLease);
  if (locked || !evade_patches.empty()) {
    auto changed = guarded_evades(game_world(), locked);
    if (locked)
      evade_starts.counts[6] = changed;
  }
  if (locked)
    guarded_battle(game_world());
  guarded_starts(game_world(), locked, wall, evade_starts);
  if (!table || !engine.objects || !producer.active(wall))
    return;
  auto started = micros();
  float rotation[4] = {}; // The controller's Pitch, Yaw, Roll, then the camera's pitch.
  bool rotated = table->controller && guarded(engine, *table->controller, rotation);
  // Minecraft's camera turns Stellar Blade's only while this stream's run owns
  // forwarded input; the frame's own input processing then applies the turn.
  // The dolly carries the pitch where it can: pitch input would move it again.
  bool granted = !owner.empty() && owner == producer.run(), applied = false, synced = false;
  uint32_t refusal = DollyNone; // Why the dolly was left alone this frame, when it was tried.
  float pitch = 0, yaw = 0;
  if (rotated && granted && producer.look(wall, pitch, yaw)) {
    auto turn = follow(following, pitch, yaw, rotation[1]);
    synced = guarded_dolly(engine, *table->controller, pitch, rotation[3], dolly_write, &refusal);
    applied = turn.engaged && guarded_turn(engine, *table->controller, synced ? 0 : turn.pitch, turn.yaw);
  } else {
    following = {};
    dolly_write = {};
  }
  uint32_t offset = 0, count = 0;
  for (const auto &c : table->components) {
    status[count++] = guarded(engine, c, payload.data() + offset);
    offset += 10 + 10 * c.bones + c.morphs;
  }
  // Unreadable parameters publish zeros: an instance's glow reads as off.
  for (const auto &q : table->parameters) {
    guarded(engine, q, payload.data() + offset);
    offset += q.floats;
  }
  if (table->effects) {
    guarded_effects(engine, *table, payload.data() + offset);
    offset += MaxEffects * EffectFloats;
  }
  float gauges[GaugeFloats];
  bool gauged = guarded_gauges(game_world(), gauges);
  float stats[StatFloats];
  bool statted = guarded_stats(game_world(), stats);
  auto elapsed = micros() - started;
  producer.publish(table->generation, status.data(), count, payload.data(),
                   offset, rotated ? rotation : nullptr,
                   (applied ? LookApplied : 0u) | (synced ? PitchSynced : 0u) | (granted ? InputGranted : 0u), wall,
                   static_cast<uint32_t>(std::min<uint64_t>(elapsed, UINT32_MAX)),
                   gauged ? gauges : nullptr, evade_starts.counts, statted ? stats : nullptr,
                   static_cast<DollyRefusal>(refusal), evade_starts.shots);
}
} // namespace stellar_pose
