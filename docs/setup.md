# Setup and builds

## Supported baseline

- Linux x86-64; Minecraft Java 1.21.1, Java 21, Fabric Loader 0.19.5 and Fabric API 0.116.17+1.21.1.
- Stellar Blade Steam build 24463856. The native adapter accepts only `SB-Win64-Shipping.exe` with SHA-256 `573aaff1c9455f85ea6036ef6f6ccb0774de4164722a296276fbbfc7fa87545c`.
- A game-compatible UE4SS installation with `LoopInGameThreadWithDelay`. The development baseline is v3.0.1 Beta #0 (`08a0733`). Stock or newer loaders are not assumed interchangeable.
- Proton baseline: `proton-cachyos-11.0-20261005-slr-x86_64_v3`. Other versions require validation.

Use a separate private creative or survival Minecraft world, or a server of friends ([multiplayer](#multiplayer)), without vehicles or player effects. Fluids in the terrain window become walls. In survival, EVE can die: use `keepInventory` or an empty inventory there. Avatar mode runs with any graphics mode; Iris (with Sodium) shader packs are experimental and not yet validated in game; capture mode needs Fast/Fancy without Iris. Back up Stellar Blade saves outside the game before using the lab. The prototype does not suppress autosaves.

## Build and check

From the repository root, set `JAVA_HOME` to a Java 21 installation when it is not the default, then run:

```sh
python tools/check.py
```

This checks Lua syntax and lifecycle tests, Python installation tools, portable native tests, the Fabric build and the terrain and EVE text exchanged between Java and Lua. The JAR and the Windows DLL are reproducible (no archive timestamps, `/Brepro`): rebuilding a commit gives the same hashes, so an installed artifact can be checked against its commit. The Gradle wrapper pins Gradle 8.14.5, Loom 1.11.8 and Yarn 1.21.1+build.3. Host CMake output goes to `build/native-host`; Minecraft output goes to `minecraft/build`.

Build the Windows x64 DLL separately with an installed clang-cl/xwin Windows SDK toolchain:

```sh
cmake -S native -B build/native-windows -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=/absolute/path/to/clang-cl-msvc.cmake
cmake --build build/native-windows
```

Adding `-DSTELLAR_INPUT_BUILD_FIXTURE=ON` builds isolated Windows checks. Never install `stellar_input_fixture.dll` or the fake host in a game directory. Run fixture executables only in a separate test directory and Wine/Proton prefix. They do not establish native gameplay or GPU compatibility.

The avatar's GPU paths are checked offline, outside `tools/check.py` (they need a GPU and the private avatar pack), with `./gradlew avatarRenderCheck` in `minecraft/` (see [diagnostics](diagnostics.md#avatar-view-experimental)); its last argument `entity` renders through Minecraft's own entity programs, the path used under a shader pack.

`python tools/check.py --only windows --windows-toolchain /absolute/path/to/clang-cl-msvc.cmake --wine /path/to/wine` builds both Windows trees (`build/native-windows` and `build/native-windows-fixture`) and runs every Windows check under that Wine or Proton binary in a throwaway prefix and working directory. With `STELLAR_WINDOWS_TOOLCHAIN` and `STELLAR_WINE` set, the complete `python tools/check.py` includes it; otherwise it reports `SKIP windows`.

## Install

Close both games before changing Lua, JAR or DLL files. Keep existing UE4SS/Xion loaders, other mods, launch options and DLL overrides. Update the three bridge components together.

Copy `minecraft/build/libs/stellar-bridge-0.1.0.jar` and the matching Fabric API JAR into the test instance's `minecraft/mods/` directory, replacing only the previous bridge JAR.

Prepare the shared directory and install the Lua mod:

```sh
mkdir -p "$HOME/.local/share/stellar-blade-minecraft/bridge"
chmod 700 "$HOME/.local/share/stellar-blade-minecraft/bridge"
python tools/deploy.py install \
  --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods' \
  --bridge-dir "$HOME/.local/share/stellar-blade-minecraft/bridge"
python tools/input_proxy.py install \
  --game-exe '/path/to/StellarBlade/SB/Binaries/Win64/SB-Win64-Shipping.exe' \
  --dll "$PWD/build/native-windows/xinput1_3.dll" \
  --bridge-dir "$HOME/.local/share/stellar-blade-minecraft/bridge"
```

The scripts refuse existing foreign or modified content and record ownership manifests. The Lua installer creates `bridge_config.lua` with the Proton `Z:` path; this machine-specific file is not source. A custom Minecraft directory uses `-Dstellarbridge.directory=/absolute/path`; both sides must point to the same private directory.

Proton must load the native adapter. Retain existing overrides and add `xinput1_3=n,b` to the game's `WINEDLLOVERRIDES`, or set the equivalent game-specific Wine registry override to `native,builtin`. The installers do not change overrides or Steam options. Do not replace `dinput8.dll`, `dwmapi.dll` or UE4SS to install this adapter.

Avatar mode also needs the private avatar pack in `~/.local/share/stellar-blade-minecraft/avatar` (or `-Dstellarbridge.avatar=<dir>`), built from your own game installation and never part of this repository; see [the avatar view](diagnostics.md#avatar-view-experimental). Without it, `/stellaravatar` reports that the pack holds no EVE mesh; the capture views still work.

Follow the [main session sequence](../README.md#start-a-session). All bridge components start inactive.

## Multiplayer

The same JAR, with the matching Fabric API JAR, also loads on a Fabric server (dedicated, or a world opened to LAN). Modded clients must run the same [network protocol](../protocol/README.md#minecraft-network-protocol-1), else the server disconnects them with both versions named; players without the mod join as usual. Each modded player runs their own Stellar Blade on their own Linux PC and links it with `/stellar start`, as in singleplayer, with EVE's save in the Great Desert, the map with the lab's remote site. The server keeps every player's session and combat, and each client writes its own bridge directory. Everyone sees EVE players as vanilla player models for now.

What changes with others online:

- There is no PvP, whatever the server's `pvp` setting: EVE's blade, drone and grenades spare players, anyone's pets and anything a player rides, other players' damage never reaches her, and an arrow she perfectly parries back never hurts another player.
- A perfect dodge slows Minecraft's world only while the server holds one player. Otherwise Stellar Blade's own slowdown and the cold tint play for the dodger alone.
- While EVE cannot answer (Stellar Blade paused or in a cutscene, or the link lapsed), she is spared for 2 s, then hits land unjudged. Full immunity, and the pause menu that keeps the world running, stay singleplayer only.
- Over the internet, dodge and guard presses count when they were made, up to 150 ms of round trip (see the [protocol](../protocol/README.md#minecraft-network-protocol-1)); hits on such a player can land up to that much later.
- Each player places at most three Smart Mines.

### Testing on one PC

Two Stellar Blades cannot run on one PC, so a one-PC test pairs one EVE player with a vanilla-controlled second player:

1. **Server.** Start a local Fabric 1.21.1 server with Fabric API 0.116.17+1.21.1 and the bridge JAR from `minecraft/build/libs/` in its `mods` folder (or Loom's `runServer` in `minecraft/`). Accepting Mojang's EULA in its `eula.txt` is yours to do. In `server.properties`, set `online-mode=false`, `server-ip=127.0.0.1` and `view-distance=6`.
2. **Instance A.** The Prism instance linked to Stellar Blade, with the default bridge directory, kept in focus for Stellar Blade's input. Join `127.0.0.1` and play as in singleplayer.
3. **Instance B.** A copy of A with an offline account, `pauseOnLostFocus:false` in its `options.txt`, and JVM arguments `-Dstellarbridge.directory=<a directory of its own> -Dstellarbridge.avatar=<the pack>`. Without its own directory, B would overwrite A's `terrain.txt` and session files. B never runs `/stellar start`. Run it at render distance 6 and 30 FPS without shaders, since memory is tight with both games open.
4. **Instance C** (optional). Vanilla Minecraft 1.21.1 without Fabric, which joins as an unmodded player.

`/stellar status` on A reports the session and its combat counts, including the payloads the server refused.

## Update and remove

With both games closed, remove the unchanged owned installation before installing a replacement:

```sh
python tools/deploy.py remove \
  --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods'
python tools/input_proxy.py remove \
  --game-exe '/path/to/StellarBlade/SB/Binaries/Win64/SB-Win64-Shipping.exe'
```

Inspect any ownership refusal instead of deleting unknown files. Remove only the bridge JAR from Minecraft. If undoing a DLL override, preserve unrelated entries and restore the prior configuration.

A stopped capture mapping is retained if the native writer has not acknowledged release. Never truncate or reuse that file while either process may hold it. After both games have exited, obsolete bridge-generated captures and mapping files can be removed from the shared directory.
