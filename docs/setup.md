# Setup and builds

## Supported baseline

- Linux x86-64; Minecraft Java 1.21.1, Java 21, Fabric Loader 0.19.5 and Fabric API 0.116.17+1.21.1.
- Stellar Blade Steam build 24463856. The native adapter accepts only `SB-Win64-Shipping.exe` with SHA-256 `573aaff1c9455f85ea6036ef6f6ccb0774de4164722a296276fbbfc7fa87545c`.
- A game-compatible UE4SS installation with `LoopInGameThreadWithDelay`. The development baseline is v3.0.1 Beta #0 (`08a0733`). Stock or newer loaders are not assumed interchangeable.
- Proton baseline: `proton-cachyos-11.0-20261005-slr-x86_64_v3`. Other versions require validation.

Use a separate private creative or survival Minecraft world, or a server of friends ([multiplayer](multiplayer.md)), without vehicles or player effects. Fluids in the terrain window become walls. In survival, EVE can die: use `keepInventory` or an empty inventory there. Avatar mode runs with any graphics mode; Iris (with Sodium) shader packs are experimental and not yet validated in game; capture mode needs Fast/Fancy without Iris. Back up Stellar Blade saves outside the game before using the lab. Outside the Great Desert's remote site, the prototype does not suppress autosaves ([playing](playing.md#start-a-session)).

## Build and check

Run all offline checks from the repository root with Java 21, Lua, Python, CMake, a C++17 compiler and a Rust toolchain (1.90 or newer, with clippy and rustfmt) available. Set `JAVA_HOME` to a Java 21 installation when it is not the default, then run:

```sh
python tools/check.py
```

This checks Lua syntax and lifecycle tests, Python installation tools, portable native tests, the Fabric build, the terrain and EVE text exchanged between Java and Lua, and last the Rust installer (formatting, build, clippy and tests, including installs by the Python tools removed by the installer and the reverse; Cargo fetches its crates on the first build). When the JAR and the Windows DLL are built, the installer step embeds them with the Lua mod and Fabric API, and its tests install those real pieces on fake trees. The JAR and the Windows DLL are reproducible (no archive timestamps, `/Brepro`): rebuilding a commit gives the same hashes, so an installed artifact can be checked against its commit. The Gradle wrapper pins Gradle 8.14.5, Loom 1.11.8 and Yarn 1.21.1+build.3. Host CMake output goes to `build/native-host`; Minecraft output goes to `minecraft/build`; installer output goes to `installer/target`. Use `--only lua`, `--only python`, `--only installer`, `--only native` or `--only minecraft` for one component. The runner builds artifacts without installing them or launching games.

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

It detects Steam, Stellar Blade and its Proton prefix, UE4SS, the `xinput1_3` override, Prism (native or Flatpak) and its instances, and an existing install, including one made by the Python tools below. It checks the game executable against the pin. Then it installs into a new dedicated `Stellar Bridge 1.21.1` Prism instance, an existing Minecraft 1.21.1 instance with Fabric Loader 0.19.5 or newer (Prism or MultiMC), or a `mods` folder you type. Before writing, it lists every path it will write, the owned files it replaces and the backups it makes. Every write waits until the games are closed. It makes one verified copy of the Stellar Blade saves at the first install, and keeps the replaced set (about 1 MB, never the avatar pack) for **Roll back**. It verifies the result and records it in `~/.local/share/Colony/Stellar Bridge/state.json`. When everything installed is the release it carries, its status page offers **Up to date**, disabled, instead of **Update**. It never writes the DLL override, Steam settings or the Proton registry: when no override is found, it shows the launch options to paste into Steam. A Lua mod and adapter installed by the Python tools are owned and get updated or removed. A bridge JAR copied by hand has no ownership manifest: the installer refuses to replace it, and uninstall leaves it and says so, so remove it by hand. Refusals and errors appear in the window's language (English or French) with their path; the raw text goes to the log, `~/.cache/Colony/Stellar Bridge/stellar-bridge.log`. See [the installer's README](../installer/README.md) for each page and operation, and for adding it to the desktop menu with its logo.

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

Follow the [main session sequence](playing.md#start-a-session). All bridge components start inactive.

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
