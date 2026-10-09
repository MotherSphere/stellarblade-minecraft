# Stellar Bridge installer

`stellar-bridge-installer` installs, updates and removes the bridge on Linux with Stellar Blade under Proton and Minecraft in Prism or MultiMC: the UE4SS Lua mod, the XInput adapter, the bridge JAR and Fabric API. It is a Colony program (iced 0.14 and colony-ui 0.1.6), licensed GPL-3.0-or-later like the rest of the repository.

**Status: in progress.** The core is here: detection, the embedded payload and every operation (install, update, repair, roll back, uninstall, verify) with its safety net, tested end to end on fake trees. The window (welcome, detect, verify, choose an instance, install, done) comes next. Until then, install with the Python tools ([setup](../docs/setup.md#install)); the binary answers only `--version`, `--licenses` and `--help`.

## What the core does

| Module | Role |
|---|---|
| `detect` | Steam roots and libraries (`libraryfolders.vdf`), Stellar Blade (`appmanifest_3489700.acf`, build id, executable pin), its Proton prefix and tool, launch options, UE4SS and its `Mods` folder, the `xinput1_3` override and the launch options to copy when it is missing, the avatar pack and whether it matches the game files it was built from |
| `launcher` | Prism (native and Flatpak) and MultiMC instances from `instance.cfg` and `mmc-pack.json`; the dedicated `Stellar Bridge 1.21.1` instance (Minecraft 1.21.1, Fabric Loader 0.19.5, and for a Flatpak Prism the bridge folders inside its sandbox as Java arguments); a manual `mods` folder for other launchers |
| `ops` | Install (also the update), repair, roll back, uninstall and verify on a target: the Stellar Blade side (executable, UE4SS and its `Mods` folder, prefix, saves), a Minecraft `mods` folder and the bridge directory |
| `payload` | The pieces this release carries, embedded at build time (see below), and a kept previous set loaded back for a rollback |
| `mods` | The bridge JAR and, when the folder has none, Fabric API, with `.stellar-bridge-install.json` |
| `deploy` | Port of `tools/deploy.py`: the Lua mod with the same `.deployment.json` |
| `adapter` | Port of `tools/input_proxy.py`: the adapter with the same `.stellar-input-deployment.json` |
| `safety` | Games closed (`/proc`, failing closed), protected-file snapshots, save tree hashes and a verified save backup |
| `ledger` | `state.json`, the previous owned set for rollback, under `~/.local/share/Colony/Stellar Bridge/` |
| `platform` | The Proton `Z:` path, the private bridge directory check, the process list, Steam root candidates |
| `pins` | The Stellar Blade build and executable pin, known UE4SS builds, Minecraft and Fabric versions |

The manifests are compared parsed, as the Python tools compare them, so an install made by either tool is removed by the other; `tests/cross_tool.rs` checks both directions and the shared refusals on fake trees.

## Operations

- **Install / update.** The same operation. Before the first write it checks that the games are closed, that the executable matches the pin, and that no piece is foreign or modified. It records "in progress" in `state.json`, makes a verified copy of the Stellar Blade saves at the first install, keeps the owned set it replaces in `previous/<release>/` (about 1 MB, never the avatar pack), then writes Fabric API and the JAR, the Lua mod and the adapter and verifies them against the payload, the protected files and the saves. On a failure it removes what it wrote and reinstalls the kept set. Installing the release already in place changes nothing.
- **Repair.** Puts back owned files that changed or went missing, only when their content is exactly what the manifests record. Unlisted or foreign files stop it before anything is written; a different release needs the update.
- **Roll back.** Installs the kept previous set through the same checks; the replaced set becomes the previous one.
- **Uninstall.** Removes every owned piece and returns the refusals for the rest, verbatim. Fabric API goes only when asked; a created instance, the pack, the save backups and `state.json` stay.

A Lua mod and adapter installed by the Python tools count as owned and are updated. A bridge JAR copied by hand has no manifest: the installer refuses it until it is removed.

## Payload

`build.rs` embeds the folder named by `SBMC_PAYLOAD`, staged by `tools/payload.py` from this repository's build outputs ([setup](../docs/setup.md#installer-payload)). At load the installer checks every file against `payload.json`, Fabric API against its SHA-512 pin and the DLL's PE header. A build without `SBMC_PAYLOAD` has no payload and installs nothing.

## Rules it keeps

- Stellar Blade, Minecraft (and the launcher, when its files change) are closed before every write, checked again between files.
- Foreign or modified content is refused and never forced. `enabled.txt` and the DLL are written last.
- It never writes saves, overrides, the Proton registry, Steam settings, UE4SS, `dwmapi.dll`, `dinput8.dll` or other mods. It never launches a game or a launcher, and never opens Prism's `accounts.json`; from Steam's `config.vdf` it reads only `CompatToolMapping`.
- The `xinput1_3` override is detected (prefix `user.reg` or launch options) and otherwise shown to copy: `WINEDLLOVERRIDES="xinput1_3=n,b" %command%`.

## Build and check

From the repository root, `python tools/check.py --only installer` runs `cargo fmt --check`, `cargo build --locked`, `cargo clippy --locked --all-targets -- -D warnings` and `cargo test --locked` in this folder, with the payload staged from the current JAR and DLL when both are built; the complete `python tools/check.py` builds them first. The tests use temporary fake trees only and need `python3`.

The declared minimum Rust is 1.90, the floor of the resolved dependency tree (ordered-float through wgpu). Check it after dependency changes with `cargo +1.90 check --locked --all-targets`.
