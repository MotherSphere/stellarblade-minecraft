# Stellar Bridge installer

<img src="assets/logo.svg" alt="Stellar Bridge logo" width="96">

`stellar-bridge-installer` installs, updates and removes the bridge with Minecraft in Prism or MultiMC and Stellar Blade under Proton on Linux or natively on Windows: the UE4SS Lua mod, the XInput adapter, the bridge JAR and Fabric API. It also sets up Fabric server folders. It is a Colony program (iced 0.14 and colony-ui 0.1.6), licensed GPL-3.0-or-later like the rest of the repository.

**Status: G1 on Linux, tested on the real install; G3 and G4 in code.** It installs, updates, repairs, rolls back and uninstalls through a window, and writes nothing until you have seen the list of files and agreed. Server folders work from the window and from the command line. The Windows build (Windows 11, and Windows 10 22H2 as a courtesy) covers EVE players, viewers and servers. CI builds and tests it on GitHub's Windows runner and each release carries it as `stellar-bridge-installer-windows-x86_64.exe`; the bridge it installs runs natively there (avatar mode; capture mode stays Linux-only), and both still need one grouped test on a real Windows PC. The avatar pack is detected but not built (G2).

## Using it

Run `stellar-bridge-installer` with Stellar Blade and Minecraft closed. Use `--dry-run` to see every plan without writing anything: a dry run creates no folder and writes no file, so its log goes to stderr only and preference changes last until the window closes. The window follows these pages. When something is already installed, it opens on the status page (Detect) instead of Welcome.

1. **Welcome.** Pick the language (French or English, taken the first time from `LANG`, or on Windows from the system language) and the role: an **EVE player**, with Stellar Blade and Minecraft on this PC, a **viewer**, with Minecraft only (the JAR and Fabric API), or a **server operator** (see [Server folder](#server-folder)). It says what changes and what is never touched.
2. **Detect.** This is also the status page. It shows Steam and its libraries, Stellar Blade and its build, the Proton tool (an untested one gets a warning), the prefix, UE4SS (a known or untested build, and its `Mods` folder), the `xinput1_3` override, the Minecraft instances, each installed piece (owned, from another release, foreign or modified), the avatar pack (missing, stale or fresh) and the installer's record. A foreign or changed file where the Stellar Blade side goes (UE4SS 2.x also uses `xinput1_3.dll`) is shown and stops the EVE install here. An interrupted run is flagged here. **Repair**, **Roll back** and **Uninstall** are here too, once something is installed. The main button reads **Install** when nothing is installed and **Update** when a piece or the recorded release differs from what the installer carries; when everything is current it reads **Up to date** and stays disabled.
3. **Verify.** It hashes `SB-Win64-Shipping.exe` (about 360 MB) against the pin. On a mismatch the Stellar Blade side stays off, and the viewer role still works.
4. **Minecraft.** Choose the instance used last time, a new dedicated `Stellar Bridge 1.21.1` instance in Prism (native or Flatpak), an existing Prism or MultiMC instance with Minecraft 1.21.1 and Fabric Loader 0.19.5 or newer (type the MultiMC folder to load its instances), or a `mods` folder for any other launcher (not a Flatpak launcher's: use the dedicated instance there). Installing elsewhere than the recorded instance is refused until the bridge is uninstalled there. A foreign or modified piece stops it here, before anything is written, even the new instance.
5. **Review.** It lists every path it will write, the owned files it replaces (kept first in `previous/`), the backups it makes (one verified copy of the saves at the first install) and what it never touches, with a link to Stellar Blade's licence agreement. Tick the box, then click **Install**.
6. **Close the games.** If Stellar Blade or Minecraft is running (or Prism, when it creates the instance), a modal lists what is running and checks again every second. **Continue** unlocks once everything is closed. The operation itself checks again before every write.
7. **Installing.** Each step is ticked as it starts: checks, save backup, keep previous, remove, the bridge JAR and Fabric API, the bridge folder and Lua mod, the adapter, verify, record. The steps are logged too. A close request while files are being written is held, and the window closes when the operation ends.
8. **Done.** It shows the result. If no `xinput1_3` override was found, it shows the Steam launch options to copy, with your current options kept. Then come the pack state, the first commands and the log path.

Repair, roll back and uninstall each ask for confirmation in a modal that lists what they will change. Uninstall shows each refusal with its path and never forces it. It removes Fabric API only if the installer added it and you tick the box. Files Prism disabled (`.disabled`) or replaced (a newer Fabric API) are handled: a disabled bridge JAR is removed too, and a newer Fabric API stays. It keeps the created instance, the pack, the save backups and `state.json`. Errors appear as toasts; a success toast goes after 5 seconds and an error after 10, as in Colony. Every string is in English and French, refusals and errors of the core included: the window names the refusal and its path, the raw English text goes only to the log, and an error of no known kind reads "Unexpected error: the log has the details." Tests check that both tables have the same keys, that every key the window uses exists and that every refusal kind has both strings.

### Server folder

A server operator gets the bridge JAR and Fabric API in the `mods` folder of a Fabric server for Minecraft 1.21.1. The **Server folder** page takes the folder, then **Check** shows what it holds: a missing or empty folder becomes a new server; a folder this installer made, or a Fabric server for Minecraft 1.21.1 set up by other means (its launcher, `fabric-server-launcher.properties` or `.fabric/`, and the game version from the launcher's name, `versions/` or the intermediary mappings in `libraries/`), gets the bridge in its `mods`. Any other folder, a Fabric Loader older than 0.19.5 or a bridge JAR copied by hand is refused. The page also shows the Java that `java` runs (21 or newer is required, as the start scripts call it), what `eula.txt` says and an `online-mode=false`, which it reports and never changes.

- **A new server** gets Fabric's server launcher as `fabric-server-launch.jar` (embedded, see [`vendor/fabric-server/NOTICE`](vendor/fabric-server/NOTICE)), `start.sh` and `start.bat` (`java -Xmx2G -jar fabric-server-launch.jar nogui`), `mods/` with the bridge JAR and Fabric API, and `.stellar-bridge-server.json` listing the files it made. It is built in a hidden sibling folder and renamed into place, so a failed run leaves nothing. At its first start the launcher downloads the Minecraft server and Fabric Loader; the installer downloads nothing.
- **Mojang's EULA** is the operator's act. `eula.txt` is written (`eula=true`) only when there is none and the operator ticks the box under the link to [Mojang's EULA](https://aka.ms/MinecraftEULA), or passes `--accept-eula`. An existing `eula.txt` is never changed. Without it, the done page says to accept the EULA in `eula.txt` before the first start.
- **Never written:** `server.properties` (so `online-mode` stays `true` on a new server, as Minecraft writes it), the worlds, other mods. The server is never started, and the install is refused while a Java process works in the folder (a Java process whose folder cannot be read counts when its command line looks like a server, or cannot be read either).
- **Update:** run it again with a newer installer; an unchanged bridge JAR of an older release is replaced, and the same release changes nothing.

Without a window (a headless host), the same runs from the command line, printing in the language of `LANG` (on Windows, the system language):

```sh
stellar-bridge-installer --server /srv/minecraft --dry-run      # what the folder holds; writes nothing
stellar-bridge-installer --server /srv/minecraft                 # install or update
stellar-bridge-installer --server /srv/minecraft --accept-eula   # also write eula=true: you accept Mojang's EULA
stellar-bridge-installer --server /srv/minecraft --uninstall     # remove the bridge JAR; Fabric API and the server stay
```

It exits with 0 on success, 1 on a refusal or failure (the reason on stderr) and 2 on a usage error.

### On Windows

The same pages, with what differs on a native Windows install:

- **Detect** finds Steam where the registry says it is (read only) or in its default folder, then its libraries through `libraryfolders.vdf`; Prism in `%APPDATA%\PrismLauncher`; MultiMC by its folder, as on Linux. There is no Proton tool, prefix or DLL override: Windows loads `xinput1_3.dll` from the game folder, so the done page shows no launch options. The saves are looked for in `%LOCALAPPDATA%\SB\Saved\SaveGames` (the folder the Proton prefix mirrors), then in `Documents\StellarBlade` and `AppData\Local\Yaksha\StellarBlade\Save` under the profile, which some guides name but nobody has confirmed; they are only read, for the save hash and the first-install copy.
- **The bridge folder** is `%USERPROFILE%\.local\share\stellar-blade-minecraft\bridge`, where the bridge JAR looks by default. It must be on a local NTFS disk, never a network share. UE4SS's Lua opens files with narrow paths, so the Lua config and the adapter's directory file hold the same ASCII string: the path itself, or for a profile such as `C:\Users\Clément` its 8.3 short form; without a short name the install is refused.
- **Games closed** reads the live process list: `SB-Win64-Shipping.exe` or `SB.exe`, and `java.exe` or `javaw.exe` with a Minecraft command line (one that cannot be read, as for an elevated process, counts as Minecraft).
- The exe runs as the user who starts it, never elevated. Started by a double click, it hides the console Windows gives it; from a terminal, `--help`, `--version` and `--server` print there.

| What | Linux | Windows |
|---|---|---|
| `state.json`, `previous/<release>/`, `save-backups/` | `~/.local/share/Colony/Stellar Bridge/` | `%LOCALAPPDATA%\Colony\Stellar Bridge\` |
| Preferences (theme, sizes, dyslexia font, reduced motion, language) | `~/.config/Colony/Stellar Bridge/preferences.json` | `%LOCALAPPDATA%\Colony\Stellar Bridge\preferences.json` |
| Log (one run, truncated at each start, also on stderr) | `~/.cache/Colony/Stellar Bridge/stellar-bridge.log` | `%LOCALAPPDATA%\Colony\Stellar Bridge\cache\stellar-bridge.log` |
| Bridge and avatar folders | `~/.local/share/stellar-blade-minecraft/{bridge,avatar}`, or inside the Flatpak Prism sandbox for its dedicated instance | `%USERPROFILE%\.local\share\stellar-blade-minecraft\{bridge,avatar}` |

The default theme is colony-ui's Stellar Blade EVE palette. The program's name opens the Preferences page.

## Modules

| Module | Role |
|---|---|
| `detect` | Steam roots (from `platform`) and libraries (`libraryfolders.vdf`), Stellar Blade (`appmanifest_3489700.acf`, build id, executable pin), its Proton prefix and tool, launch options, UE4SS and its `Mods` folder, the `xinput1_3` override and the launch options to copy when it is missing, the avatar pack and whether it matches the game files it was built from |
| `launcher` | Prism (native and Flatpak) and MultiMC instances from `instance.cfg` and `mmc-pack.json`; the dedicated `Stellar Bridge 1.21.1` instance (Minecraft 1.21.1, Fabric Loader 0.19.5, and for a Flatpak Prism the bridge folders inside its sandbox as Java arguments); a manual `mods` folder for other launchers |
| `ops` | Install (also the update), repair, roll back, uninstall and verify on a target: the Stellar Blade side (executable, UE4SS and its `Mods` folder, prefix, saves), a Minecraft `mods` folder and the bridge directory |
| `payload` | The pieces this release carries, embedded at build time (see below), and a kept previous set loaded back for a rollback |
| `mods` | The bridge JAR and, when the folder has none, Fabric API, with `.stellar-bridge-install.json` |
| `deploy` | Port of `tools/deploy.py`: the Lua mod with the same `.deployment.json` |
| `adapter` | Port of `tools/input_proxy.py`: the adapter with the same `.stellar-input-deployment.json` |
| `safety` | Games closed (`/proc`, failing closed), a server running in its folder, protected-file snapshots, save tree hashes and a verified save backup |
| `ledger` | `state.json`, the previous owned set for rollback, under `~/.local/share/Colony/Stellar Bridge/` |
| `server` | Server folder mode: what a server folder holds, the Java check, a new server from the embedded launcher, the bridge in `mods/`, `eula.txt` only on request, `.stellar-bridge-server.json` |
| `platform` | The only module with Linux and Windows branches: the process list (`/proc`, or sysinfo on Windows), the path the game side opens (Proton's `Z:` form, or a Windows ASCII or 8.3 path), the private bridge directory (owner and 0700, or a local NTFS disk), where Steam (the registry on Windows, read only), Prism and the saves are, the home folder, and paths without Windows' `\\?\` prefix |
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
- The `xinput1_3` override is detected (prefix `user.reg` or launch options) and otherwise shown to copy: `WINEDLLOVERRIDES="xinput1_3=n,b" %command%`. Windows needs none.
- On Windows it reads the registry only to find Steam, and writes none.
- In a server folder it never accepts Mojang's EULA for the operator, never writes `server.properties` or the worlds, and never starts the server.

## Build and check

From the repository root, `python tools/check.py --only installer` runs `cargo fmt --check`, `cargo build --locked`, `cargo clippy --locked --all-targets -- -D warnings` and `cargo test --locked` in this folder. The payload is staged from the current JAR and DLL when both are built, and the complete `python tools/check.py` builds them first. The tests use temporary fake trees only and need `python3`. On Windows the unit tests and `tests/operations.rs` run too; `tests/ui_flow.rs` (a fake Linux home and `/proc`) and `tests/cross_tool.rs` (the Linux-only Python tools) are Linux only. `tests/ui_flow.rs` walks the window's state machine through every page on a fake home: it creates a dedicated instance, installs, and goes through the games modal, repair, update, roll back and uninstall. It also checks that a dry run leaves the tree byte for byte as it was, that an install by the Python tools is detected and uninstalled, and that the server page makes a new server folder, writes `eula.txt` only with the box ticked and stops while a server runs there. The server tests run a fake `java` that prints a version. CI runs the Windows tests natively on `windows-2025` with the release payload embedded, plus clippy, a check with the declared minimum Rust and the `--version` smoke test ([releases and CI](../docs/releasing.md#ci)).

A release binary with the payload, after a complete check on a committed tree:

```sh
python tools/payload.py /path/to/new/payload
SBMC_PAYLOAD=/path/to/new/payload cargo build --release --locked --manifest-path installer/Cargo.toml
```

The binary is `installer/target/release/stellar-bridge-installer`. It embeds colony-ui's fonts, under the SIL Open Font License 1.1 (texts in `licenses/`), and Fabric's server launcher, under the Apache License 2.0 (`vendor/fabric-server/`); `--licenses` prints them all.

To add it to the desktop menu, install the binary, its desktop entry and the logo for your user:

```sh
install -Dm755 installer/target/release/stellar-bridge-installer ~/.local/bin/stellar-bridge-installer
install -Dm644 installer/assets/stellar-bridge-installer.desktop ~/.local/share/applications/stellar-bridge-installer.desktop
install -Dm644 installer/assets/logo.svg ~/.local/share/icons/hicolor/scalable/apps/stellar-bridge-installer.svg
```

The window's application id is `stellar-bridge-installer`, so Wayland compositors take its icon from that entry; X11 uses the 64 px icon embedded in the binary.

The Windows build (`--target x86_64-pc-windows-msvc`, on Windows or cross-built) is `stellar-bridge-installer.exe`, published as `stellar-bridge-installer-windows-x86_64.exe`. `build.rs` gives it the icon `assets/icon.ico`, version information from `Cargo.toml` and the manifest `assets/stellar-bridge-installer.manifest`, which runs it as the user who starts it, never elevated. The resource compiler is the Windows SDK's `rc.exe`, or `llvm-rc` when cross-building.

The declared minimum Rust is 1.95, set by sysinfo, which only the Windows build uses; the Linux tree alone builds with 1.90 (ordered-float through wgpu). The Windows CI job checks 1.95 on every run; check the Linux floor after dependency changes with `cargo +1.90 check --locked --all-targets --ignore-rust-version`.

## Logo

`assets/logo.svg` is the Stellar Bridge logo, "Split Block": one block split along an S seam into two interlocking pieces, a black one and a gold one, for the two worlds the bridge joins. Its seam is filled, so it looks the same on light and dark backgrounds. It is original artwork under GPL-3.0-or-later like the rest of the repository, and uses no game art, logo or font. The window icon `assets/icon-64.rgba` is rendered from it; after changing the SVG, render it again with:

```sh
rsvg-convert -w 64 -h 64 installer/assets/logo.svg | magick png:- -depth 8 rgba:installer/assets/icon-64.rgba
rsvg-convert -w 256 -h 256 installer/assets/logo.svg | magick png:- -define icon:auto-resize=64,48,32,16 installer/assets/icon.ico
```

The second line renders the Windows exe's icon.
