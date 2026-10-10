# Stellar Bridge installer

`stellar-bridge-installer` installs, updates and removes the bridge on Linux with Stellar Blade under Proton and Minecraft in Prism or MultiMC: the UE4SS Lua mod, the XInput adapter, the bridge JAR and Fabric API. It is a Colony program (iced 0.14 and colony-ui 0.1.6), licensed GPL-3.0-or-later like the rest of the repository.

**Status: G1.** It covers today's setup, Stellar Blade under Proton and Minecraft in Prism or MultiMC on Linux. It installs, updates, repairs, rolls back and uninstalls through a window. It writes nothing until you have seen the list of files and agreed. The avatar pack is detected but not built (G2), and Windows comes later (G3, G4).

## Using it

Run `stellar-bridge-installer` with Stellar Blade and Minecraft closed. Use `--dry-run` to see every plan without writing anything: a dry run creates no folder and writes no file, so its log goes to stderr only and preference changes last until the window closes. The window follows these pages. When something is already installed, it opens on the status page (Detect) instead of Welcome.

1. **Welcome.** Pick the language (French or English, taken from `LANG` the first time) and the role: an **EVE player**, with Stellar Blade and Minecraft on this PC, or a **viewer**, with Minecraft only (the JAR and Fabric API). It says what changes and what is never touched.
2. **Detect.** This is also the status page. It shows Steam and its libraries, Stellar Blade and its build, the Proton tool (an untested one gets a warning), the prefix, UE4SS (a known or untested build, and its `Mods` folder), the `xinput1_3` override, the Minecraft instances, each installed piece (owned, from another release, foreign or modified), the avatar pack (missing, stale or fresh) and the installer's record. A foreign or changed file where the Stellar Blade side goes (UE4SS 2.x also uses `xinput1_3.dll`) is shown and stops the EVE install here. An interrupted run is flagged here. **Repair**, **Roll back** and **Uninstall** are here too, once something is installed.
3. **Verify.** It hashes `SB-Win64-Shipping.exe` (about 360 MB) against the pin. On a mismatch the Stellar Blade side stays off, and the viewer role still works.
4. **Minecraft.** Choose the instance used last time, a new dedicated `Stellar Bridge 1.21.1` instance in Prism (native or Flatpak), an existing Prism or MultiMC instance with Minecraft 1.21.1 and Fabric Loader 0.19.5 or newer (type the MultiMC folder to load its instances), or a `mods` folder for any other launcher (not a Flatpak launcher's: use the dedicated instance there). Installing elsewhere than the recorded instance is refused until the bridge is uninstalled there. A foreign or modified piece stops it here, before anything is written, even the new instance.
5. **Review.** It lists every path it will write, the owned files it replaces (kept first in `previous/`), the backups it makes (one verified copy of the saves at the first install) and what it never touches, with a link to Stellar Blade's licence agreement. Tick the box, then click **Install**.
6. **Close the games.** If Stellar Blade or Minecraft is running (or Prism, when it creates the instance), a modal lists what is running and checks again every second. **Continue** unlocks once everything is closed. The operation itself checks again before every write.
7. **Installing.** Each step is ticked as it starts: checks, save backup, keep previous, remove, the bridge JAR and Fabric API, the bridge folder and Lua mod, the adapter, verify, record. The steps are logged too. A close request while files are being written is held, and the window closes when the operation ends.
8. **Done.** It shows the result. If no `xinput1_3` override was found, it shows the Steam launch options to copy, with your current options kept. Then come the pack state, the first commands and the log path.

Repair, roll back and uninstall each ask for confirmation in a modal that lists what they will change. Uninstall shows each refusal with its path and never forces it. It removes Fabric API only if the installer added it and you tick the box. Files Prism disabled (`.disabled`) or replaced (a newer Fabric API) are handled: a disabled bridge JAR is removed too, and a newer Fabric API stays. It keeps the created instance, the pack, the save backups and `state.json`. Errors appear as toasts; a success toast goes after 5 seconds and an error after 10, as in Colony. Every string is in English and French, refusals and errors of the core included (their raw English text goes only to the log), and tests check that both tables have the same keys, that every key the window uses exists and that every refusal kind has both strings.

| What | Where |
|---|---|
| `state.json`, `previous/<release>/`, `save-backups/` | `~/.local/share/Colony/Stellar Bridge/` |
| Preferences (theme, sizes, dyslexia font, reduced motion, language) | `~/.config/Colony/Stellar Bridge/preferences.json` |
| Log (one run, truncated at each start, also on stderr) | `~/.cache/Colony/Stellar Bridge/stellar-bridge.log` |
| Bridge and avatar folders | `~/.local/share/stellar-blade-minecraft/{bridge,avatar}`, or inside the Flatpak Prism sandbox for its dedicated instance |

The default theme is colony-ui's Stellar Blade EVE palette. The program's name opens the Preferences page.

## Modules

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
| `app` | The window's state machine; detection, hashing and every operation run as jobs on a worker thread |
| `ui` | The pages, drawn with iced 0.14 and colony-ui 0.1.6 (identity button, Preferences page, toasts), plus the error toast, buttons, status rows and the modal |
| `i18n` | English and French strings, one file per locale, falling back to colony-ui's shared labels |
| `log` | The log file and stderr |
| `pins` | The Stellar Blade build and executable pin, known UE4SS builds, Minecraft and Fabric versions |

The manifests are compared parsed, as the Python tools compare them, so an install made by either tool is removed by the other; `tests/cross_tool.rs` checks both directions and the shared refusals on fake trees.

## Operations

Each operation reports its steps to the window and runs the games-closed check before every write. The review page and `--dry-run` show `ops::plan`, which reads only.

- **Install / update.** The same operation. Before the first write it checks that the games are closed, that the executable matches the pin, and that no piece is foreign or modified. It records "in progress" in `state.json`, makes a verified copy of the Stellar Blade saves at the first install, keeps the owned set it replaces in `previous/<release>/` (about 1 MB, never the avatar pack), then writes Fabric API and the JAR, the Lua mod and the adapter and verifies them against the payload, the protected files and the saves. On a failure it removes what it wrote and reinstalls the kept set. Installing the release already in place changes nothing.
- **Repair.** Puts back owned files that changed or went missing, only when their content is exactly what the manifests record. Unlisted or foreign files stop it before anything is written; a different release needs the update.
- **Roll back.** Installs the kept previous set through the same checks; the replaced set becomes the previous one.
- **Uninstall.** Removes every owned piece and returns the refusals for the rest, each with its path. Fabric API goes only when asked; a created instance, the pack, the save backups and `state.json` stay.

A Lua mod and adapter installed by the Python tools count as owned and are updated. A bridge JAR copied by hand has no manifest: the installer refuses it until it is removed.

## Payload

`build.rs` embeds the folder named by `SBMC_PAYLOAD`, staged by `tools/payload.py` from this repository's build outputs ([setup](../docs/setup.md#installer-payload)). At load the installer checks every file against `payload.json`, Fabric API against its SHA-512 pin and the DLL's PE header. A build without `SBMC_PAYLOAD` has no payload and installs nothing.

## Rules it keeps

- Stellar Blade, Minecraft (and the launcher, when its files change) are closed before every write, checked again between files.
- Foreign or modified content is refused and never forced. `enabled.txt` and the DLL are written last.
- It never writes saves, overrides, the Proton registry, Steam settings, UE4SS, `dwmapi.dll`, `dinput8.dll` or other mods. It never launches a game or a launcher, and never opens Prism's `accounts.json`; from Steam's `config.vdf` it reads only `CompatToolMapping`.
- The `xinput1_3` override is detected (prefix `user.reg` or launch options) and otherwise shown to copy: `WINEDLLOVERRIDES="xinput1_3=n,b" %command%`.

## Build and check

From the repository root, `python tools/check.py --only installer` runs `cargo fmt --check`, `cargo build --locked`, `cargo clippy --locked --all-targets -- -D warnings` and `cargo test --locked` in this folder. The payload is staged from the current JAR and DLL when both are built, and the complete `python tools/check.py` builds them first. The tests use temporary fake trees only and need `python3`. `tests/ui_flow.rs` walks the window's state machine through every page on a fake home: it creates a dedicated instance, installs, and goes through the games modal, repair, update, roll back and uninstall. It also checks that a dry run leaves the tree byte for byte as it was, and that an install by the Python tools is detected and uninstalled.

A release binary with the payload, after a complete check on a committed tree:

```sh
python tools/payload.py /path/to/new/payload
SBMC_PAYLOAD=/path/to/new/payload cargo build --release --locked --manifest-path installer/Cargo.toml
```

The binary is `installer/target/release/stellar-bridge-installer`. It embeds colony-ui's fonts, under the SIL Open Font License 1.1 (texts in `licenses/`, printed by `--licenses`).

The declared minimum Rust is 1.90, the floor of the resolved dependency tree (ordered-float through wgpu). Check it after dependency changes with `cargo +1.90 check --locked --all-targets`.
