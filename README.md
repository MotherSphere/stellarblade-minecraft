# Stellar Blade × Minecraft

An experimental bridge project for playing EVE with her native movement and combat in a live Minecraft Java world on Linux, with Stellar Blade running through Proton.

The current deliverable is **only a Stellar Blade collision probe**. There is no Minecraft integration yet. Automated checks cover fixture lifecycle and deployment; they do not prove that the installed game supports these collisions.

## Collision probe

The UE4SS Lua mod creates seven temporary collision volumes: an elevated floor, four boundary walls, a low step and a removable obstacle. EVE's native movement is left unchanged. Creation does not move her; entering the fixture is a separate command. Returning restores her original position before deleting the supporting floor.

The probe uses Unreal's `TriggerBox` and reflected collision APIs through the existing UE4SS installation. It does not replace the mod loader or the game's executables. It starts idle and does not save the game.

Optional cube visuals use the game's `/Engine/BasicShapes/Cube.Cube` asset if present. Debug outlines are also requested, but shipping builds may omit them. Missing visuals do not establish missing collision, and visible cubes do not establish working collision.

## Tested configuration

The collision fixture has been exercised with Stellar Blade Steam build `24463856`, the installed game-specific UE4SS `v3.0.1 Beta #0` (`08a0733`), and Proton `proton-cachyos-11.0-20260703-slr-x86_64_v3`. EVE can stand on the added floor and is blocked by the added obstacles with her native controller. The obstacle can be removed/recreated, and return/cleanup restores the original position before removing the floor.

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
python -m unittest discover -s tests -p 'test_*.py' -v
luac -p stellar/Mods/StellarCollisionProbe/Scripts/main.lua
luac -p stellar/Mods/StellarCollisionProbe/Scripts/probe.lua
```

The Lua tests use a small engine boundary substitute to exercise the real fixture lifecycle, including failed creation, failed return, changed worlds and cleanup retries. Engine API availability, graphics, collision responses and controller behavior require in-game validation.
