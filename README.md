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

With bridge configuration installed, **F4** (`sbmc frame`) requests a paired 320×180 capture through temporary Unreal scene-capture actors showing EVE's components. Display color, inverse-opacity mask and scene depth use the same camera snapshot within one game-thread call. A manual request takes one frame without moving EVE or hiding the original level. It releases the temporary actors and render targets after each request; `sbmc capturecleanup` retries cleanup if the log reports a failure. Stop the animated preview before taking manual captures.

**Shift+F4** (`sbmc framehd`) requests an opt-in **1280×720 paired still** through the same lifecycle. Both mods must include HD support. Only 320×180 and 1280×720 are accepted; every color, mask and depth plane must match its descriptor and stay within 4 MiB. This is a detail and timing check, not a faster live stream. F4 and the animated preview retain their original resolution and cadence.

HD captures log start/end boundaries for native allocation, capture, export, validation, cleanup and publication. Minecraft logs CPU read/depth decoding, PNG/mask decoding and texture-upload submission times when a still is accepted. Submission time does not measure GPU completion. These diagnostics help identify the next transport improvement without claiming asynchronous readback or a playable frame rate.

`eve-frame.txt` in the private bridge directory names three generation-specific PNG/HDR files and records the camera, projection and context. It is published only after all exports and cleanup succeed. A failed capture preserves the previous complete frame. Files from the preceding frame of the same Lua lifetime are removed after replacement; old files from earlier launches can be removed while both games are closed.

In a private Minecraft singleplayer world, run **`/stellarpreview load`** within 30 seconds of F4 to inspect the capture in a diagnostic inset. **`/stellarpreview off`** closes it. The preview uses the display-color image and the separate inverse-opacity mask, rejecting low-coverage sky/haze pixels. Partial hair and translucency remain unsupported. The texture expires 30 seconds after capture and is removed on world change or disconnect. This is an opt-in still preview, not a live renderer, world-camera override or depth-tested composition.

The original independent probes remain available: `sbmc capture` writes `eve-capture-color.png` (raw HDR scene color clamped to RGBA8), while **F2** (`sbmc depth`) writes `eve-capture-depth.hdr`. Their descriptors are separate and do not establish a matching pair. Native tests confirmed inverse opacity in the PNG and a readable depth HDR. The PNG exporter adds trailing padding, which the publisher removes after parsing the actual IEND chunk. Independently attached weapons, the drone and effects may require additional capture membership.

### World composition experiment

After starting the terrain link and entering it with EVE, press F4 (320×180) or Shift+F4 (1280×720), then run **`/stellarview load`** in Minecraft within 30 seconds. Keep the native terrain diagnostic disabled. This selects the captured camera and projection and draws EVE with her captured scene depth against Minecraft's world depth. **`/stellarview off`** restores the vanilla camera. This is a frozen view; it does not refresh as EVE moves. Capture again before loading a new view.

The view requires the same active private creative session and exactly the captured terrain revision. It stops on block revisions, stale captures, link loss, world changes or unsupported effects. Use Fast/Fancy graphics without Iris, and a calm player without status effects. Camera roll, full translucency and other renderer replacements are unsupported. The window may use a different aspect ratio: the EVE layer covers the matching portion of Minecraft's projection instead of stretching.

The rendering branch corrects the provisional coordinate basis to **Unreal `(X,Y,Z)` → Minecraft `(X,Z,Y)`**, with the configured units-per-block conversion. Both collision import and exported feet use this basis. The native image is not mirrored, preserving EVE's visible handedness. Update both mods together and start a fresh session; do not mix the earlier negative-horizontal-axis build with this renderer.

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
| Shift+F4 | `sbmc framehd` | Capture one 1280×720 color/mask/depth frame for the Minecraft still view |
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

The Lua tests use a small engine boundary substitute to exercise scene ownership, transactional replacement, failed creation/return, changed worlds, cleanup retries and bounded capture scheduling. Java checks cover real file publication, pose gating, collision merging, shared protocol fixtures, worker cancellation and image decoding. Engine API availability, graphics, collision responses, movement interception and controller behavior require in-game validation.
