# Setup and builds

## Supported baseline

- Linux x86-64, with Stellar Blade under Proton; or Windows 11 x64 (Windows 10 22H2 as a courtesy) with both games on Windows, on a local NTFS disk ([Windows](#windows)). Minecraft Java 1.21.1, Java 21, Fabric Loader 0.19.5 and Fabric API 0.116.17+1.21.1. Windows has the installer but no grouped game test yet.
- Stellar Blade Steam build 24463856. The native adapter accepts only `SB-Win64-Shipping.exe` with SHA-256 `573aaff1c9455f85ea6036ef6f6ccb0774de4164722a296276fbbfc7fa87545c`.
- A game-compatible UE4SS installation with `LoopInGameThreadWithDelay`. The development baseline is v3.0.1 Beta #0 (`08a0733`). Stock or newer loaders are not assumed interchangeable.
- Proton baseline (Linux): `proton-cachyos-11.0-20261005-slr-x86_64_v3`. Other versions require validation. On Windows, Stellar Blade runs natively and loads UE4SS v3 through `dwmapi.dll`; UE4SS 2.x used `xinput1_3.dll`, the adapter's name, and the installer refuses to replace it.

Use a separate private creative or survival Minecraft world, or a server of friends ([multiplayer](multiplayer.md)), without vehicles or player effects. Fluids in the terrain window become walls. In survival, EVE can die: use `keepInventory` or an empty inventory there. Avatar mode runs with any graphics mode; Iris (with Sodium) shader packs are experimental and not yet validated in game; capture mode needs Fast/Fancy without Iris. Back up Stellar Blade saves outside the game before using the lab. Outside the Great Desert's remote site, the prototype does not suppress autosaves ([playing](playing.md#start-a-session)).

## Build and check

Run all offline checks from the repository root with Java 21, Lua, Python, CMake, a C++17 compiler and a Rust toolchain (1.90 or newer, with clippy and rustfmt) available, plus the .NET 10 SDK for the pack builder's sidecar (without `dotnet` that step reports `SKIP eve-extract`). Fetch the sidecar's pinned CUE4Parse once with `git submodule update --init tools/eve-extract/CUE4Parse`. Set `JAVA_HOME` to a Java 21 installation when it is not the default, then run:

```sh
python tools/check.py
```

This checks Lua syntax and lifecycle tests, Python installation tools, portable native tests, the Fabric build, the terrain and EVE text exchanged between Java and Lua, [EveExtract](../tools/eve-extract/README.md) (its input checks, both self-contained builds into `build/eve-extract` with their `SHA256SUMS`, and a smoke test of each on synthetic inputs, the Windows one under Wine when `--wine` is given), and last the Rust installer (formatting, build, clippy and tests, including installs by the Python tools removed by the installer and the reverse; Cargo fetches its crates on the first build). When the JAR and the Windows DLL are built, the installer step embeds them with the Lua mod and Fabric API, and its tests install those real pieces on fake trees. The JAR and the Windows DLL are reproducible (no archive timestamps, `/Brepro`): rebuilding a commit gives the same hashes, so an installed artifact can be checked against its commit. The Gradle wrapper pins Gradle 8.14.5, Loom 1.11.8 and Yarn 1.21.1+build.3. Host CMake output goes to `build/native-host`; Minecraft output goes to `minecraft/build`; installer output goes to `installer/target`. Use `--only lua`, `--only python`, `--only installer`, `--only native`, `--only minecraft` or `--only eve-extract` for one component. The runner builds artifacts without installing them or launching games. CI runs the same check on every pull request, with the `windows` component on a Windows runner instead, and builds the releases ([releases and CI](releasing.md)).

Build the Windows x64 DLL separately with an installed clang-cl/xwin Windows SDK toolchain:

```sh
cmake -S native -B build/native-windows -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=/absolute/path/to/clang-cl-msvc.cmake
cmake --build build/native-windows
```

Adding `-DSTELLAR_INPUT_BUILD_FIXTURE=ON` builds isolated Windows checks. Never install `stellar_input_fixture.dll` or the fake host in a game directory. Run fixture executables only in a separate test directory and Wine/Proton prefix. They do not establish native gameplay or GPU compatibility.

The avatar's GPU paths are checked offline, outside `tools/check.py` (they need a GPU and the private avatar pack), with `./gradlew avatarRenderCheck` in `minecraft/` (see [diagnostics](diagnostics.md#avatar-view-experimental)); its last argument `entity` renders through Minecraft's own entity programs, the path used under a shader pack.

`python tools/check.py --only windows --windows-toolchain /absolute/path/to/clang-cl-msvc.cmake --wine /path/to/wine` builds both Windows trees (`build/native-windows` and `build/native-windows-fixture`) and runs every Windows check, and the fake host beside both DLLs, under that Wine or Proton binary in a throwaway prefix and working directory. With `STELLAR_WINDOWS_TOOLCHAIN` and `STELLAR_WINE` set, the complete `python tools/check.py` includes it; otherwise it reports `SKIP windows`. On Windows itself, `python tools/check.py --only windows` needs neither: it builds with Visual Studio's ClangCL toolset (`-A x64 -T ClangCL`, the release DLL's) and runs the checks directly, and `--only minecraft` runs the Java suites through `gradlew.bat` (the Java-Lua exchange needs `lua` on `PATH`, else it reports `SKIP`). The Python tools and their checks stay Linux-only. CI's Windows bridge job runs both on a Windows runner, then the pose mapping between Java and the native fixture, and Java's replacements beside a C reader that shares files as Lua does ([releases and CI](releasing.md)).

### Installer payload

The installer carries the bridge pieces in its binary: the Lua mod from `stellar/Mods/StellarCollisionProbe`, `build/native-windows/xinput1_3.dll`, `minecraft/build/libs/stellar-bridge-<version>.jar` and Fabric API 0.116.17+1.21.1 with its licence, taken from the Gradle cache where Loom put it for the Minecraft build. After a complete check (so the JAR and the DLL match the commit), stage and embed them:

```sh
python tools/payload.py /path/to/new/payload
SBMC_PAYLOAD=/path/to/new/payload cargo build --release --locked --manifest-path installer/Cargo.toml
```

`tools/payload.py` checks Fabric API against its pinned SHA-512 (or takes `--fabric-api /path/to/the.jar`), refuses a machine-specific `bridge_config.lua`, and writes `payload.json` with every file's SHA-256 and the release name (`<installer version>+<commit>`, with `.dirty` when tracked files differ from the commit). The installer checks every file against that list, the Fabric API pin and the adapter's PE header when it loads its payload; `stellar-bridge-installer --version` names the release it carries. Refresh the payload the same way after any change to the Lua mod, the JAR or the DLL. Without `SBMC_PAYLOAD` the installer builds without a payload and installs nothing.

## Installer

Stellar Bridge (`installer/`) installs, updates, repairs, rolls back and removes the three bridge components, plus Fabric API, through a window. It is built with its payload as described above, or taken from a release (`stellar-bridge-installer-linux-x86_64`, or `stellar-bridge-installer-windows-x86_64.exe` on Windows). Close Stellar Blade and Minecraft, then run:

```sh
installer/target/release/stellar-bridge-installer            # the window
installer/target/release/stellar-bridge-installer --dry-run  # every plan, nothing written
```

It detects Steam, Stellar Blade and its Proton prefix, UE4SS, the `xinput1_3` override, Prism (native or Flatpak) and its instances, and an existing install, including one made by the Python tools below. It checks the game executable against the pin. Then it installs into a new dedicated `Stellar Bridge 1.21.1` Prism instance, an existing Minecraft 1.21.1 instance with Fabric Loader 0.19.5 or newer (Prism or MultiMC), or a `mods` folder you type. Before writing, it lists every path it will write, the owned files it replaces and the backups it makes. Every write waits until the games are closed. It makes one verified copy of the Stellar Blade saves at the first install, and keeps the replaced set (about 1 MB, never the avatar pack) for **Roll back**. It verifies the result and records it in `~/.local/share/Colony/Stellar Bridge/state.json`. When everything installed is the release it carries, its status page offers **Up to date**, disabled, instead of **Update**. It never writes the DLL override, Steam settings or the Proton registry: when no override is found, it shows the launch options to paste into Steam. A Lua mod and adapter installed by the Python tools are owned and get updated or removed. A bridge JAR copied by hand has no ownership manifest: the installer refuses to replace it, and uninstall leaves it and says so, so remove it by hand. Refusals and errors appear in the window's language (English or French) with their path; the raw text goes to the log, `~/.cache/Colony/Stellar Bridge/stellar-bridge.log`. An EVE player also builds the [avatar pack](#avatar-pack) from its status page. See [the installer's README](../installer/README.md) for each page and operation, for server folders (`--server <dir>`, also without a window) and for adding it to the desktop menu with its logo.

On Windows the installer finds Steam where the registry says it is (it reads the registry and writes none) and Prism in `%APPDATA%\PrismLauncher`; MultiMC and other launchers are picked by folder, as on Linux. Windows loads `xinput1_3.dll` from the game folder, so there is no DLL override and no launch option to set. The bridge folder is `%USERPROFILE%\.local\share\stellar-blade-minecraft\bridge`, where the bridge JAR looks by default; it must be on a local NTFS disk, never a network share. UE4SS's Lua opens files by narrow path, so the installer writes one ASCII path to both `bridge_config.lua` and the adapter's `stellar-input-directory.txt`: the folder itself, or for a profile such as `C:\Users\Clément` its 8.3 short form; without one it refuses and names the folder. Its data, preferences and log live in `%LOCALAPPDATA%\Colony\Stellar Bridge\`. Run Stellar Blade borderless or windowed so it keeps running beside Minecraft. The Python tools below are Linux developer tools.

## Install

The manual way, with the repository's Python tools. Close both games before changing Lua, JAR or DLL files. Keep existing UE4SS/Xion loaders, other mods, launch options and DLL overrides. Update the three bridge components together.

Copy `minecraft/build/libs/stellar-bridge-<version>.jar` and the matching Fabric API JAR into the test instance's `minecraft/mods/` directory, replacing only the previous bridge JAR.

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

Avatar mode also needs the private avatar pack ([below](#avatar-pack)). Without it, `/stellaravatar` reports that the pack holds no EVE mesh; the capture views still work on Linux.

Follow the [main session sequence](playing.md#start-a-session). All bridge components start inactive.

## Avatar pack

Minecraft draws EVE from the avatar pack: her meshes (`.sbav`), baked textures (PNG), `wardrobe.txt` and `pack.txt`, in `~/.local/share/stellar-blade-minecraft/avatar` (`%USERPROFILE%\.local\share\stellar-blade-minecraft\avatar` on Windows, or `-Dstellarbridge.avatar=<dir>`). Each player builds it from their own copy of Stellar Blade with the installer. It is never part of this repository, never shipped and never uploaded: it is derived from your game files and stays on your PC. See [the avatar view](diagnostics.md#avatar-view-experimental) for how Minecraft uses it.

To build it, close Stellar Blade and Minecraft, open the installer as an EVE player and click **Build the avatar pack** on its status page (**Rebuild the avatar pack** once one exists, for instance after a game update, a mod change or new outfits). The installer checks what the build needs, asks for your consent, asks once more before it fetches Oodle, then shows the build's progress and its result ([the pages](../installer/README.md#avatar-pack)). A full build takes about 10 to 15 minutes and about 3 GB for the pack; plan for 8 GB of free disk while it runs. Your installed pack stays as it is until the new one is complete: a cancel or a failure leaves it unchanged. A later build from the same game files reuses it and exports only what is new.

The builder runs [EveExtract](../tools/eve-extract/README.md), a sidecar the installer carries and unpacks into its builder cache, checked against its pinned SHA-256, which reads the game's containers read-only; the installer then bakes the pack. The installer is about 80 MB: it contains everything the build needs except two files this project never ships:

- **The usmap**, the game build's type mappings. The Lua mod (revision 58 and newer) asks UE4SS to write it once, the first time EVE is loaded in a world while UE4SS's folder holds no `.usmap`; UE4SS's log then has a `Mappings: wrote ...` line ([where the file goes](../protocol/README.md#mappings-for-the-pack-builder)). The installer copies it into its builder cache under the game's Steam build id and checks its header, and refuses one older than the game's program (written for an earlier game build); the build then checks that EveExtract reads the game's item table with it. If that fails, or after a game update, delete the old `.usmap` in UE4SS's folder, press Ctrl+Numpad6 in game (UE4SS's own dump key) with a save loaded, quit, and build again.
- **Oodle**, the decompressor Stellar Blade's containers use. It is proprietary (Epic Games' and RAD Game Tools', under the Unreal Engine EULA) and is never in this repository or its releases. The installer downloads it only after you agree, from the [OodleUE](https://github.com/WorkingRobot/OodleUE) release `2026-06-04-1357` that CUE4Parse itself uses, and checks its pinned SHA-256; or it takes that release's zip you already have, under the same check.

By default the pack holds the outfits you own (from the adapter's last wardrobe list, else a read-only copy of your newest save). Building every outfit instead takes longer and more disk, and lets you see friends' outfits in [multiplayer](multiplayer.md). Outfits you get later appear after the next build.

Stellar Blade's EULA forbids modifying, reverse engineering or making derivative works of the game, and "unauthorised mods" (https://store.steampowered.com/eula/3489700_eula_0). The bridge, and this builder that extracts your own game files into a local pack, fall under it: use them at your own risk. The installer shows this before the first build.

## Windows

EVE players on Windows run both games there: Stellar Blade from Steam and Minecraft Java 1.21.1 with Fabric. Install the three bridge components with the installer; the Python tools above are Linux developer tools and refuse to run on Windows. Then follow the same [session sequence](playing.md#start-a-session). Windows 11 is the target; Windows 10 22H2 should work but gets less testing. The native Windows bridge is checked on a Windows CI runner; it has not yet been played on a real Windows PC.

- **Bridge folder.** `%USERPROFILE%\.local\share\stellar-blade-minecraft\bridge` (Minecraft's default, as on Linux), on a local NTFS disk: never a network share, OneDrive or a FAT/exFAT drive, where shared mappings are not coherent or a removal can stay pending. Its privacy comes from your profile's permissions, not POSIX modes. Stellar Blade's Lua opens it with the C library's narrow `fopen`, so the path it is given must be ASCII: a profile like `C:\Users\Clément` needs its 8.3 short form, the same string for the Lua mod and the adapter, or another folder through `-Dstellarbridge.directory`.
- **Adapter.** `xinput1_3.dll` beside `SB-Win64-Shipping.exe` loads by Windows' own search order: no DLL override. It refuses any other executable, as on Linux. The DLL is unsigned and patches the game in memory, so antivirus software may flag it; keep protection on and report it.
- **UE4SS.** Use UE4SS v3, which loads as `dwmapi.dll`. UE4SS 2.x used `xinput1_3.dll` itself: remove it, as its v3 notes say, before installing the bridge (the installer refuses an existing `xinput1_3.dll`).
- **Window mode.** Run Stellar Blade borderless or windowed, so it keeps running behind Minecraft. The adapter opts Stellar Blade out of Windows 11's power throttling, which would otherwise slow its input loop while the game is hidden and silent.
- **Steam Input.** If forwarding never starts, `/stellaravatar status` shows which callers poll the adapter ([diagnostics](diagnostics.md#avatar-view-experimental)): "the gameplay poll is not recognized" means an overlay hook, most likely Steam Input, calls the controller on the game's behalf. Disable Steam Input for Stellar Blade in its Steam properties.
- **Avatar mode only.** Capture mode (`/stellarview`, `/stellarpreview`) stays Linux-only and says so on Windows. The installer builds the avatar pack into `%USERPROFILE%\.local\share\stellar-blade-minecraft\avatar`, as on Linux ([above](#avatar-pack)).

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
