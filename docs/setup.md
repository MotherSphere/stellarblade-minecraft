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

This checks Lua syntax and lifecycle tests, Python installation tools, portable native tests, the Fabric build, the terrain and EVE text exchanged between Java and Lua, and last the Rust installer (formatting, build, clippy and tests, including installs by the Python tools removed by the installer and the reverse; Cargo fetches its crates on the first build). When the JAR and the Windows DLL are built, the installer step embeds them with the Lua mod and Fabric API, and its tests install those real pieces on fake trees. The JAR and the Windows DLL are reproducible (no archive timestamps, `/Brepro`): rebuilding a commit gives the same hashes, so an installed artifact can be checked against its commit. The Gradle wrapper pins Gradle 8.14.5, Loom 1.11.8 and Yarn 1.21.1+build.3. Host CMake output goes to `build/native-host`; Minecraft output goes to `minecraft/build`; installer output goes to `installer/target`.

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

### Installer payload

The installer carries the bridge pieces in its binary: the Lua mod from `stellar/Mods/StellarCollisionProbe`, `build/native-windows/xinput1_3.dll`, `minecraft/build/libs/stellar-bridge-0.1.0.jar` and Fabric API 0.116.17+1.21.1 with its licence, taken from the Gradle cache where Loom put it for the Minecraft build. After a complete check (so the JAR and the DLL match the commit), stage and embed them:

```sh
python tools/payload.py /path/to/new/payload
SBMC_PAYLOAD=/path/to/new/payload cargo build --release --locked --manifest-path installer/Cargo.toml
```

`tools/payload.py` checks Fabric API against its pinned SHA-512 (or takes `--fabric-api /path/to/the.jar`), refuses a machine-specific `bridge_config.lua`, and writes `payload.json` with every file's SHA-256 and the release name (`<installer version>+<commit>`, with `.dirty` when tracked files differ from the commit). The installer checks every file against that list, the Fabric API pin and the adapter's PE header when it loads its payload; `stellar-bridge-installer --version` names the release it carries. Refresh the payload the same way after any change to the Lua mod, the JAR or the DLL. Without `SBMC_PAYLOAD` the installer builds without a payload and installs nothing.

## Installer

Stellar Bridge (`installer/`) installs, updates, repairs, rolls back and removes the three bridge components, plus Fabric API, through a window. It is built with its payload as described above, or taken from a release. Close Stellar Blade and Minecraft, then run:

```sh
installer/target/release/stellar-bridge-installer            # the window
installer/target/release/stellar-bridge-installer --dry-run  # every plan, nothing written
```

It detects Steam, Stellar Blade and its Proton prefix, UE4SS, the `xinput1_3` override, Prism (native or Flatpak) and its instances, and an existing install, including one made by the Python tools below. It checks the game executable against the pin. Then it installs into a new dedicated `Stellar Bridge 1.21.1` Prism instance, an existing Minecraft 1.21.1 instance with Fabric Loader 0.19.5 or newer (Prism or MultiMC), or a `mods` folder you type. Before writing, it lists every path it will write, the owned files it replaces and the backups it makes. Every write waits until the games are closed. It makes one verified copy of the Stellar Blade saves at the first install, and keeps the replaced set (about 1 MB, never the avatar pack) for **Roll back**. It verifies the result and records it in `~/.local/share/Colony/Stellar Bridge/state.json`. It never writes the DLL override, Steam settings or the Proton registry: when no override is found, it shows the launch options to paste into Steam. A Lua mod and adapter installed by the Python tools are owned and get updated or removed. A bridge JAR copied by hand has no ownership manifest: the installer refuses to replace it, and uninstall leaves it and says so, so remove it by hand. The log is `~/.cache/Colony/Stellar Bridge/stellar-bridge.log`. See [the installer's README](../installer/README.md) for each page and operation.

## Install

The manual way, with the repository's Python tools. Close both games before changing Lua, JAR or DLL files. Keep existing UE4SS/Xion loaders, other mods, launch options and DLL overrides. Update the three bridge components together.

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

The same JAR, with the matching Fabric API JAR, also loads on a Fabric server (dedicated, or a world opened to LAN). Modded clients must run the same [network protocol](../protocol/README.md#minecraft-network-protocol), else the server disconnects them with both versions named; players without the mod join as usual. Each modded player runs their own Stellar Blade on their own Linux PC and links it with `/stellar start`, as in singleplayer, with EVE's save in the Great Desert, the map with the lab's remote site. The server keeps every player's session and combat, and each client writes its own bridge directory. A modded client with its own avatar pack draws every other EVE player's EVE from her network poses with that pack: an outfit piece the pack lacks is replaced by its core mesh of the same role (body, hair, ponytail, face, drone) or left out (accessories, weapons). Players without the mod or without a pack, and an EVE whose body cannot be drawn, show as vanilla player models.

What changes with others online:

- There is no PvP, whatever the server's `pvp` setting: EVE's blade, drone and grenades spare players, anyone's pets and anything a player rides, other players' damage never reaches her, and an arrow she perfectly parries back never hurts another player.
- A perfect dodge slows Minecraft's world only while the server holds one player. Otherwise Stellar Blade's own slowdown and the cold tint play for the dodger alone.
- While EVE cannot answer (Stellar Blade paused or in a cutscene, or the link lapsed), she is spared for 2 s, then hits land unjudged. Full immunity, and the pause menu that keeps the world running, stay singleplayer only.
- Over the internet, dodge and guard presses count when they were made, up to 150 ms of round trip (see the [protocol](../protocol/README.md#minecraft-network-protocol)); hits on such a player can land up to that much later.
- Each player places at most three Smart Mines.
- Each EVE player's client uploads her pose stream, about 0.4 Mbit/s, and a client with an avatar pack receives about as much per EVE player within 64 blocks (0.08 Mbit/s beyond); a client without a pack receives none. The server caps what it relays to each player at 512 KiB/s, set in KiB with `-Dstellarbridge.relayBudget` on the server (see the [network pose](../protocol/README.md#network-pose)).

### Testing on one PC

Two Stellar Blades cannot run on one PC, so a one-PC test pairs one EVE player with a second player who plays back a recording of her:

1. **Server.** In `minecraft/`, `./gradlew runServer` starts a development server with the bridge and Fabric API in `run/server/` (or use a Fabric 1.21.1 server with Fabric API 0.116.17+1.21.1 and the bridge JAR from `minecraft/build/libs/` in its `mods` folder). Accepting Mojang's EULA in its `eula.txt` is yours to do. In `server.properties`, set `online-mode=false`, `enforce-secure-profile=false`, `server-ip=127.0.0.1` and `view-distance=6`.
2. **Instance A.** The Prism instance linked to Stellar Blade, with the default bridge directory, kept in focus for Stellar Blade's input. Add `-Dstellarbridge.netTools=true` to its JVM arguments for the network tools below. Join `127.0.0.1` and play as in singleplayer.
3. **Instance B.** In `minecraft/`, `./gradlew runClient` starts an offline development client in `run/client/` whose bridge directory is `run/client/bridge/`, with the network tools on (or use a copy of A with an offline account and the JVM arguments `-Dstellarbridge.directory=<a directory of its own> -Dstellarbridge.avatar=<the pack> -Dstellarbridge.netTools=true`). Without its own directory, B would overwrite A's `terrain.txt` and session files. B never runs `/stellar start`. It reads the avatar pack from the default place unless `-Dstellarbridge.avatar` says otherwise. Set `pauseOnLostFocus:false`, render distance 6 and 30 FPS without shaders, since memory is tight with both games open. The development client needs Minecraft's assets in Gradle's cache; Loom downloads them on the first run unless they are already there.
4. **Instance C** (optional). Vanilla Minecraft 1.21.1 without Fabric, which joins as an unmodded player.

`/stellar status` on A reports the session and its combat counts, including the payloads the server refused.

The network tools, registered only with `-Dstellarbridge.netTools=true`, stand in for a second Stellar Blade:

- `/stellaravatar echo` on A toggles the echo: A's own network poses, as they would leave for the server, come back to A as another EVE 3 blocks along +X and 1 s late, through the same decoder, clock floor, interpolation and drawing as another player's. It works in singleplayer too.
- `/stellaravatar netrecord` on A records A's network poses for 60 s (again stops it early) into `eve-netrecord.bin` in A's bridge directory. Copy that file to B's (`run/client/bridge/`).
- `/stellaravatar netplay [file]` on B sends that recording through the server as B's own EVE, in a loop, placed so her first pose stands where B stands; again stops it. A then sees B as that EVE and B sees A live.

Grouped test for the remote EVEs:

1. A, linked and anchored, turns the echo on: the echo EVE follows A's moves 1 s later, 3 blocks aside, without stutter; `/stellaravatar status` shows `Remote echo` drawn in full. Turning it off removes it.
2. A records 60 s of moves, sword attacks and dodges with `netrecord`; B plays it back with `netplay`. A sees B's EVE replay them where B stood when the playback started, with B's name tag above her and B's vanilla model hidden while B stays within 8 blocks of her (the playback does not follow B; walking B further shows B's vanilla model again); B sees A's EVE live. With B at the playback spot, A walks away from B: past 20 blocks B's EVE loses her face morphs, trail and effects; past 64 blocks she moves at 2 poses a second. `/stellaravatar status` on both shows each remote's poses a second, KB/s, clock floor, display delay and stalls, and the server's relay line.
3. Compare sword arcs at the default 20 poses a second and at `-Dstellarbridge.poseRate=30` on A.
4. B without a pack (`-Dstellarbridge.avatar=<a directory that does not exist>`, restart B): A's EVE shows as a vanilla model on B. With a copy of the pack lacking A's suit, B draws A's EVE in the core suit, and `/stellaravatar status` names the stand-in.
5. Restart B with `-Dstellarbridge.clockOffsetMs=3000` (development only: B's network clock runs 3 s ahead, both for the poses it plays back and for the arrivals it measures). A's EVE stays smooth on B and B's playback stays smooth on A; in `/stellaravatar status` the clock floor of each moves by about 3000 ms and the display delay does not.
6. Death and respawn, postponed from the first multiplayer test: A dies and respawns at the anchor without a relink while B watches; B sees A's EVE again as soon as her poses flow after the respawn, and a vanilla model only while they do not (3 s without a pose). A disconnects, and Stellar Blade returns EVE within 5 s; A's EVE leaves B's view with A's player.

## Update and remove

The installer updates in place (keeping the replaced set for **Roll back**) and uninstalls from its detect page. With the Python tools, close both games and remove the unchanged owned installation before installing a replacement:

```sh
python tools/deploy.py remove \
  --mods-dir '/path/to/StellarBlade/SB/Binaries/Win64/ue4ss/Mods'
python tools/input_proxy.py remove \
  --game-exe '/path/to/StellarBlade/SB/Binaries/Win64/SB-Win64-Shipping.exe'
```

Inspect any ownership refusal instead of deleting unknown files. Remove only the bridge JAR from Minecraft. If undoing a DLL override, preserve unrelated entries and restore the prior configuration.

A stopped capture mapping is retained if the native writer has not acknowledged release. Never truncate or reuse that file while either process may hold it. After both games have exited, obsolete bridge-generated captures and mapping files can be removed from the shared directory.
