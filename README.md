# Stellar Blade × Minecraft

An experimental bridge project for playing EVE with her native movement and combat in a live Minecraft Java world on Linux, with Stellar Blade running through Proton.

The current build includes a Stellar Blade collision probe and an experimental **local Minecraft terrain and position link**. Minecraft exports real block collision shapes; Stellar Blade imports them into a bounded elevated lab and sends EVE's native position back to the local Minecraft player. EVE rendering, camera composition, cross-game combat and general world streaming are not implemented yet.

## Minecraft terrain link

Use **Minecraft Java 1.21.1**, **Fabric Loader 0.19.5**, **Fabric API 0.116.17+1.21.1**, and **Java 21**. This experiment accepts only a private creative singleplayer world, without LAN players, fluids or vehicles. Start with a separate flat test world. Disable `pauseOnLostFocus` in this instance's `options.txt` so its integrated server keeps publishing while Stellar Blade has focus. Keep Minecraft in the world, rather than its pause menu.

If Minecraft still opens its pause menu when switching games, return to the world and use **F3+P** to toggle automatic pausing off in the running game. A file edit alone does not change an already running instance's options. Check that terrain timestamps keep advancing after switching to Stellar Blade.

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

1. Stand on a full block in the creative test world and run `/stellar start`. `/stellar status` reports the revision and position link. `/stellar stop` ends the session.
2. In Stellar Blade, load a backed-up save in a clear outdoor location. Press **F5** (`sbmc mirror`) to import the terrain. Check that the elevated scene is clear of the game's existing geometry.
3. Press **F8** to enter once. EVE's native controller owns movement. Minecraft's local player follows her feet position; Minecraft mouse look remains available for block editing during this phase.
4. Edit blocks in the fixed patch to test collision updates. A new block intersecting EVE is deferred until she moves away. Imported replacement collision is prepared before the old collision is removed.
5. Press **F11** to return EVE and remove the temporary scene. Stop the Minecraft session before closing either game. If the connection expires, the mod attempts a guarded return and retains support if that return fails.

The sampled patch is nine blocks wide, six blocks high and nine blocks deep, fixed around the initial Minecraft anchor. At most 256 merged collision boxes are accepted. The provisional scale is 100 Unreal units per block. Separate lab guards contain EVE at the patch boundary; they are additional experimental geometry, not Minecraft blocks. Visible cubes are diagnostic visuals from Stellar Blade, not Minecraft textures or a finished passthrough renderer.

Transport uses bounded versioned text snapshots with session IDs, revisions, timestamps and complete-message footers. Java publishes terrain with atomic replacement; Lua alternates two EVE files because the Windows C runtime cannot rename over an existing destination. Missing or stale EVE packets release Minecraft's position override. No network service or additional native transport DLL is used.

## Native image capture experiment

With bridge configuration installed, **F4** (`sbmc frame`) requests a paired 320×180 capture through temporary Unreal scene-capture actors showing EVE's components. Display color, inverse-opacity mask and scene depth use the same camera snapshot within one game-thread call. This probe does not move EVE, hide the original level or continuously capture frames. It releases the temporary actors and render targets after each request; `sbmc capturecleanup` retries cleanup if the log reports a failure.

`eve-frame.txt` in the private bridge directory names three generation-specific PNG/HDR files and records the camera, projection and context. It is published only after all exports and cleanup succeed. A failed capture preserves the previous complete frame. Files from the preceding frame of the same Lua lifetime are removed after replacement; old files from earlier launches can be removed while both games are closed.

In a private Minecraft singleplayer world, run **`/stellarpreview load`** within 30 seconds of F4 to inspect the capture in a diagnostic inset. **`/stellarpreview off`** closes it. The preview uses the display-color image and the separate inverse-opacity mask, rejecting low-coverage sky/haze pixels. Partial hair and translucency remain unsupported. The texture expires 30 seconds after capture and is removed on world change or disconnect. This is an opt-in still preview, not a live renderer, world-camera override or depth-tested composition.

The original independent probes remain available: `sbmc capture` writes `eve-capture-color.png` (raw HDR scene color clamped to RGBA8), while **F2** (`sbmc depth`) writes `eve-capture-depth.hdr`. Their descriptors are separate and do not establish a matching pair. Native tests confirmed inverse opacity in the PNG and a readable depth HDR. The PNG exporter adds trailing padding, which the publisher removes after parsing the actual IEND chunk. Independently attached weapons, the drone and effects may require additional capture membership.

## Collision probe

The UE4SS Lua mod creates seven temporary collision volumes: an elevated floor, four boundary walls, a low step and a removable obstacle. EVE's native movement is left unchanged. Creation does not move her; entering the fixture is a separate command. Returning restores her original position before deleting the supporting floor.

The probe uses Unreal's `TriggerBox` and reflected collision APIs through the existing UE4SS installation. It does not replace the mod loader or the game's executables. It starts idle and does not save the game.

Optional cube visuals use the game's `/Engine/BasicShapes/Cube.Cube` asset if present. Debug outlines are also requested, but shipping builds may omit them. Missing visuals do not establish missing collision, and visible cubes do not establish working collision.

## Tested configuration

The collision fixture has been exercised with Stellar Blade Steam build `24463856`, the installed game-specific UE4SS `v3.0.1 Beta #0` (`08a0733`), and Proton `proton-cachyos-11.0-20260703-slr-x86_64_v3`. EVE can stand on the added floor and is blocked by the added obstacles with her native controller. The obstacle can be removed/recreated, and return/cleanup restores the original position before removing the floor.

The local Fabric baseline above has also been exercised with a creative flat world: real terrain was imported, Minecraft reported active EVE position control, and block edits reached Stellar Blade with matching revision acknowledgements. A placed full block blocked EVE, supported native climbing/jumping onto it, and became traversable after removal. Expiring the Minecraft heartbeat returned EVE to the original level and removed the temporary scene. This validates the bounded experiment, not rendering, combat integration, general terrain streaming or playable latency.

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
python -m unittest discover -s tests -p 'test_*.py' -v
luac -p stellar/Mods/StellarCollisionProbe/Scripts/*.lua
cd minecraft && ./gradlew build
```

The Lua tests use a small engine boundary substitute to exercise scene ownership, transactional replacement, failed creation/return, changed worlds and cleanup retries. Java checks cover real file publication, pose gating, collision merging and the shared protocol fixtures. Engine API availability, graphics, collision responses, movement interception and controller behavior require in-game validation.
