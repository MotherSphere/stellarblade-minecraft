//! Read-only detection: Steam, Stellar Blade, its Proton prefix, UE4SS, the DLL override and the avatar pack.
//!
//! Steam's `config.vdf` also holds secrets (depot keys): only `CompatToolMapping` is read from it, and no Steam file is
//! ever logged. Prism's `accounts.json` is never opened (see `launcher`).

use anyhow::Result;
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use crate::platform::{self, SteamKind};
use crate::{adapter, pins, safety};

/// A Valve KeyValues (VDF) value: a string or a block of pairs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Vdf {
    Str(String),
    Block(Vec<(String, Vdf)>),
}

impl Vdf {
    /// The text form Steam writes: quoted or bare tokens, `{ }` blocks, `//` comments and `[$OS]` conditions (ignored).
    pub fn parse(text: &str) -> Option<Vdf> {
        let mut tokens = Vec::new();
        let mut chars = text.chars().peekable();
        while let Some(c) = chars.next() {
            match c {
                '{' | '}' => tokens.push((true, c.to_string())),
                '"' => {
                    let mut token = String::new();
                    loop {
                        match chars.next()? {
                            '"' => break,
                            '\\' => match chars.next()? {
                                'n' => token.push('\n'),
                                't' => token.push('\t'),
                                other => token.push(other),
                            },
                            other => token.push(other),
                        }
                    }
                    tokens.push((false, token));
                }
                '/' if chars.peek() == Some(&'/') => {
                    while chars.next_if(|&c| c != '\n').is_some() {}
                }
                '[' => while chars.next().is_some_and(|c| c != ']') {},
                c if c.is_whitespace() => {}
                c => {
                    let mut token = c.to_string();
                    while let Some(c) =
                        chars.next_if(|c| !c.is_whitespace() && !matches!(c, '"' | '{' | '}'))
                    {
                        token.push(c);
                    }
                    tokens.push((false, token));
                }
            }
        }
        let mut tokens = tokens.into_iter();
        let pairs = Self::block(&mut tokens, false)?;
        Some(Vdf::Block(pairs))
    }

    fn block(
        tokens: &mut impl Iterator<Item = (bool, String)>,
        nested: bool,
    ) -> Option<Vec<(String, Vdf)>> {
        let mut pairs = Vec::new();
        loop {
            let key = match tokens.next() {
                None if !nested => return Some(pairs),
                Some((true, brace)) if brace == "}" && nested => return Some(pairs),
                Some((false, key)) => key,
                _ => return None,
            };
            let value = match tokens.next()? {
                (true, brace) if brace == "{" => Vdf::Block(Self::block(tokens, true)?),
                (false, text) => Vdf::Str(text),
                _ => return None,
            };
            pairs.push((key, value));
        }
    }

    /// The first value under `key`, ignoring case as Steam does.
    pub fn get(&self, key: &str) -> Option<&Vdf> {
        match self {
            Vdf::Block(pairs) => pairs
                .iter()
                .find(|(k, _)| k.eq_ignore_ascii_case(key))
                .map(|(_, v)| v),
            Vdf::Str(_) => None,
        }
    }

    /// The value at a path of keys.
    pub fn at(&self, keys: &[&str]) -> Option<&Vdf> {
        keys.iter().try_fold(self, |value, key| value.get(key))
    }

    pub fn str(&self) -> Option<&str> {
        match self {
            Vdf::Str(text) => Some(text),
            Vdf::Block(_) => None,
        }
    }

    pub fn pairs(&self) -> &[(String, Vdf)] {
        match self {
            Vdf::Block(pairs) => pairs,
            Vdf::Str(_) => &[],
        }
    }
}

fn read_vdf(path: &Path) -> Option<Vdf> {
    Vdf::parse(&String::from_utf8_lossy(&fs::read(path).ok()?))
}

/// A Steam installation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Steam {
    pub root: PathBuf,
    pub kind: SteamKind,
    /// Library folders, the root's own first.
    pub libraries: Vec<PathBuf>,
}

/// Every distinct Steam installation under `home`.
pub fn steam(home: &Path) -> Vec<Steam> {
    let mut found: Vec<Steam> = Vec::new();
    for (candidate, kind) in platform::steam_roots(home) {
        let Ok(root) = fs::canonicalize(&candidate) else {
            continue;
        };
        if !root.join("steamapps").is_dir() || found.iter().any(|s| s.root == root) {
            continue;
        }
        let mut libraries = vec![root.clone()];
        if let Some(folders) = read_vdf(&root.join("steamapps/libraryfolders.vdf")) {
            for (_, library) in folders
                .get("libraryfolders")
                .map(Vdf::pairs)
                .unwrap_or_default()
            {
                // Current format: a block with "path"; old format: the path itself.
                if let Some(path) = library.get("path").and_then(Vdf::str).or(library.str()) {
                    let path = fs::canonicalize(path).unwrap_or_else(|_| PathBuf::from(path));
                    if !libraries.contains(&path) {
                        libraries.push(path);
                    }
                }
            }
        }
        found.push(Steam {
            root,
            kind,
            libraries,
        });
    }
    found
}

/// The Stellar Blade installation found in a Steam library.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Game {
    pub steam_root: PathBuf,
    pub steam_kind: SteamKind,
    pub library: PathBuf,
    /// `steamapps/common/<installdir>`.
    pub dir: PathBuf,
    /// `SB/Binaries/Win64`, where the adapter goes.
    pub win64: PathBuf,
    pub exe: PathBuf,
    pub build_id: Option<String>,
    /// Steam has an update pending or running: wait before installing.
    pub updating: bool,
    /// `steamapps/compatdata/3489700/pfx`, when it exists.
    pub prefix: Option<PathBuf>,
    /// The Proton tool Steam maps the game to, if set.
    pub proton: Option<String>,
    /// The game's launch options for each Steam account on this PC.
    pub launch_options: Vec<String>,
}

impl Game {
    /// Whether Steam reports the build this release supports (the cheap gate before hashing the executable).
    pub fn build_supported(&self) -> bool {
        self.build_id.as_deref() == Some(pins::SB_BUILD_ID) && !self.updating
    }

    /// The `SaveGames` folder in the prefix, when it exists. The SteamID64 below it is never logged.
    pub fn saves(&self) -> Option<PathBuf> {
        let saves = self
            .prefix
            .as_ref()?
            .join("drive_c/users/steamuser/AppData/Local/SB/Saved/SaveGames");
        saves.is_dir().then_some(saves)
    }
}

/// Stellar Blade in any of the Steam installations.
pub fn game(steams: &[Steam]) -> Option<Game> {
    for steam in steams {
        for library in &steam.libraries {
            let steamapps = library.join("steamapps");
            let Some(manifest) =
                read_vdf(&steamapps.join(format!("appmanifest_{}.acf", pins::SB_APP_ID)))
            else {
                continue;
            };
            let Some(state) = manifest.get("AppState") else {
                continue;
            };
            let field = |key: &str| state.get(key).and_then(Vdf::str).map(str::to_string);
            let Some(installdir) = field("installdir")
                .filter(|d| !d.is_empty() && !d.contains(['/', '\\']) && d != "..")
            else {
                continue;
            };
            let dir = steamapps.join("common").join(installdir);
            let win64 = dir.join("SB/Binaries/Win64");
            let build_id = field("buildid");
            let target = field("TargetBuildID").filter(|t| t != "0");
            let updating = field("StateFlags").as_deref() != Some("4")
                || target.is_some_and(|t| Some(&t) != build_id.as_ref());
            let prefix = steamapps
                .join("compatdata")
                .join(pins::SB_APP_ID)
                .join("pfx");
            let proton = read_vdf(&steam.root.join("config/config.vdf")).and_then(|config| {
                let mapping = config.at(&[
                    "InstallConfigStore",
                    "Software",
                    "Valve",
                    "Steam",
                    "CompatToolMapping",
                    pins::SB_APP_ID,
                ])?;
                mapping
                    .get("name")
                    .and_then(Vdf::str)
                    .filter(|n| !n.is_empty())
                    .map(str::to_string)
            });
            return Some(Game {
                steam_root: steam.root.clone(),
                steam_kind: steam.kind,
                library: library.clone(),
                exe: win64.join(adapter::EXE),
                win64,
                dir,
                build_id,
                updating,
                prefix: prefix.is_dir().then_some(prefix),
                proton,
                launch_options: launch_options(&steam.root),
            });
        }
    }
    None
}

fn launch_options(steam_root: &Path) -> Vec<String> {
    let mut found = Vec::new();
    let Ok(users) = fs::read_dir(steam_root.join("userdata")) else {
        return found;
    };
    let mut users: Vec<PathBuf> = users.filter_map(|e| e.ok().map(|e| e.path())).collect();
    users.sort();
    for user in users {
        let Some(config) = read_vdf(&user.join("config/localconfig.vdf")) else {
            continue;
        };
        let path = [
            "UserLocalConfigStore",
            "Software",
            "Valve",
            "Steam",
            "apps",
            pins::SB_APP_ID,
            "LaunchOptions",
        ];
        if let Some(options) = config.at(&path).and_then(Vdf::str) {
            found.push(options.to_string());
        }
    }
    found
}

/// Whether the executable is the pinned build, reporting the bytes hashed so far.
pub fn exe_supported(exe: &Path, progress: &mut dyn FnMut(u64)) -> Result<bool> {
    Ok(safety::sha256_file(exe, progress)? == pins::SB_EXE_SHA256)
}

/// A UE4SS installation. It is never installed, replaced or removed here.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ue4ss {
    /// UE4SS's folder: `Win64/ue4ss`, or `Win64` itself in the old layout.
    pub dir: PathBuf,
    /// The `Mods` folder, unless `ModsFolderPath` moves it somewhere the user must pick.
    pub mods: Option<PathBuf>,
    /// `UE4SS.dll` is a build known to work with the bridge.
    pub known: bool,
}

/// UE4SS in the game's `Win64` folder.
pub fn ue4ss(win64: &Path) -> Option<Ue4ss> {
    let dir = [win64.join("ue4ss"), win64.to_path_buf()]
        .into_iter()
        .find(|d| d.join("UE4SS.dll").is_file())?;
    let custom = fs::read_to_string(dir.join("UE4SS-settings.ini"))
        .ok()
        .is_some_and(|ini| {
            ini.lines()
                .filter_map(|line| line.split_once('='))
                .any(|(key, value)| {
                    key.trim().eq_ignore_ascii_case("ModsFolderPath") && !value.trim().is_empty()
                })
        });
    let mods = dir.join("Mods");
    let known = safety::sha256_file(&dir.join("UE4SS.dll"), &mut |_| {})
        .is_ok_and(|d| pins::UE4SS_KNOWN_SHA256.contains(&d.as_str()));
    Some(Ue4ss {
        mods: (!custom && mods.is_dir()).then_some(mods),
        known,
        dir,
    })
}

/// Where Proton is told to load the adapter (`xinput1_3` native first).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Override {
    /// The prefix's registry, for the game or for every program.
    Registry,
    LaunchOptions,
    /// Not found: show [`pins::LAUNCH_OPTION`] to copy. The installer never writes it.
    Missing,
}

/// Looks for the `xinput1_3` override in the prefix's `user.reg` and the launch options, read only.
pub fn dll_override(prefix: Option<&Path>, launch_options: &[String]) -> Override {
    let native = |mode: &str| mode.trim_matches('"').starts_with('n');
    if let Some(registry) = prefix.and_then(|p| fs::read(p.join("user.reg")).ok()) {
        let registry = String::from_utf8_lossy(&registry);
        let mut section = false;
        for line in registry.lines() {
            if line.starts_with('[') {
                let key = line
                    .split(']')
                    .next()
                    .unwrap_or_default()
                    .to_ascii_lowercase();
                section = key
                    == "[software\\\\wine\\\\appdefaults\\\\sb-win64-shipping.exe\\\\dlloverrides"
                    || key == "[software\\\\wine\\\\dlloverrides";
            } else if section
                && let Some((name, mode)) = line.split_once('=')
                && name.trim_matches('"').eq_ignore_ascii_case("xinput1_3")
                && native(mode)
            {
                return Override::Registry;
            }
        }
    }
    for options in launch_options {
        for word in options.split_whitespace() {
            let Some(value) = word.strip_prefix("WINEDLLOVERRIDES=") else {
                continue;
            };
            for entry in value.trim_matches('"').split(';') {
                if let Some((names, mode)) = entry.split_once('=')
                    && names
                        .split(',')
                        .any(|n| n.eq_ignore_ascii_case("xinput1_3"))
                    && native(mode)
                {
                    return Override::LaunchOptions;
                }
            }
        }
    }
    Override::Missing
}

/// The game's launch options with `xinput1_3=n,b` added, for the user to copy into Steam: other options and overrides
/// are kept, an existing `WINEDLLOVERRIDES` gains the entry, and `%command%` is added when missing.
pub fn launch_options_with_override(existing: &str) -> String {
    let existing = existing.trim();
    if existing.is_empty() {
        return pins::LAUNCH_OPTION.to_string();
    }
    let mut words: Vec<String> = existing.split_whitespace().map(str::to_string).collect();
    if let Some(word) = words
        .iter_mut()
        .find(|w| w.starts_with("WINEDLLOVERRIDES="))
    {
        let value = word["WINEDLLOVERRIDES=".len()..].trim_matches('"');
        *word = if value.is_empty() {
            "WINEDLLOVERRIDES=\"xinput1_3=n,b\"".to_string()
        } else {
            format!("WINEDLLOVERRIDES=\"{value};xinput1_3=n,b\"")
        };
    } else {
        words.insert(0, "WINEDLLOVERRIDES=\"xinput1_3=n,b\"".to_string());
    }
    if !words.iter().any(|w| w == "%command%") {
        // Options without %command% are arguments for the game: they follow it.
        let at = words.iter().take_while(|w| w.contains('=')).count();
        words.insert(at, "%command%".to_string());
    }
    words.join(" ")
}

/// The folder holding `bridge/` and `avatar/`: where the JAR looks by default, or for a Flatpak Prism a folder inside
/// its sandbox that the installer passes to the JAR with `-Dstellarbridge.directory` and `-Dstellarbridge.avatar`.
pub fn bridge_home(home: &Path, flatpak_prism: bool) -> PathBuf {
    if flatpak_prism {
        home.join(".var/app/org.prismlauncher.PrismLauncher/data/stellar-blade-minecraft")
    } else {
        home.join(".local/share/stellar-blade-minecraft")
    }
}

/// The private avatar pack's state. The pack is built from the player's own game, never shipped (G1 only detects it).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Pack {
    Missing,
    /// A pack without `pack.txt` (built before mod tracking), or whose Paks folder is gone: cannot be compared.
    Untracked,
    Unreadable,
    /// Built from other game files: `+added`, `-removed`, `~changed` paths under Paks.
    Stale(Vec<String>),
    Fresh,
}

/// Parses `pack.txt` exactly as `Wardrobe.manifest` does: the Paks folder and "bytes mtime-ns" by path under it.
fn pack_manifest(text: &str) -> Option<(PathBuf, BTreeMap<String, String>)> {
    let body = text
        .strip_prefix("SBAVPACK 1\nPAKS ")?
        .strip_suffix("\nEND\n")?;
    let mut lines = body.split('\n');
    let paks = lines.next().filter(|p| !p.is_empty())?;
    let mut sources = BTreeMap::new();
    for line in lines {
        if let Some(source) = line.strip_prefix("SOURCE ") {
            let mut fields = source.rsplitn(3, ' ');
            let (mtime, size, path) = (fields.next()?, fields.next()?, fields.next()?);
            let number =
                |n: &str| (1..=19).contains(&n.len()) && n.bytes().all(|b| b.is_ascii_digit());
            if path.is_empty()
                || !number(size)
                || !number(mtime)
                || sources
                    .insert(path.to_string(), format!("{size} {mtime}"))
                    .is_some()
            {
                return None;
            }
        } else if !line.starts_with("MESH ") {
            return None;
        }
    }
    Some((PathBuf::from(paks), sources))
}

/// The `.pak`, `.utoc` and `.ucas` files under `paks` as `pack.py` lists them: "bytes mtime-ns" by path.
fn pak_sources(paks: &Path) -> Result<BTreeMap<String, String>> {
    let root = fs::canonicalize(paks)?;
    let mut found = BTreeMap::new();
    let visit = |dir: &Path, found: &mut BTreeMap<String, String>| -> Result<Vec<PathBuf>> {
        let mut subdirs = Vec::new();
        for entry in fs::read_dir(dir)? {
            let path = entry?.path();
            let name = path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .to_lowercase();
            // Like Files.walk: symlinked folders are not entered, symlinked files count.
            if fs::symlink_metadata(&path)?.is_dir() {
                subdirs.push(path);
                continue;
            }
            let Ok(metadata) = fs::metadata(&path) else {
                continue;
            };
            if metadata.is_file() && [".pak", ".utoc", ".ucas"].iter().any(|e| name.ends_with(e)) {
                let mtime = metadata
                    .modified()?
                    .duration_since(std::time::UNIX_EPOCH)?
                    .as_nanos();
                found.insert(
                    safety::slash(path.strip_prefix(&root)?)?,
                    format!("{} {mtime}", metadata.len()),
                );
            }
        }
        Ok(subdirs)
    };
    let mut pending = vec![root.clone()];
    while let Some(dir) = pending.pop() {
        pending.extend(visit(&dir, &mut found)?);
    }
    Ok(found)
}

/// Whether the avatar pack in `avatar` exists and matches the game files it was built from.
pub fn pack(avatar: &Path) -> Pack {
    let header = |name: &str, magic: &str| {
        fs::read(avatar.join(name)).is_ok_and(|b| b.starts_with(magic.as_bytes()))
    };
    if !avatar.is_dir() || !header("wardrobe.txt", "SBAVWARDROBE 1\n") {
        return Pack::Missing;
    }
    let Ok(text) = fs::read_to_string(avatar.join("pack.txt")) else {
        return Pack::Untracked;
    };
    let Some((paks, built)) = pack_manifest(&text) else {
        return Pack::Unreadable;
    };
    if !paks.is_dir() {
        return Pack::Untracked;
    }
    let Ok(found) = pak_sources(&paks) else {
        return Pack::Unreadable;
    };
    let keys: std::collections::BTreeSet<&String> = built.keys().chain(found.keys()).collect();
    let changes: Vec<String> = keys
        .into_iter()
        .filter_map(|path| match (built.get(path), found.get(path)) {
            (None, _) => Some(format!("+{path}")),
            (_, None) => Some(format!("-{path}")),
            (was, now) if was != now => Some(format!("~{path}")),
            _ => None,
        })
        .collect();
    if changes.is_empty() {
        Pack::Fresh
    } else {
        Pack::Stale(changes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    #[test]
    fn vdf_reads_nested_blocks_escapes_comments_and_conditions() {
        let vdf = Vdf::parse("// c\n\"a\" { \"b\" \"x\\\"y\" [$WIN32]\n bare value \"c\" { } }\n")
            .unwrap();
        assert_eq!(vdf.at(&["A", "b"]).and_then(Vdf::str), Some("x\"y"));
        assert_eq!(vdf.at(&["a", "bare"]).and_then(Vdf::str), Some("value"));
        assert_eq!(vdf.at(&["a", "c"]), Some(&Vdf::Block(vec![])));
        assert_eq!(Vdf::parse("\"a\" { \"b\""), None);
    }

    /// A fake Steam: the root library holds nothing, a second library holds the game with its prefix.
    fn fake_steam(home: &Path, library: &Path) {
        let root = home.join(".local/share/Steam");
        write(
            &root.join("steamapps/libraryfolders.vdf"),
            &format!(
                "\"libraryfolders\"\n{{\n\t\"0\"\n\t{{\n\t\t\"path\"\t\t\"{}\"\n\t}}\n\t\"1\"\n\t{{\n\t\t\"path\"\t\t\"{}\"\n\t\t\"apps\" {{ \"3489700\" \"1\" }}\n\t}}\n}}\n",
                root.display(),
                library.display()
            ),
        );
        write(
            &library.join("steamapps/appmanifest_3489700.acf"),
            "\"AppState\"\n{\n\t\"appid\"\t\t\"3489700\"\n\t\"installdir\"\t\t\"StellarBlade\"\n\t\"buildid\"\t\t\"24463856\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"TargetBuildID\"\t\t\"0\"\n}\n",
        );
        write(
            &library.join("steamapps/common/StellarBlade/SB/Binaries/Win64/SB-Win64-Shipping.exe"),
            "exe",
        );
        fs::create_dir_all(library.join("steamapps/compatdata/3489700/pfx/drive_c/users/steamuser/AppData/Local/SB/Saved/SaveGames")).unwrap();
        write(
            &root.join("config/config.vdf"),
            "\"InstallConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"depots\" { \"1\" { \"DecryptionKey\" \"secret\" } } \"CompatToolMapping\" { \"3489700\" { \"name\" \"proton-cachyos-11.0-20261005-slr-x86_64_v3\" \"config\" \"\" } } } } } }",
        );
        write(
            &root.join("userdata/12345/config/localconfig.vdf"),
            "\"UserLocalConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"apps\" { \"3489700\" { \"LaunchOptions\" \"PROTON_ENABLE_WAYLAND=1 %command%\" } } } } } }",
        );
        fs::create_dir_all(home.join(".steam")).unwrap();
        std::os::unix::fs::symlink(&root, home.join(".steam/steam")).unwrap();
    }

    #[test]
    fn finds_the_game_its_prefix_and_proton_in_a_second_library() {
        let root = tempfile::tempdir().unwrap();
        let home = fs::canonicalize(root.path()).unwrap().join("home");
        let library = home.parent().unwrap().join("SteamLibrary");
        fake_steam(&home, &library);
        let steams = steam(&home);
        assert_eq!(steams.len(), 1, "the ~/.steam/steam link is the same Steam");
        assert_eq!(
            steams[0].libraries,
            vec![home.join(".local/share/Steam"), library.clone()]
        );
        let game = game(&steams).unwrap();
        assert_eq!(
            game.win64,
            library.join("steamapps/common/StellarBlade/SB/Binaries/Win64")
        );
        assert!(game.build_supported());
        assert_eq!(game.proton.as_deref(), Some(pins::PROTON_TESTED));
        assert_eq!(
            game.prefix,
            Some(library.join("steamapps/compatdata/3489700/pfx"))
        );
        assert!(game.saves().is_some());
        assert_eq!(
            game.launch_options,
            vec!["PROTON_ENABLE_WAYLAND=1 %command%"]
        );
        assert!(!exe_supported(&game.exe, &mut |_| {}).unwrap());
        assert_eq!(
            dll_override(game.prefix.as_deref(), &game.launch_options),
            Override::Missing
        );
    }

    #[test]
    fn update_in_progress_blocks_the_build_gate() {
        let root = tempfile::tempdir().unwrap();
        let home = fs::canonicalize(root.path()).unwrap().join("home");
        let library = home.parent().unwrap().join("SteamLibrary");
        fake_steam(&home, &library);
        let acf = library.join("steamapps/appmanifest_3489700.acf");
        fs::write(
            &acf,
            fs::read_to_string(&acf).unwrap().replace(
                "\"TargetBuildID\"\t\t\"0\"",
                "\"TargetBuildID\"\t\t\"25000000\"",
            ),
        )
        .unwrap();
        assert!(!game(&steam(&home)).unwrap().build_supported());
    }

    #[test]
    fn override_is_found_in_the_registry_or_the_launch_options() {
        let root = tempfile::tempdir().unwrap();
        let prefix = root.path();
        write(
            &prefix.join("user.reg"),
            "WINE REGISTRY Version 2\n\n[Software\\\\Wine\\\\AppDefaults\\\\SB-Win64-Shipping.exe\\\\DllOverrides] 1759000000\n#time=1dc\n\"xinput1_3\"=\"native,builtin\"\n",
        );
        assert_eq!(dll_override(Some(prefix), &[]), Override::Registry);
        write(
            &prefix.join("user.reg"),
            "[Software\\\\Wine\\\\AppDefaults\\\\Other.exe\\\\DllOverrides] 1\n\"xinput1_3\"=\"native,builtin\"\n",
        );
        assert_eq!(dll_override(Some(prefix), &[]), Override::Missing);
        let options = ["WINEDLLOVERRIDES=\"dinput8,xinput1_3=n,b\" %command%".to_string()];
        assert_eq!(
            dll_override(Some(prefix), &options),
            Override::LaunchOptions
        );
        assert_eq!(
            dll_override(None, &["WINEDLLOVERRIDES=xinput1_3=b %command%".into()]),
            Override::Missing
        );
    }

    #[test]
    fn launch_options_keep_what_is_there() {
        assert_eq!(launch_options_with_override(""), pins::LAUNCH_OPTION);
        assert_eq!(
            launch_options_with_override("PROTON_ENABLE_WAYLAND=1 %command% -dx12"),
            "WINEDLLOVERRIDES=\"xinput1_3=n,b\" PROTON_ENABLE_WAYLAND=1 %command% -dx12"
        );
        assert_eq!(
            launch_options_with_override("WINEDLLOVERRIDES=\"dinput8=n,b\" %command%"),
            "WINEDLLOVERRIDES=\"dinput8=n,b;xinput1_3=n,b\" %command%"
        );
        assert_eq!(
            launch_options_with_override("-skipintro"),
            "WINEDLLOVERRIDES=\"xinput1_3=n,b\" %command% -skipintro"
        );
        let suggested = launch_options_with_override("WINEDLLOVERRIDES=dinput8=n,b %command%");
        assert_eq!(dll_override(None, &[suggested]), Override::LaunchOptions);
    }

    #[test]
    fn ue4ss_layouts_and_moved_mods_folder() {
        let root = tempfile::tempdir().unwrap();
        let win64 = root.path();
        assert_eq!(ue4ss(win64), None);
        write(&win64.join("ue4ss/UE4SS.dll"), "dll");
        fs::create_dir_all(win64.join("ue4ss/Mods")).unwrap();
        let found = ue4ss(win64).unwrap();
        assert_eq!(
            (found.mods, found.known),
            (Some(win64.join("ue4ss/Mods")), false)
        );
        write(
            &win64.join("ue4ss/UE4SS-settings.ini"),
            "[Overrides]\nModsFolderPath = D:\\Mods\n",
        );
        assert_eq!(ue4ss(win64).unwrap().mods, None);
        fs::remove_dir_all(win64.join("ue4ss")).unwrap();
        write(&win64.join("UE4SS.dll"), "old layout");
        fs::create_dir_all(win64.join("Mods")).unwrap();
        assert_eq!(ue4ss(win64).unwrap().mods, Some(win64.join("Mods")));
    }

    #[test]
    fn pack_freshness_follows_the_paks_it_was_built_from() {
        let root = tempfile::tempdir().unwrap();
        let avatar = root.path().join("avatar");
        let paks = root.path().join("Paks");
        assert_eq!(pack(&avatar), Pack::Missing);
        write(&avatar.join("wardrobe.txt"), "SBAVWARDROBE 1\n");
        assert_eq!(pack(&avatar), Pack::Untracked);
        write(&paks.join("pakchunk0.pak"), "pak");
        write(&paks.join("~mods/Outfit_P.utoc"), "mod");
        write(&paks.join("notes.txt"), "ignored");
        let sources = pak_sources(&paks).unwrap();
        let lines: String = sources
            .iter()
            .map(|(path, value)| format!("SOURCE {path} {value}\n"))
            .collect();
        write(
            &avatar.join("pack.txt"),
            &format!(
                "SBAVPACK 1\nPAKS {}\n{lines}MESH body.sbav\nEND\n",
                paks.display()
            ),
        );
        assert_eq!(pack(&avatar), Pack::Fresh);
        fs::remove_file(paks.join("~mods/Outfit_P.utoc")).unwrap();
        write(&paks.join("~mods/New_P.ucas"), "new");
        assert_eq!(
            pack(&avatar),
            Pack::Stale(vec![
                "+~mods/New_P.ucas".into(),
                "-~mods/Outfit_P.utoc".into()
            ])
        );
        write(&avatar.join("pack.txt"), "SBAVPACK 1\nPAKS /x\nJUNK\nEND\n");
        assert_eq!(pack(&avatar), Pack::Unreadable);
    }
}
