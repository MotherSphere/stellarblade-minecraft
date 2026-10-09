# Stellar Bridge installer

`stellar-bridge-installer` installs, updates and removes the bridge on Linux with Stellar Blade under Proton and Minecraft in Prism or MultiMC: the UE4SS Lua mod, the XInput adapter, the bridge JAR and Fabric API. It is a Colony program (iced 0.14 and colony-ui 0.1.6), licensed GPL-3.0-or-later like the rest of the repository.

**Status: in progress.** The core library is here: detection, the Lua and adapter installers and the safety net. The window (welcome, detect, verify, choose an instance, install, done; update, repair, roll back, uninstall), the embedded pieces and the Minecraft side come next. Until then, install with the Python tools ([setup](../docs/setup.md#install)); the binary answers only `--version` and `--help`.

## What the core does

| Module | Role |
|---|---|
| `detect` | Steam roots and libraries (`libraryfolders.vdf`), Stellar Blade (`appmanifest_3489700.acf`, build id, executable pin), its Proton prefix and tool, launch options, UE4SS and its `Mods` folder, the `xinput1_3` override, the avatar pack and whether it matches the game files it was built from |
| `launcher` | Prism (native and Flatpak) and MultiMC instances from `instance.cfg` and `mmc-pack.json`; a manual `mods` folder for other launchers |
| `deploy` | Port of `tools/deploy.py`: the Lua mod with the same `.deployment.json` |
| `adapter` | Port of `tools/input_proxy.py`: the adapter with the same `.stellar-input-deployment.json` |
| `safety` | Games closed (`/proc`, failing closed), protected-file snapshots, save tree hashes and a verified save backup |
| `ledger` | `state.json`, the previous owned set for rollback, under `~/.local/share/Colony/Stellar Bridge/` |
| `platform` | The Proton `Z:` path, the private bridge directory check, the process list, Steam root candidates |
| `pins` | The Stellar Blade build and executable pin, known UE4SS builds, Minecraft and Fabric versions |

The manifests are compared parsed, as the Python tools compare them, so an install made by either tool is removed by the other; `tests/cross_tool.rs` checks both directions and the shared refusals on fake trees.

## Rules it keeps

- Stellar Blade, Minecraft (and the launcher, when its files change) are closed before every write, checked again between files.
- Foreign or modified content is refused and never forced. `enabled.txt` and the DLL are written last.
- It never writes saves, overrides, the Proton registry, Steam settings, UE4SS, `dwmapi.dll`, `dinput8.dll` or other mods. It never launches a game or a launcher, and never opens Prism's `accounts.json`; from Steam's `config.vdf` it reads only `CompatToolMapping`.
- The `xinput1_3` override is detected (prefix `user.reg` or launch options) and otherwise shown to copy: `WINEDLLOVERRIDES="xinput1_3=n,b" %command%`.

## Build and check

From the repository root, `python tools/check.py --only installer` runs `cargo fmt --check`, `cargo build --locked`, `cargo clippy --locked --all-targets -- -D warnings` and `cargo test --locked` in this folder; the complete `python tools/check.py` includes it. The tests use temporary fake trees only and need `python3`.

The declared minimum Rust is 1.90, the floor of the resolved dependency tree (ordered-float through wgpu). Check it after dependency changes with `cargo +1.90 check --locked --all-targets`.
