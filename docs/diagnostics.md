# Diagnostic commands

The gameplay path is [`/stellarview play`](../README.md#start-a-session). The commands below isolate problems; they are not required for each session. F10 belongs to the existing Stellar Blade console. Shift may also trigger native dodge; prefer console equivalents when keeping EVE still.

## Stellar Blade

| Key | Console command | Action |
|---|---|---|
| F5 | `sbmc mirror` | Import the active Minecraft collision patch |
| F8 | `sbmc enter` | Enter the created lab once |
| F6 | `sbmc status` | Log context, position and timing counters |
| F11 | `sbmc restore` | Revoke input, stop captures, return EVE and clean up owned geometry |
| F4 | `sbmc framehd` | One 1280 × 720 paired still: BMP color, PNG mask, HDR depth |
| F2 | `sbmc depth` | Independent depth probe; not paired with another capture |
| F1 | `sbmc live` / `sbmc liveoff` | Legacy 320 × 180 animated diagnostic; capped at 60 seconds or 300 frames |
| F7 | `sbmc create` | Standalone collision fixture: floor, four walls, step and obstacle |
| F9 | `sbmc toggle` | Toggle the standalone fixture's obstacle |
| Shift+F7 | `sbmc texture` | Toggle a non-colliding stone-texture probe |
| Shift+F8 | `sbmc visuals` | Toggle native Minecraft terrain visuals inside Stellar Blade |

`sbmc posetest` records EVE's skeletal mesh components for up to 15 seconds or 900 frames: bone transforms relative to their parents (`SnapshotPose`, with a per-bone fallback), face morph weights and component world transforms. It writes private `eve-pose-probe.txt` (components, bones, method, timing) and `eve-pose-probe.bin` (per frame: frame number, seconds, then each component's world transform followed by its bones and weights, 10 floats per transform). It changes nothing in the scene; Minecraft can replay it with [`/stellaravatar replay`](#avatar-view-experimental).

`sbmc frame` retains the 320 × 180 PNG still; `sbmc framehdpng` retains HD PNG color. `sbmc capture` is an independent color probe. `sbmc capturecleanup` retries failed capture cleanup.

`sbmc exportbmp` compares synchronous color exports. `sbmc exportasync` and `sbmc frameasync` share one asynchronous submission attempt per Lua lifetime and omit completion callbacks. The latter requires Minecraft's file-close watcher already running, then `/stellarpreview load` or `/stellarview load`. Pending generation files are retained until cleanup with both games closed. `sbmc exporttest` is disabled because its callback crashed the pinned loader.

Read `SB/Binaries/Win64/ue4ss/UE4SS.log`, prefix `[StellarCollisionProbe]`, and Minecraft's `logs/latest.log`. Successful object creation or texture assignment is not proof of valid pixels or collision.

## Minecraft views

- `/stellarpreview load` shows a still on a checkerboard; `/stellarpreview off` closes it.
- `/stellarview load` composes a still against world depth. It requires the matching entered terrain session and revision.
- `/stellarview live` consumes the legacy F1 producer. `/stellarview livehd` requests the continuous raw producer without input forwarding.
- `/stellarview status` reports image delivery separately from Minecraft FPS; see [performance measurements](#performance-measurements). `/stellarview off` restores the normal camera and stops forwarded controls.

Still captures expire 30 seconds after capture. Legacy live startup times out after five seconds; two seconds without a fresh generation stops an established view. A repeated frame does not renew freshness. Revision changes hide incompatible frames. Unsupported effects, session/world changes or invalid data stop composition.

A high Minecraft FPS counter with slow EVE motion indicates a different capture/transfer cadence; compare accepted frames over the same interval and framebuffer size. A small checkerboard preview does not show the full capture resolution. Hair noise can originate in native capture, not only the Minecraft compositor.

## Avatar view (experimental)

These commands draw EVE with her own meshes in Minecraft. Minecraft keeps its own camera, frame rate, lighting (lightmap at EVE's position, entity-style directional shading) and fog. Her materials add normal maps, ambient occlusion, roughness-based highlights, reflections of Minecraft's sky (in proportion to the sky light where she stands), metals, emissive parts and light scattered under the skin on top of that lighting; hair cards get strand highlights and darker roots, and translucent overlays (eye occlusion, catchlights, the wet tear line) draw last.

- `/stellaravatar replay` loops the latest `sbmc posetest` recording from the bridge directory two blocks ahead of the player, interpolating between recorded frames. Stellar Blade does not need to run.
- `/stellaravatar live` streams EVE's current pose from Stellar Blade (Lua revision 37 and the matching adapter) once per native frame. After `/stellar start`, F5 and F8 she stands where the bridge places EVE and replaces the player's body in third person; Minecraft hides her in first person, where the camera is inside her head. Without an entered link she stands two blocks ahead of where the command ran. A changed composition (outfit, weapon, hair) briefly reloads meshes and textures.
- `/stellaravatar play`, after `/stellar start`, F5 and F8 and without `/stellarview`, drives EVE from Minecraft without any capture. Minecraft owns the camera (third person by default) and its mouse look; Minecraft's movement and action bindings are forwarded as with `/stellarview play`, gated by a fresh streamed pose instead of a captured image. While input is forwarded, the adapter turns Stellar Blade's camera toward Minecraft's (yaw and pitch) at each native frame through the game's own look input, as a mouse would. Each pose reports the controller's rotation; the left stick is rotated by any remaining difference so EVE moves where Minecraft looks. If the camera does not follow (a yaw gap above 30 degrees for a second), the right stick steers it instead (full deflection at 45 degrees of yaw error, never reversing during fast spins). `/stellaravatar status` reports the share of poses turned by look input and the mean and maximum yaw gap. The camera follows EVE's displayed position.
- `/stellaravatar status` reports the stream: component-table generation, poses received per second, incomplete poses, the age of the last pose and the adapter's read time (last, maximum and mean microseconds per native frame).
- `/stellaravatar off` removes her and releases the stream.

Each command reports drawn material sections, meshes missing from the pack and, for a replay, loading time. With `/stellaravatar live` alone, drive EVE in Stellar Blade; `/stellarview play` also works but adds its captured image and image-paced camera. Poses are displayed one sample interval behind the newest, interpolated on Stellar Blade's publication times, so motion stays smooth above Stellar Blade's frame rate; `/stellaravatar status` shows that interval.

The renderer reads a private avatar pack from `~/.local/share/stellar-blade-minecraft/avatar` (override with `-Dstellarbridge.avatar=<dir>`). The pack is built from the user's own game installation and is never part of this repository:

- `<mesh>.sbav` for each mesh named in the recording's `COMPONENT` lines. Components without a mesh in the pack are skipped and listed.
- `<material slot>.png`: one baked RGBA base texture per drawn section; alpha below 0.5 (hair: 0.25) is cut out, and its mipmaps keep the share of texels that pass, so thin strands do not thin out at a distance. Sections without a PNG are not drawn.
- Optional maps beside it, each falling back to a neutral default: `<slot>.normal.png` (tangent-space normal in Unreal's convention, +X along +U and +Y along +V; the shader builds the frame from screen-space derivatives, so meshes need no tangents), `<slot>.surface.png` (occlusion, roughness, metallic and specular in R, G, B, A; specular 0.5 is 4 % reflectance), `<slot>.emissive.png` (RGB added after lighting), `<slot>.scatter.png` (subsurface color times mask: light wraps past the terminator along the smooth normal) and `<slot>.hair.png` (root-to-tip gradient, strand id and depth; selects strand shading along +V).
- `<slot>.overlay.png` instead of `<slot>.png` for a translucent slot: premultiplied RGB with its opacity in alpha (alpha 0 adds light), drawn after every opaque section without writing depth; without its own surface map it gets no highlight. `<slot>.glow.png` is such an overlay drawn only while the slot's streamed `Opacity` is above zero, its emissive map tinted by `Emissive_Color` × `Emissive_Intensity` plus the `_2` pair.
- `<mesh>.trail.txt` beside a weapon mesh: `SBAVTRAIL 1`, `BEGIN bone x y z` and `END bone x y z` (the trail sockets in Unreal units in their bones' space), `HELD mesh handBone centimeters chestBone`; with `<mesh>.trail.png` (gradient in red, streak noise in green).

`SBAVMESH` version 2 is little-endian, in Unreal space (centimeters, Z up): magic `SBAVMESH`, `u32` version; `u32` bone count (at most 256), then per bone a `u16`-length UTF-8 name, `i32` parent (earlier bone or -1) and the reference local transform (10 floats: quaternion xyzw, translation, scale); `u32` vertex count, then 56-byte vertices (position, normal, UV, four `u16` bone indices, four float weights); `u32` index count and `u32` triangle indices; `u32` section count, then per section a material slot name, first index and triangle count; `u32` morph count, then per morph a name, `u32` delta count and per delta a `u32` vertex with position and normal deltas (6 floats). Morphs follow the mesh's Unreal `MorphTargets` order and are matched to recorded weights by name. Recorded bone names must equal the mesh's bones in order; leader-pose followers (face, ponytail) map bones by name to their leader.

The live stream carries the face's morph weights (expressions, blinks); `sbmc posetest` recordings do not. It also carries glow parameters the game animates on dynamic material instances (`Opacity`, `Emissive_Intensity`, `Emissive_Color` and their `_2` pairs): a `<slot>.glow.png` slot such as the sword's edge appears only while its `Opacity` is raised. `/stellaravatar status` lists how many parameters are streamed and the highest glow opacity seen. Most of Stellar Blade's combat effects are not material parameters but particle systems its attack shows spawn (anim trails between weapon sockets, Niagara effects such as the sword flame buff). Minecraft draws the sword's trail itself from the streamed swing: while the blade is in EVE's right hand and turns fast relative to her chest, a 0.12 s ribbon between the weapon's trail sockets follows it, white fading to blue, with the game's trail gradient. Lua logs every change of EVE's active particle effects (owned by her actor, or pooled by the world and attached under her skeletal components) to `UE4SS.log` as `EVE effects: asset[class]@parent:socket ...` to guide the next effects. `./gradlew avatarRenderCheck -PavatarCheck="<pack> <recording dir> <out.png> <seconds,...> [distance,height,degrees [glow]]"` in `minecraft/` renders a pack and recording offscreen with the same renderer and prints per-mesh extents; it needs a GPU and is not part of `tools/check.py`.

## Performance measurements

Each layer reports its own rate. Compare runs only at the same Minecraft framebuffer size, because capture cost grows with captured pixels.

- **Stellar Blade:** while a link is entered, `UE4SS.log` receives one `Cadence:` line per ten native seconds: native FPS, whether capture is live, completed generations and native frames from submission to observed completion. Idle lines give the same session's uncaptured rate.
- **Native adapter:** the private `eve-raw-timing.txt` keeps per-plane rows for the first 12 generations and rewrites whole-run summaries at most once per second. `COST 0`/`COST 2` are color/depth conversion times, `GAP` is the time between a generation's two plane arrivals: the second engine readback plus any image-queue wait, and `PERIOD` is the interval between publications. Values are count, mean and maximum microseconds.
- **Minecraft:** `/stellarview status`, also logged when a view stops, gives displayed frames, frames that arrived from the worker, frames replaced before the next Minecraft render or rejected as stale or mismatched, Minecraft world renders per second while the view is requested, and worker copy and texture-upload times. Upload time is the render-thread call cost, not GPU completion.

GPU contention between the games is outside these counters; sample it externally, for example with `nvidia-smi pmon`.

## Reverse terrain and texture probes

The reverse renderer displays Minecraft faces inside Stellar Blade and leaves its surroundings visible. It supports air and full cubes of stone, dirt, grass without snow, white wool and bedrock within the fixed patch. Unsupported blocks stop visual publication. Textures come from the active Minecraft resource manager; no game assets are packaged here. It does not import mobs, distant chunks, arbitrary models or fluids.

After `/stellar start`, F5 and F8, toggle it with `sbmc visuals`. Disable it before capturing EVE into Minecraft. Visual revisions are independent of collision revisions; changing wool to stone can update appearance without changing collision. F11 removes owned visuals and performs the guarded return.

The standalone `sbmc texture` probe requires `minecraft-stone.png` in the private bridge directory, extracted from `assets/minecraft/textures/block/stone.png` in the user's own Minecraft 1.21.1 client JAR. It creates one temporary non-colliding cube ahead of EVE. Its opaque display material does not establish Minecraft lighting compatibility.
