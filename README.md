# Stellar Blade × Minecraft

An experimental bridge project for playing EVE with her native movement and combat in a live Minecraft Java world on Linux, with Stellar Blade running through Proton.

**Minecraft is the primary game and renderer.** Its world, entities, inventory, menus, mods and shader pipeline remain in Minecraft. Stellar Blade supplies EVE's native movement, animations and combat systems through the bridge. The intended view composites EVE into Minecraft; it does not replace Minecraft's renderer with Stellar Blade's.

The current build includes an experimental **local Minecraft terrain and position link** and EVE image-capture diagnostics. Minecraft exports real collision shapes; Stellar Blade imports them into a bounded elevated lab and sends EVE's position back to the Minecraft player. A still EVE image can be composed with Minecraft's world depth. Practical continuous composition, controls while Minecraft has focus, cross-game combat, general world streaming and Iris compatibility are not established yet. Compatibility with other mods and shader packs must be verified against specific versions; it is not universal by default.

A bounded Minecraft-terrain renderer inside Stellar Blade is retained as a separate diagnostic. It is not the final rendering direction and does not establish Minecraft shader compatibility. Stellar Blade outfit and CNS compatibility are secondary goals.

## Minecraft terrain link

Use **Minecraft Java 1.21.1**, **Fabric Loader 0.19.5**, **Fabric API 0.116.17+1.21.1**, and **Java 21**. This experiment accepts only a private creative singleplayer world, without LAN players, fluids or vehicles. Start with a separate flat test world. An active bridge session keeps that world running through pause menus and focus changes without editing Minecraft's saved pause setting. `/stellar stop` or disconnect restores normal pause behavior.

Build with the included Gradle wrapper (Loom 1.11.8, Yarn 1.21.1+build.3):

```sh
cd minecraft
./gradlew build
```

Copy `minecraft/build/libs/stellar-bridge-0.1.0.jar` and the matching Fabric API jar into that instance's `minecraft/mods/` directory while Minecraft is closed. The mod starts inactive.

On Linux, the shared directory defaults to `~/.local/share/stellar-blade-minecraft/bridge`. The Minecraft mod creates it with private permissions. To prepare it before installing the Stellar Blade side:

```sh
mkdir -p "$HOME/.local/share/stellar-blade-minecraft/bridge"
chmod 700 "$HOME/.local/share/stellar-blade-minecraft/bridge"
python tools/deploy.py install --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods' \
  --bridge-dir "$HOME/.local/share/stellar-blade-minecraft/bridge"
```

The installer generates a machine-specific Lua configuration, including the Proton `Z:` path, and records it in the ownership manifest. It is not part of the source. A custom Minecraft location can be set with `-Dstellarbridge.directory=/absolute/path`; both games must use the same directory.

1. Stand on a full block in the creative test world and run `/stellar start`. `/stellar status` reports the revision and position link. Minecraft keeps this private world running while the bridge session is active, including in pause menus or after switching windows. `/stellar stop` ends the session and restores normal pause behavior; disconnecting also releases the override.
2. In Stellar Blade, load a backed-up save in a clear outdoor location. Press **F5** (`sbmc mirror`) to import the terrain. Check that the elevated scene is clear of the game's existing geometry.
3. Press **F8** to enter once. EVE's native controller owns movement. Minecraft's local player follows her feet position; Minecraft mouse look remains available for block editing during this phase.
4. Edit blocks in the fixed patch to test collision updates. A new block intersecting EVE is deferred until she moves away. Imported replacement collision is prepared before the old collision is removed.
5. Press **F11** to return EVE and remove the temporary scene. Stop the Minecraft session before closing either game. If the connection expires, the mod attempts a guarded return and retains support if that return fails.

The sampled patch is nine blocks wide, six blocks high and nine blocks deep, fixed around the initial Minecraft anchor. At most 256 merged collision boxes are accepted. The provisional scale is 100 Unreal units per block. Separate lab guards contain EVE at the patch boundary; they are additional experimental geometry, not Minecraft blocks. Visible cubes are diagnostic visuals from Stellar Blade, not Minecraft textures or a finished passthrough renderer.

Transport uses bounded versioned text snapshots with session IDs, revisions, timestamps and complete-message footers. Java publishes terrain with atomic replacement; Lua alternates two EVE files because the Windows C runtime cannot rename over an existing destination. Missing or stale EVE packets release Minecraft's position override. No network service or additional native transport DLL is used.

## Bounded native terrain diagnostic

This route displays Minecraft block faces inside Stellar Blade, preserving EVE's normal rendering, animation and camera. It avoids the low-resolution EVE frame transport described in the diagnostic sections below. Both games still run: Minecraft owns the world and block changes, while Stellar Blade owns EVE's movement.

This renderer is experimental. In-game checks confirmed the grass surface and exterior block faces, native traversal, live block addition/removal, and a wool-to-stone appearance change without a collision revision. With both games open, bounded flat-patch checks averaged 59.7 native engine frames per second over 131 seconds, and 57.9 over 147 seconds including block edits and window switching. These are average engine counters, not frame-time consistency measurements or larger-world performance guarantees.

Those measurements apply only to terrain rendered inside Stellar Blade. They do not measure EVE's image-transfer rate or gameplay performance inside Minecraft.

The linked world also remained active with Minecraft's pause menu open and Stellar Blade in focus. F11 returned EVE and cleaned up the native scene; `/stellar stop` restored Minecraft's normal menu pause behavior.

1. In the prepared Minecraft world, run `/stellar start` while standing on the floor.
2. Load the outdoor Stellar Blade save, then use **F5** to import collision and **F8** to enter the lab.
3. Press **Shift+F8** (`sbmc visuals`) to enable the native terrain scene. Keep Stellar Blade focused for native controls. The command also toggles the scene off.
4. Edit blocks in Minecraft to update the scene. **F11** clears the owned visuals and performs the guarded return before removing collision.

The initial renderer supports full cubes of **stone, dirt, grass blocks without snow, white wool and bedrock**, plus empty air, within the existing 9×6×9 patch. Unsupported non-air blocks stop visual publication with a diagnostic. The current native scene then expires, and the collision experiment remains available. This is a bounded terrain view: the original Stellar Blade surroundings are still present, and distant chunks, decorative block models, fluids, Minecraft mobs and HUD are not imported.

Visible faces use the supported vanilla texture paths from the active Minecraft resource manager. Grass top and side colors use Minecraft's tint; the side overlay is combined with its base texture. Square power-of-two source images up to 256 pixels are accepted. Texels are replicated with nearest-neighbor sampling into 256×256 tiles to retain block texture edges through native filtering. Face layouts and UV orientation are fixed: vanilla randomized rotations/mirroring, model-based texture redirection, custom model geometry and animated textures are outside this initial renderer. PNG replacements at the supported paths are honored.

Visual revisions are independent of collision revisions: changing stone into wool refreshes its appearance even when the collision shape is identical. A complete replacement mesh is prepared before the prior scene is retired. Only the mod's diagnostic terrain and guard meshes are hidden while a matching visual scene is active; their collision remains. Session changes, stale data and native context changes release the scene and restore diagnostic visibility.

The native scene uses an opaque display material for this first stage. Texture selection is supported; physically matched Minecraft lighting is not. Live EVE capture is stopped when this route is enabled, and cannot start while native terrain rendering remains enabled.

## Native image capture experiment

With bridge configuration installed, **F4** (`sbmc framehd`) requests a paired **1280×720 still** through temporary Unreal scene-capture actors showing EVE's components. Use F4 without a modifier: Shift also triggers EVE's native dodge. Display color, inverse-opacity mask and scene depth use the same camera snapshot within one game-thread call. Color uses an uncompressed 24-bit BMP; the alpha mask remains PNG and depth remains HDR. A manual request takes one frame without moving EVE or hiding the original level. It releases the temporary actors and render targets after each request; `sbmc capturecleanup` retries cleanup if the log reports a failure. Stop the animated preview before taking manual captures.

The console command **`sbmc frame`** retains the **320×180** PNG baseline through the same lifecycle. **`sbmc framehdpng`** retains the previous **1280×720** PNG color export for comparison. Update both mods together for F4's frame-v2 BMP color; the reader still accepts frame-v1 PNG captures. Only 320×180 and 1280×720 are accepted; every color, mask and depth plane must match its descriptor and stay within 4 MiB. These remain still-image checks. The animated preview retains its original resolution and cadence.

HD captures log start/end boundaries for native allocation, capture, export, validation, cleanup and publication. Minecraft logs CPU read/depth decoding, color/mask decoding and texture-upload submission times when a still is accepted. Submission time does not measure GPU completion. These diagnostics help identify the next transport improvement without claiming asynchronous readback or a playable frame rate.

The `sbmc exporttest` command is disabled: its experimental completion callback crashed the tested UE4SS build. F4 retains the synchronous still path. The newer asynchronous paths omit that callback and use reader-side file-close validation.

With production capture stopped, `sbmc exportbmp` compares synchronous PNG and BMP exports of one 1280×720 color target. It does not bind a completion callback or change the paired-frame descriptor. The timing log and diagnostic files test whether avoiding PNG compression reduces export cost; this command does not enable a faster Minecraft stream.

`sbmc exportasync` is a separate one-shot diagnostic. It prepares three HD capture targets, completes the existing HDR depth export, then queues BMP color and PNG mask writes without a completion callback. It retains the diagnostic files and leaves the paired frame unchanged. After submission begins, another attempt requires restarting Stellar Blade; Lua reload is unsupported. The log measures submission and ordered resource release, not completed writes or asynchronous GPU readback. F4 retains the synchronous frame-v2 path.

For a bounded asynchronous still, start Minecraft first, then use **`sbmc frameasync`** in Stellar Blade and **`/stellarpreview load`** or **`/stellarview load`** in Minecraft. Both mods must include frame-v3 support. Minecraft observes the native files closing through Linux inotify, validates their contents, and rejects missing or invalidated event history. The world view still requires the matching active terrain link. This command shares the single submission attempt with `exportasync`; it is not a continuous stream. Its one image generation is retained until manual cleanup with both games closed, including after F11, because asynchronous writes may finish later. F11 still invalidates the frame descriptor and performs normal scene cleanup.

`eve-frame.txt` in the private bridge directory names three generation-specific image files and records the camera, projection and context. Frame-v2 uses BMP color, PNG mask and HDR depth; frame-v1 uses PNG color. Synchronous descriptors are published only after all exports and cleanup succeed. Frame-v3 instead describes a pending asynchronous request: zero color/mask byte counts require observed file closure and full reader validation before use; depth has its validated byte count. A failed submission preserves the preceding descriptor. Synchronous generations are retired after replacement; the bounded asynchronous generation and old files from earlier launches can be removed while both games are closed.

In a private Minecraft singleplayer world, run **`/stellarpreview load`** within 30 seconds of F4 to inspect the capture in a diagnostic inset. **`/stellarpreview off`** closes it. The preview uses the display-color image and the separate inverse-opacity mask, rejecting low-coverage sky/haze pixels. Partial hair and translucency remain unsupported. The texture expires 30 seconds after capture and is removed on world change or disconnect. This is an opt-in still preview, not a live renderer, world-camera override or depth-tested composition.

The original independent probes remain available: `sbmc capture` writes `eve-capture-color.png` (raw HDR scene color clamped to RGBA8), while **F2** (`sbmc depth`) writes `eve-capture-depth.hdr`. Their descriptors are separate and do not establish a matching pair. Native tests confirmed inverse opacity in the PNG and a readable depth HDR. The PNG exporter adds trailing padding, which the publisher removes after parsing the actual IEND chunk. Independently attached weapons, the drone and effects may require additional capture membership.

### World composition experiment

After starting the terrain link and entering it with EVE, press F4 for the HD still (or use `sbmc frame` for the 320×180 baseline), then run **`/stellarview load`** in Minecraft within 30 seconds. Keep the native terrain diagnostic disabled. This selects the captured camera and projection and draws EVE with her captured scene depth against Minecraft's world depth. **`/stellarview off`** restores the vanilla camera. This is a frozen view; it does not refresh as EVE moves. Capture again before loading a new view.

The view requires the same active private creative session and exactly the captured terrain revision. It stops on block revisions, stale captures, link loss, world changes or unsupported effects. Use Fast/Fancy graphics without Iris, and a calm player without status effects. Camera roll, full translucency and other renderer replacements are unsupported. The window may use a different aspect ratio: the EVE layer covers the matching portion of Minecraft's projection instead of stretching.

The rendering branch corrects the provisional coordinate basis to **Unreal `(X,Y,Z)` → Minecraft `(X,Z,Y)`**, with the configured units-per-block conversion. Both collision import and exported feet use this basis. The native image is not mirrored, preserving EVE's visible handedness. Update both mods together and start a fresh session; do not mix the earlier negative-horizontal-axis build with this renderer.

### Continuous HD composition

After `/stellar start`, F5 and F8 have established the terrain link, use **`/stellarview play`** in Minecraft for the native image and controller bridge. **`/stellarview livehd`** requests the same image stream without forwarding controls. Keep the native terrain renderer disabled. **`/stellarview off`** stops the request and restores the normal camera; **`/stellarview status`** reports accepted image cadence separately from Minecraft FPS.

The continuous path captures at the Minecraft framebuffer dimensions when the run starts, preserving aspect and limiting the image to 3840×2160. A later window resize preserves the captured aspect; start a new run to change capture resolution. Three persistent capture components retain their rendering state. One synchronous depth warmup establishes resource readiness; subsequent frames use the engine's asynchronous image queue. The version-pinned native adapter intercepts only this bridge's registered jobs before image encoding, combines display RGB, coverage and depth, and publishes complete frame-v5 bundles through a file-backed shared mapping. No PNG/BMP/EXR image files are written by accepted continuous raw jobs. Existing still/legacy paths retain their encodings.

The transport adapts [SkyCraft's](https://github.com/chasmlol/SkyCraft) three-buffer ownership exchange. It works across a Windows process under Proton and native Linux Java: each side holds a private slot, and one atomic exchange transfers the middle slot. The Java reader copies an acquired frame before releasing its slot; the render thread reuses its color/depth textures. A new capture run creates a fresh private mapping, never resets one that another process may still use, and fails closed on invalid dimensions, identity, ownership or stale progress. See [third-party notices](THIRD-PARTY-NOTICES.md).

Raw capture removes image encoding, image-file polling and image decoding. The engine still performs its original GPU surface readback; this implementation is not a zero-copy GPU link and does not establish a 60 FPS capture rate. Persistent targets and framebuffer-sized captures improve the prerequisites for quality, but the grouped native test must verify alpha, hair, temporal rendering and color. Iris and cross-game mob damage remain separate integrations. An unacknowledged stopped mapping is retained instead of truncating a potentially live resource; resolve the native stop or close both games before removing stale mapping files.

The displayed camera also supplies Minecraft's block and entity targeting. Interaction reach remains measured from the player, and normal Minecraft interaction handlers still perform the action. An unsupported camera offset or interaction range above 64 blocks produces no target in this local-patch implementation. Fast/Fancy graphics, the private creative terrain link and the existing rendering restrictions still apply.

### Minecraft-focused controller bridge

The optional game-local XInput adapter lets **`/stellarview play`** request the HD view and forward actual Minecraft keyboard/mouse input through Stellar Blade's native controller path. This implementation has offline protocol, lifecycle and fake-host checks; native gameplay and background focus still need in-game acceptance. It does not implement damage between EVE and Minecraft mobs or establish Iris compatibility.

Install the matching Lua mod, Fabric jar and production **`xinput1_3.dll`** with both games closed. The adapter supports the pinned Stellar Blade executable only: it checks its full SHA-256 and the verified gameplay polling callsite. The Lua bridge requires the installed loader's `LoopInGameThreadWithDelay` API; its terrain and capture timers execute directly on the game thread. It forwards physical input through the system `xinput1_4.dll`, preserving other controller slots. When slot0 has no physical controller, it reports a connected neutral pad so the engine continues polling. Existing UE4SS and Xion loader files remain separate.

```sh
python tools/input_proxy.py install \
  --game-exe '/path/to/StellarBlade/SB/Binaries/Win64/SB-Win64-Shipping.exe' \
  --dll '/path/to/build/xinput1_3.dll' \
  --bridge-dir "$HOME/.local/share/stellar-blade-minecraft/bridge"
```

The installer refuses foreign or modified adapter files and records ownership for `python tools/input_proxy.py remove --game-exe '/path/to/SB-Win64-Shipping.exe'`. It does not change Steam launch options. Proton must load this native DLL: add `xinput1_3=n,b` to the game's existing `WINEDLLOVERRIDES` entries, retaining its other overrides. Never install `stellar_input_fixture.dll`; that build is exclusively for the isolated fake host, and the installer rejects it.

After the existing `/stellar start`, F5 and F8 setup, run `/stellarview play`, close chat and release held controls. Forwarding starts only after a fresh displayed HD frame, a matching native permit and a neutral controller acknowledgement. **B** toggles EVE/build mode and is rebindable in Minecraft's Controls menu; `/stellarview mode eve` and `/stellarview mode build` are equivalent. EVE mode forwards configured Minecraft movement, jump, sneak, sprint, attack and use bindings as left stick, A, B, L3, X and LB. Six additional controller buttons—Y, RB, LT, RT, R3 and D-pad up—are initially unbound and can be assigned there. The actions those buttons perform follow Stellar Blade's own controller settings.

Build mode holds EVE's movement/actions neutral, keeps native camera look, and gives Minecraft normal block/entity clicks and sneak interactions. Inventory, chat, hotbar and other screens remain Minecraft-owned. Mouse look uses a right-stick conversion; **`/stellarview gain <1..32767>`** adjusts its sensitivity (default2500), which requires game calibration. **`/stellarview off`** stops input and rendering; F11 also revokes native permission before returning EVE. Menus, lost focus, stale data, native pause and non-playable sequences neutralize forwarded controls; held keys require release before resuming.

Input is independent of image-write completion. A bounded ordered queue retains each press/release with its stick direction until a verified native gameplay poll acknowledges it. Device-detection queries cannot consume these edges. Expired or conflicting data stops the old control epoch; newer heartbeat traffic cannot revive an old held action. The adapter's acknowledgement proves delivery to the native polling boundary, not execution or damage from a skill.

### Bounded animated preview

The animated preview reuses the same 320×180 color, mask, depth and camera bundle. It requests at most one capture per 200 ms bridge callback, with no catch-up queue. Synchronous native exports and temporary capture allocation can cause stutters; this first diagnostic is limited to 300 published frames or 60 seconds, whichever comes first. It does not establish a playable frame rate.

1. Start the terrain link with `/stellar start`, then use F5 and F8 in Stellar Blade to import and enter the lab.
2. Press **F1** in Stellar Blade to start capture (`sbmc live`). F1 toggles it off; `sbmc liveoff` also stops it.
3. Run **`/stellarview live`** in Minecraft. Keep Stellar Blade focused for EVE's native controls, with Minecraft visible alongside it when possible. No keyboard or mouse forwarding is installed.
4. Use **`/stellarview status`** to inspect the number of accepted frames and observed rate. This is the transfer rate, not Minecraft's rendering FPS.
5. Use **`/stellarview off`** to restore the normal Minecraft camera. F11 in Stellar Blade stops capture, returns EVE and removes the lab.

Minecraft reads and decodes frames on a single background worker and keeps at most one pending decoded frame. Texture uploads and the coherent camera/color/depth swap happen on the render thread. A terrain revision temporarily hides the old image until a matching frame arrives. Session or world changes stop the preview. After startup, two seconds without a fresh generation also stop it; receiving the same frame again does not extend that deadline. Starting the reader without a producer times out after five seconds.

Stopping native capture invalidates its published frame and cleans up the images owned by that Lua session. It never restarts automatically after an error or cleanup failure. The still commands retain their separate 30-second lifetime. Hair, transparency, independent accessories and lighting retain the limitations of the still experiment.

## Native textured-block probe

This probe evaluates the alternative rendering direction: drawing Minecraft terrain inside Stellar Blade so EVE stays in the game's normal renderer. Her image is not captured or transferred in this mode. The first capability check shows one temporary stone-textured cube; it does not yet import the Minecraft world's visual geometry.

Provide the vanilla 16×16 stone texture from your own Minecraft 1.21.1 installation as `minecraft-stone.png` in the private bridge directory. No Minecraft assets are included with this project. For example, with both games closed, replace the client JAR path below:

```sh
python - <<'PY'
from pathlib import Path
from zipfile import ZipFile

with ZipFile('/path/to/minecraft-1.21.1-client.jar') as client:
    texture = client.read('assets/minecraft/textures/block/stone.png')
destination = Path.home() / '.local/share/stellar-blade-minecraft/bridge/minecraft-stone.png'
with destination.open('xb') as output:
    output.write(texture)
PY
```

Load EVE in a clear outdoor area and press **Shift+F7** (`sbmc texture`). A one-block cube appears approximately 2.5 blocks ahead of her. It has no collision and does not move EVE. The command toggles the cube off; **F11** also removes it while performing the usual capture cleanup and guarded return. Changed pawn/world context triggers cleanup. Failed cleanup retains ownership and blocks replacement until it succeeds.

Only the temporary cube receives a new material instance and imported texture. The game’s shared material is unchanged. An opaque widget material supplies the initial texture-sampling test, so lighting and shading are not claimed to match Minecraft. Missing native APIs or a suitable material stop the probe with a diagnostic. Successful parameter assignment is not proof of correct pixels; inspect the actual cube while rotating the camera.

## Collision probe

The UE4SS Lua mod creates seven temporary collision volumes: an elevated floor, four boundary walls, a low step and a removable obstacle. EVE's native movement is left unchanged. Creation does not move her; entering the fixture is a separate command. Returning restores her original position before deleting the supporting floor.

The probe uses Unreal's `TriggerBox` and reflected collision APIs through the existing UE4SS installation. It does not replace the mod loader or the game's executables. It starts idle and does not save the game.

Optional cube visuals use the game's `/Engine/BasicShapes/Cube.Cube` asset if present. Debug outlines are also requested, but shipping builds may omit them. Missing visuals do not establish missing collision, and visible cubes do not establish working collision.

## Tested configuration

The collision fixture has been exercised with Stellar Blade Steam build `24463856`, the installed game-specific UE4SS `v3.0.1 Beta #0` (`08a0733`), and Proton `proton-cachyos-11.0-20260703-slr-x86_64_v3`. EVE can stand on the added floor and is blocked by the added obstacles with her native controller. The obstacle can be removed/recreated, and return/cleanup restores the original position before removing the floor.

The local Fabric baseline above has also been exercised with a creative flat world: real terrain was imported, Minecraft reported active EVE position control, and block edits reached Stellar Blade with matching revision acknowledgements. A placed full block blocked EVE, supported native climbing/jumping onto it, and became traversable after removal. Expiring the Minecraft heartbeat returned EVE to the original level and removed the temporary scene. This validates the bounded experiment, not rendering, combat integration, general terrain streaming or playable latency.

The native still renderer has been exercised in the same setup. EVE's complete cutout was captured independently of the block, then Minecraft's world depth correctly hid her lower body behind that block. The captured camera and projection placed her in the Minecraft world, without the vanilla avatar. `/stellarview off` restored the normal camera, and F11 returned EVE and removed the lab. The test used 320×180 frames; continuous animation, higher-resolution performance, complete hair/transparency, independent accessories and matched lighting remain unverified.

PNG decoding uses Minecraft's stream API rather than its byte-array overload, which allocates compressed data on a small native thread stack. A real capture larger than 64 KiB exposed that limit. The corrected path passed a generated 230,802-byte PNG regression through the actual native decoder, then loaded the larger game capture successfully.

Stellar Blade extends `Actor.SetActorEnableCollision` with an additional `inFastUpdate` boolean. This prototype uses that game-specific signature. Other game or loader versions need their own compatibility check.

## Install and remove

Requirements: Stellar Blade PC, its compatible UE4SS installation, and Python 3 for deployment. The source can be syntax-checked and its lifecycle tested with Lua without launching either game.

Close the game before installing or removing the probe. Pass the actual UE4SS Mods directory:

```sh
python tools/deploy.py install --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods'
```

Only `StellarCollisionProbe/` is added. Existing directories are refused, including a previously installed version. The deployment manifest tracks ownership. To remove an unchanged installation:

```sh
python tools/deploy.py remove --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods'
```

If removal reports changes, inspect them before deleting anything. Neither command edits `mods.txt`, loader DLLs, Steam options or other mods. Remove the owned old version before installing an update.

## Run the experiment

Use a backed-up test save and a clear outdoor location. Do not save, travel, load another save or reload Lua while inside the fixture. The test does not suppress the game's autosave system; retain the external save backup.

| Keys | Console equivalent | Action |
|---|---|---|
| Shift+F7 | `sbmc texture` | Toggle the non-colliding native stone-texture probe |
| Shift+F8 | `sbmc visuals` | Toggle the bounded native Minecraft terrain renderer after entering the link |
| F1 | `sbmc live` / `sbmc liveoff` | Toggle the bounded animated capture while EVE is in the linked lab |
| F4 | `sbmc framehd` | Capture one 1280×720 color/mask/depth frame for the Minecraft still view |
| F5 | `sbmc mirror` | Import the active Minecraft terrain when bridge configuration is installed |
| F6 | `sbmc status` | Log player, world, capsule and position; refresh optional markers |
| F7 | `sbmc create` | Create the fixture 600 Unreal units above the current player position |
| F8 | `sbmc enter` | Move EVE to the fixture once; native controls then take over |
| F9 | `sbmc toggle` | Remove or recreate only the obstacle |
| F11 | `sbmc restore` | Return EVE to her original position and remove the fixture |

These shortcuts do not hold Ctrl, which can activate aiming in Stellar Blade. F10 is left to the existing console binding.

Read `SB/Binaries/Win64/ue4ss/UE4SS.log` for lines prefixed `[StellarCollisionProbe]`. Unsupported APIs stop the action with a diagnostic. If a return fails, the supporting floor stays in place so the return can be retried.

Start with status, then create. Verify that the elevated fixture is clear of existing level geometry before entering. While inside, test standing, walking/running, jumping, landing and dodging against the obstacles from multiple directions. Remove the central obstacle, cross its location, move clear of it, and recreate it. Return before saving or leaving the area. Repeat after restarting the game.

A real pass requires EVE to be supported only by the added floor, to collide with the added obstacles, and to retain native movement. Cube visibility, successful actor creation, a downward ray hit and automated lifecycle tests are not sufficient proof. The default fixture's dimensions are experimental Unreal units, not a calibrated Minecraft block scale.

## Checks

```sh
lua tests/probe_test.lua
lua tests/adapter_test.lua
lua tests/terrain_test.lua
lua tests/protocol_test.lua
lua tests/bridge_test.lua
lua tests/capture_test.lua
lua tests/capture_stream_test.lua
lua tests/capture_stream_main_test.lua
lua tests/capture_session_test.lua
lua tests/raw_capture_test.lua
lua tests/control_permit_test.lua
lua tests/visual_probe_test.lua
lua tests/visual_probe_adapter_test.lua
lua tests/scene_protocol_test.lua
lua tests/scene_geometry_test.lua
lua tests/native_scene_test.lua
lua tests/scene_adapter_test.lua
python -m unittest discover -s tests -p 'test_*.py' -v
luac -p stellar/Mods/StellarCollisionProbe/Scripts/*.lua
cd minecraft && ./gradlew build
```

The portable native input, raw frame ownership and task-layout checks use CMake and a C++17 compiler:

```sh
cmake -S native -B build/input -DCMAKE_BUILD_TYPE=Release
cmake --build build/input
ctest --test-dir build/input --output-on-failure
```

To build the production Windows DLL on Linux, configure a separate build with the installed clang-cl/xwin Windows SDK toolchain using `-DCMAKE_TOOLCHAIN_FILE=/path/to/clang-cl-msvc.cmake`. `STELLAR_INPUT_BUILD_FIXTURE=ON` additionally builds an isolated Windows test host and fixture adapter; these are not game artifacts. The production DLL defaults to the verified game identity, with no environment-variable bypass.

The Lua tests use a small engine boundary substitute to exercise scene ownership, transactional replacement, failed creation/return, changed worlds, cleanup retries and bounded capture scheduling. Java checks cover real file publication, pose gating, collision merging, shared protocol fixtures, worker cancellation and image decoding. Engine API availability, graphics, collision responses, movement interception and controller behavior require in-game validation.
