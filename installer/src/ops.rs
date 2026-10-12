//! The operations on a chosen target: install (which is also the update), repair, roll back, uninstall and verify.
//! Each one runs its checks before the first write, calls the games-closed guard before every write, leaves the
//! protected files and the Stellar Blade saves exactly as they were, and records what it did in `state.json`.

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use crate::ledger::{self, Kept, State, Status};
use crate::payload::Payload;
use crate::safety::{self, Protected, Snapshot, sha256_file, sha256_hex};
use crate::{Guard, Kind, Ownership, Refusal, adapter, deploy, mods, pins, platform};

/// The Stellar Blade side of a target.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Game {
    pub exe: PathBuf,
    /// UE4SS's own folder.
    pub ue4ss: PathBuf,
    /// The UE4SS `Mods` folder.
    pub mods: PathBuf,
    /// The Proton prefix (`pfx`).
    pub prefix: Option<PathBuf>,
    /// The `SaveGames` folder.
    pub saves: Option<PathBuf>,
}

impl Game {
    fn win64(&self) -> PathBuf {
        self.exe.parent().unwrap_or(Path::new("")).to_path_buf()
    }
}

/// Where the pieces go.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Target {
    /// `None` for a viewer: Minecraft only.
    pub game: Option<Game>,
    /// The Minecraft instance's `mods` folder.
    pub minecraft_mods: PathBuf,
    /// The private bridge directory both sides share; created private when missing (mode 0700 on Linux).
    pub bridge: PathBuf,
}

/// What every operation needs from its caller.
pub struct Env<'a> {
    /// The installer data folder: `state.json`, `previous/`, `changed/`, `save-backups/`.
    pub data: PathBuf,
    /// Refuses while a game runs. Called before every write.
    pub closed: Guard<'a>,
    /// The Stellar Blade executable this release supports: [`pins::SB_EXE_SHA256`] outside tests.
    pub pin: &'a str,
    /// Told when each step starts, for the window's step list and log.
    pub step: &'a dyn Fn(Step),
}

/// The steps an operation goes through, in order; each one is reported when it starts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    /// Games closed, executable pin, ownership, protected-file snapshot.
    Checks,
    SaveBackup,
    KeepPrevious,
    /// Removing the owned pieces being replaced or uninstalled.
    Remove,
    /// The bridge JAR and Fabric API.
    Minecraft,
    LuaMod,
    Adapter,
    /// Rewriting drifted owned files.
    Repair,
    Verify,
    /// A failure: putting the previous state back.
    Restore,
    Record,
}

pub const JAR: &str = "Bridge JAR";
pub const LUA: &str = "Lua mod";
pub const ADAPTER: &str = "Input adapter";

/// One installed piece.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Piece {
    pub name: &'static str,
    pub ownership: Ownership,
    /// Owned, unchanged, and the same content as the payload.
    pub current: bool,
    /// Modified, but only in files its manifest lists: an update replaces it (see [`replaceable`]).
    pub replaceable: bool,
}

/// Whether an update may replace the piece `name` on `target`: owned, or modified only in files its manifest lists
/// (an edit by hand, a file an antivirus quarantined). The update keeps it first (in `changed/` once done) and the consent page
/// lists those files. Foreign content, unlisted files, changed folders and links never are.
pub fn replaceable(target: &Target, name: &str) -> bool {
    match (name, &target.game) {
        (JAR, _) => mods::drifted(&target.minecraft_mods).is_ok(),
        (LUA, Some(game)) => deploy::drifted(&game.mods).is_ok(),
        (ADAPTER, Some(game)) => adapter::drifted(&game.win64()).is_ok(),
        _ => false,
    }
}

/// The files of ours that changed or went missing since they were installed, in the pieces an update replaces.
pub fn changed_files(target: &Target) -> Vec<PathBuf> {
    let mut files = Vec::new();
    let mut add = |dir: &Path, drift: Result<Vec<(String, String)>>| {
        files.extend(drift.into_iter().flatten().map(|(name, _)| dir.join(name)));
    };
    let mods_dir = &target.minecraft_mods;
    add(mods_dir, mods::drifted(mods_dir));
    if let Some(game) = &target.game {
        add(
            &game.mods.join(deploy::MOD_NAME),
            deploy::drifted(&game.mods),
        );
        add(&game.win64(), adapter::drifted(&game.win64()));
    }
    files
}

/// Whether a file of ours on `target` holds content changed by hand: changed, not only missing.
fn changed_by_hand(target: &Target) -> bool {
    changed_files(target)
        .iter()
        .any(|path| fs::symlink_metadata(path).is_ok())
}

/// Where a piece is on `target`: what a failure about it names.
fn piece_path(target: &Target, name: &str) -> PathBuf {
    match (name, &target.game) {
        (LUA, Some(game)) => game.mods.join(deploy::MOD_NAME),
        (ADAPTER, Some(game)) => game.win64().join(adapter::DLL),
        _ => target.minecraft_mods.clone(),
    }
}

fn hashes<'a>(files: impl IntoIterator<Item = (&'a str, &'a [u8])>) -> BTreeMap<String, String> {
    files
        .into_iter()
        .map(|(name, bytes)| (name.to_string(), sha256_hex(bytes)))
        .collect()
}

/// Whether the bridge files in `mods` (owned, as `ownership` says) are exactly `payload`'s JAR and Fabric API.
pub(crate) fn jar_current(
    mods_dir: &Path,
    ownership: &Ownership,
    payload: &Payload,
) -> Result<bool> {
    Ok(
        *ownership == Ownership::Owned && mods::drifted(mods_dir)?.is_empty() && {
            let listed = mods::manifest(mods_dir)?.files;
            let fabric = sha256_hex(&payload.fabric_api);
            listed.get(&payload.jar.0) == Some(&sha256_hex(&payload.jar.1))
                && listed.iter().all(|(name, hash)| {
                    name == &payload.jar.0 || (mods::is_fabric_api(name) && *hash == fabric)
                })
        },
    )
}

/// What the target holds, piece by piece, compared with `payload`.
pub fn verify(env: &Env, target: &Target, payload: &Payload) -> Result<Vec<Piece>> {
    let mut pieces = Vec::new();
    // A piece the payload does not carry (a kept set from the Python tools has no JAR) is current when absent.
    let absent = |ownership: &Ownership| *ownership == Ownership::Absent;
    let ownership = mods::ownership(&target.minecraft_mods)?;
    let current = if payload.jar.1.is_empty() {
        absent(&ownership)
    } else {
        jar_current(&target.minecraft_mods, &ownership, payload)?
    };
    let modified = |name: &str, ownership: &Ownership| {
        matches!(ownership, Ownership::Modified(_)) && replaceable(target, name)
    };
    pieces.push(Piece {
        name: JAR,
        replaceable: modified(JAR, &ownership),
        ownership,
        current,
    });
    if let Some(game) = &target.game {
        let ownership = deploy::ownership(&game.mods)?;
        let current = if payload.lua.is_empty() {
            absent(&ownership)
        } else {
            ownership == Ownership::Owned && {
                let expected = deploy::config(&target.bridge).map(|config| {
                    let mut files =
                        hashes(payload.lua.iter().map(|(n, b)| (n.as_str(), b.as_slice())));
                    files.insert(deploy::CONFIG.into(), sha256_hex(&config));
                    files
                });
                expected.is_ok_and(|e| {
                    e == deploy::manifest(&game.mods)
                        .map(|m| m.files)
                        .unwrap_or_default()
                })
            }
        };
        pieces.push(Piece {
            name: LUA,
            replaceable: modified(LUA, &ownership),
            ownership,
            current,
        });
        let ownership = adapter::ownership(&game.win64())?;
        let current = if payload.dll.is_empty() {
            absent(&ownership)
        } else {
            ownership == Ownership::Owned && {
                let expected = adapter::directory_file(&target.bridge).map(|directory| {
                    hashes([
                        (adapter::CONFIG, directory.as_slice()),
                        (adapter::DLL, payload.dll.as_slice()),
                    ])
                });
                // Installed for another executable (before a Steam update): the update records the pinned one.
                expected.is_ok_and(|e| {
                    adapter::manifest(&game.win64())
                        .is_ok_and(|m| m.files == e && m.executable == env.pin)
                })
            }
        };
        pieces.push(Piece {
            name: ADAPTER,
            replaceable: modified(ADAPTER, &ownership),
            ownership,
            current,
        });
    }
    Ok(pieces)
}

/// The protected files around the target. `own_jars` are the names in `mods` this run may add, change or remove.
fn protected(target: &Target, own_jars: Vec<String>) -> Protected {
    let game = target.game.as_ref();
    Protected {
        win64: game.map(Game::win64),
        ue4ss: game.map(|g| g.ue4ss.clone()),
        mods: game.map(|g| g.mods.clone()),
        prefix: game.and_then(|g| g.prefix.clone()),
        minecraft: target.minecraft_mods.parent().map(Path::to_path_buf),
        own_jars,
    }
}

/// Our names in `mods`: the manifest and what it lists, plus what `payload` would add.
fn own_jars(target: &Target, payload: Option<&Payload>) -> Result<Vec<String>> {
    let mut own = vec![mods::MANIFEST.to_string()];
    if let Ok(manifest) = mods::manifest(&target.minecraft_mods) {
        for name in manifest.files.into_keys() {
            own.push(format!("{name}{}", mods::DISABLED));
            own.push(name);
        }
    }
    if let Some(payload) = payload {
        own.push(format!("{}{}", payload.jar.0, mods::DISABLED));
        own.push(payload.jar.0.clone());
        let mut names = fs::read_dir(&target.minecraft_mods)?;
        if !names.any(|e| e.is_ok_and(|e| mods::is_fabric_api(&e.file_name().to_string_lossy()))) {
            own.push(pins::FABRIC_API_FILE.into());
        }
    }
    Ok(own)
}

fn saves(target: &Target) -> Result<Option<BTreeMap<String, String>>> {
    target
        .game
        .as_ref()
        .and_then(|g| g.saves.as_deref())
        .map(safety::tree)
        .transpose()
}

/// What must hold after every operation: protected files and saves exactly as before.
struct Guarded {
    protected: Protected,
    before: Snapshot,
    saves: Option<BTreeMap<String, String>>,
}

impl Guarded {
    fn take(target: &Target, own: Vec<String>) -> Result<Self> {
        let protected = protected(target, own);
        Ok(Self {
            before: protected.snapshot()?,
            protected,
            saves: saves(target)?,
        })
    }

    fn unchanged(&self, target: &Target) -> Result<()> {
        let changed = self.before.changed(&self.protected.snapshot()?);
        if !changed.is_empty() {
            bail!(Refusal::new(Kind::ProtectedChanged, changed.join(", ")));
        }
        if saves(target)? != self.saves {
            let saves = target.game.as_ref().and_then(|g| g.saves.as_deref());
            bail!(Refusal::new(
                Kind::SavesChanged,
                saves.unwrap_or(Path::new("")).display()
            ));
        }
        Ok(())
    }
}

/// Paths of every owned piece: what an uninstall removes, and, `replacing` also the modified pieces an update may
/// replace, what an update keeps in `previous/` before replacing them.
pub fn owned_paths(target: &Target, replacing: bool) -> Result<Vec<PathBuf>> {
    let taken = |name: &str, ownership: Ownership| match ownership {
        Ownership::Owned => true,
        Ownership::Modified(_) => replacing && replaceable(target, name),
        _ => false,
    };
    let mut paths = Vec::new();
    let mods_dir = &target.minecraft_mods;
    // A dedicated instance about to be created, or a deleted one, has no mods folder: nothing is owned there.
    if taken(JAR, mods::ownership(mods_dir)?) {
        paths.push(mods_dir.join(mods::MANIFEST));
        paths.extend(mods::on_disk(mods_dir)?);
    }
    if let Some(game) = &target.game {
        if taken(LUA, deploy::ownership(&game.mods)?) {
            paths.push(game.mods.join(deploy::MOD_NAME));
        }
        if taken(ADAPTER, adapter::ownership(&game.win64())?) {
            paths.extend(
                [adapter::MANIFEST, adapter::CONFIG, adapter::DLL]
                    .map(|n| game.win64().join(n))
                    .into_iter()
                    .filter(|path| fs::symlink_metadata(path).is_ok()),
            );
        }
    }
    Ok(paths)
}

/// The pieces left alone, by name, and why.
pub type Refusals = Vec<(&'static str, Refusal)>;

/// Removes every owned piece, and with `replacing` the modified pieces an update may replace; foreign or other
/// modified pieces are left alone and returned as refusals.
fn remove_owned(
    env: &Env,
    target: &Target,
    keep_fabric: bool,
    replacing: bool,
) -> Result<Refusals> {
    let mut refusals = Vec::new();
    let mut take = |name: &'static str,
                    ownership: Ownership,
                    remove: &dyn Fn() -> Result<()>,
                    replace: &dyn Fn() -> Result<()>|
     -> Result<()> {
        match ownership {
            Ownership::Absent => Ok(()),
            Ownership::Owned => remove(),
            Ownership::Modified(_) if replacing && replaceable(target, name) => replace(),
            Ownership::Foreign(refusal) | Ownership::Modified(refusal) => {
                refusals.push((name, refusal));
                Ok(())
            }
        }
    };
    if let Some(game) = &target.game {
        take(
            ADAPTER,
            adapter::ownership(&game.win64())?,
            &|| adapter::remove(&game.exe, env.closed).map(drop),
            &|| adapter::remove_drifted(&game.exe, env.closed).map(drop),
        )?;
        take(
            LUA,
            deploy::ownership(&game.mods)?,
            &|| deploy::remove(&game.mods, env.closed).map(drop),
            &|| deploy::remove_drifted(&game.mods, env.closed).map(drop),
        )?;
    }
    let mods_dir = &target.minecraft_mods;
    take(
        JAR,
        mods::ownership(mods_dir)?,
        &|| mods::remove(mods_dir, keep_fabric, env.closed),
        &|| mods::remove_drifted(mods_dir, env.closed),
    )?;
    Ok(refusals)
}

/// Creates the private bridge directory (mode 0700 on Linux) when it is missing.
fn ensure_bridge(bridge: &Path, closed: Guard) -> Result<()> {
    if !bridge.is_absolute() {
        bail!(Refusal::new(Kind::BridgeNotAbsolute, bridge.display()));
    }
    if !bridge.exists() {
        closed()?;
        if let Some(parent) = bridge.parent() {
            fs::create_dir_all(parent)?;
        }
        platform::create_private_dir(bridge)?;
    }
    Ok(())
}

/// Writes every piece `payload` carries: Fabric API and the JAR (`disabled` as the replaced one was), then the Lua
/// mod, then the adapter.
fn put(env: &Env, target: &Target, payload: &Payload, disabled: bool) -> Result<()> {
    if !payload.jar.1.is_empty() {
        (env.step)(Step::Minecraft);
        mods::install(
            &target.minecraft_mods,
            (&payload.jar.0, &payload.jar.1),
            &payload.fabric_api,
            disabled,
            env.closed,
        )?;
    }
    if let Some(game) = &target.game {
        (env.step)(Step::LuaMod);
        ensure_bridge(&target.bridge, env.closed)?;
        if !payload.lua.is_empty() {
            deploy::install(&game.mods, &payload.lua, Some(&target.bridge), env.closed)?;
        }
        if !payload.dll.is_empty() {
            (env.step)(Step::Adapter);
            adapter::install(&game.exe, &payload.dll, &target.bridge, env.pin, env.closed)?;
        }
    }
    Ok(())
}

/// Every piece current, protected files and saves unchanged.
fn check(env: &Env, target: &Target, payload: &Payload, guarded: &Guarded) -> Result<()> {
    (env.step)(Step::Verify);
    if let Some(piece) = verify(env, target, payload)?
        .into_iter()
        .find(|p| !p.current)
    {
        bail!(Refusal::new(
            Kind::MismatchAfterWrite,
            piece_path(target, piece.name).display()
        ));
    }
    guarded.unchanged(target)
}

/// The checks every operation runs before its first write.
fn gates(target: &Target, env: &Env) -> Result<()> {
    (env.step)(Step::Checks);
    (env.closed)()?;
    if !target.minecraft_mods.is_dir() {
        bail!(Refusal::new(
            Kind::ModsMissing,
            target.minecraft_mods.display()
        ));
    }
    if let Some(game) = &target.game {
        platform::real_dir(&game.mods)?;
        // Checked here, not first when the Lua mod is written: by then the owned pieces would be gone.
        platform::bridge_ready(&target.bridge)?;
        if sha256_file(&platform::real_file(&game.exe)?, &mut |_| {})? != env.pin {
            bail!(Refusal::new(Kind::UnsupportedExe, game.exe.display()));
        }
    }
    Ok(())
}

fn state_path(env: &Env) -> PathBuf {
    env.data.join(ledger::STATE)
}

fn same(a: &Path, b: &Path) -> bool {
    a == b
        || platform::canonicalize(a).is_ok_and(|a| platform::canonicalize(b).is_ok_and(|b| a == b))
}

/// The recorded install's owned game-side pieces that are not where `target` puts them (UE4SS moved its `Mods`
/// folder, or the game moved while its old folder stayed): the old `Mods` folder holding the Lua mod, and the old
/// executable's folder holding the adapter (the executable may be gone). Read before the run records its new target.
#[derive(Default)]
struct Moved {
    mods: Option<PathBuf>,
    win64: Option<PathBuf>,
}

impl Moved {
    fn find(env: &Env, target: &Target) -> Moved {
        let old = ledger::load(&state_path(env)).and_then(|s| s.target?.game);
        let (Some(old), Some(new)) = (old, &target.game) else {
            return Moved::default();
        };
        let owned = |ownership: Result<Ownership>| matches!(ownership, Ok(Ownership::Owned));
        Moved {
            mods: (!same(&old.mods, &new.mods) && owned(deploy::ownership(&old.mods)))
                .then(|| old.mods.clone()),
            win64: (!same(&old.win64(), &new.win64()) && owned(adapter::ownership(&old.win64())))
                .then(|| old.win64()),
        }
    }

    /// What an update keeps in `previous/` beside the pieces at `target`.
    fn paths(&self) -> Vec<PathBuf> {
        let mut paths: Vec<PathBuf> = self.mods.iter().map(|m| m.join(deploy::MOD_NAME)).collect();
        if let Some(win64) = &self.win64 {
            paths.extend([adapter::MANIFEST, adapter::CONFIG, adapter::DLL].map(|n| win64.join(n)));
        }
        paths
    }

    fn remove(&self, closed: Guard) -> Result<()> {
        if let Some(mods) = &self.mods {
            deploy::remove(mods, closed)?;
        }
        if let Some(win64) = &self.win64 {
            adapter::remove_at(win64, closed)?;
        }
        Ok(())
    }
}

/// The recorded install's owned game-side pieces left at a moved place, which an install, a roll back or an uninstall
/// at `target` removes there.
pub fn moved_paths(env: &Env, target: &Target) -> Vec<PathBuf> {
    Moved::find(env, target).paths()
}

/// Refuses a target that would leave the recorded install's owned pieces behind, where no later run would look.
fn left_behind(env: &Env, target: &Target) -> Result<()> {
    let Some(old) = ledger::load(&state_path(env)).and_then(|s| s.target) else {
        return Ok(());
    };
    let owned = |ownership: Result<Ownership>| matches!(ownership, Ok(Ownership::Owned));
    let mut left = Vec::new();
    if !same(&old.minecraft_mods, &target.minecraft_mods)
        && owned(mods::ownership(&old.minecraft_mods))
    {
        left.push(old.minecraft_mods.display().to_string());
    }
    if let (Some(game), None) = (&old.game, &target.game) {
        if owned(deploy::ownership(&game.mods)) {
            left.push(game.mods.join(deploy::MOD_NAME).display().to_string());
        }
        if owned(adapter::ownership(&game.win64())) {
            left.push(game.win64().join(adapter::DLL).display().to_string());
        }
    }
    if !left.is_empty() {
        bail!(Refusal::new(Kind::LeftBehind, left.join(", ")));
    }
    Ok(())
}

/// The release the owned set on disk belongs to, as `previous/` names it.
fn old_release(state: &State) -> String {
    match state.release.as_str() {
        "" => "untracked".to_string(),
        release => release.to_string(),
    }
}

/// What an interrupted run already kept, when the next run finishes it: the owned set on disk is then partly that run's
/// own, so it must not be kept over the complete one, nor kept at all when that run had nothing to keep. A record
/// without `kept` (the 0.3 installers) counts only `previous/<release on record>`: an older kept set means the
/// interrupted run stopped before it kept anything, and a finished run never records the set of the release it
/// installed.
fn resumed(env: &Env, state: &State) -> Option<Kept> {
    if state.status != Status::InProgress {
        return None;
    }
    match &state.kept {
        Some(Kept::Set(set)) if !set.is_dir() => None,
        Some(kept) => Some(kept.clone()),
        None => {
            let kept = env.data.join("previous").join(old_release(state));
            (state.previous.as_ref() == Some(&kept) && kept.is_dir()).then_some(Kept::Set(kept))
        }
    }
}

/// Each path's content (`None` when missing; a folder by its files), to tell whether a run changed them.
fn contents(paths: &[PathBuf]) -> Result<Vec<Option<BTreeMap<String, String>>>> {
    paths
        .iter()
        .map(|path| match fs::symlink_metadata(path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.into()),
            Ok(m) if m.is_dir() => safety::tree(path).map(Some),
            Ok(_) => Ok(Some(BTreeMap::from([(
                String::new(),
                sha256_file(path, &mut |_| {})?,
            )]))),
        })
        .collect()
}

/// Puts the state before a failed install back: removes what is at `target`, then copies the set `kept` (nothing for a
/// first install) back where it came from, file for file, so files changed by hand and a disabled JAR come back as they
/// were. Manifests first and the DLL last: a run killed meanwhile leaves pieces the next one owns or replaces.
fn restore(env: &Env, target: &Target, kept: Option<&Path>) -> Result<()> {
    if let Some((_, refusal)) = remove_owned(env, target, false, true)?.into_iter().next() {
        bail!(refusal);
    }
    let mut back = Vec::new();
    for entry in kept.map(fs::read_dir).transpose()?.into_iter().flatten() {
        let from = entry?.path();
        let name = from.file_name().unwrap_or_default();
        let dir = match (name.to_str(), &target.game) {
            (Some(deploy::MOD_NAME), Some(game)) => game.mods.clone(),
            (Some(adapter::MANIFEST | adapter::CONFIG | adapter::DLL), Some(game)) => game.win64(),
            _ => target.minecraft_mods.clone(),
        };
        back.push((dir.join(name), from));
    }
    let manifest =
        |path: &Path| path.ends_with(mods::MANIFEST) || path.ends_with(adapter::MANIFEST);
    back.sort_by_key(|(to, _)| (!manifest(to), to.ends_with(adapter::DLL)));
    for (to, from) in &back {
        (env.closed)()?;
        let (dir, name) = (
            to.parent().unwrap_or(Path::new("")),
            to.file_name().unwrap_or_default(),
        );
        if from.is_dir() {
            // Whole or not at all, like a Lua mod install.
            let staging = dir.join(deploy::STAGING);
            if fs::symlink_metadata(&staging).is_ok() {
                fs::remove_dir_all(&staging)?;
            }
            safety::copy_tree(from, &staging)?;
            fs::rename(&staging, to)?;
        } else {
            mods::write_atomic(dir, &name.to_string_lossy(), &fs::read(from)?)?;
        }
    }
    let (to, from): (Vec<_>, Vec<_>) = back.into_iter().unzip();
    if contents(&to)? != contents(&from)? {
        bail!(Refusal::new(
            Kind::MismatchAfterWrite,
            kept.unwrap_or(Path::new("")).display()
        ));
    }
    Ok(())
}

/// Whether [`install`] takes a kept set on `target`, so Roll back can go to it: a set kept while a whole piece was
/// missing (a bridge JAR deleted by hand, a Lua mod gone with a UE4SS reinstall) loads, but without that piece.
fn installable(set: &Payload, target: &Target) -> bool {
    !set.jar.1.is_empty() && (target.game.is_none() || !(set.lua.is_empty() || set.dll.is_empty()))
}

/// Whether Roll back from the release `installed` can go to the kept `set`: [`install`] takes it on `target`, and it
/// holds another release. Every set `state.json` names for Roll back passes this.
fn goes_back(set: &Path, target: &Target, installed: &str) -> bool {
    Payload::from_previous(set).is_ok_and(|p| installable(&p, target) && p.release != installed)
}

/// Installs `payload` on `target`, replacing an owned older install (an update). Foreign or modified pieces stop it
/// before anything changes. Records "in progress" first, makes the first-install save backup, keeps the replaced set in
/// `previous/`, then writes and verifies; on any failure it removes what it wrote and puts the kept set back.
pub fn install(env: &Env, target: &Target, payload: &Payload) -> Result<State> {
    gates(target, env)?;
    left_behind(env, target)?;
    let moved = Moved::find(env, target);
    if payload.jar.1.is_empty() {
        bail!(Refusal::new(Kind::NoJar, &payload.release));
    }
    if target.game.is_some() && (payload.lua.is_empty() || payload.dll.is_empty()) {
        bail!(Refusal::new(Kind::NoGameSide, &payload.release));
    }
    let pieces = verify(env, target, payload)?;
    for piece in &pieces {
        match &piece.ownership {
            // Kept in previous/ and replaced; the consent page listed its changed files.
            Ownership::Modified(_) if piece.replaceable => {}
            Ownership::Foreign(refusal) | Ownership::Modified(refusal) => bail!(refusal.clone()),
            _ => {}
        }
    }
    let path = state_path(env);
    let recorded = ledger::load(&path);
    // What a restore that puts the disk back as it was records again; pieces without a record count as installed.
    let status_before = recorded.as_ref().map_or(Status::Installed, |s| s.status);
    let mut state = recorded.unwrap_or_default();
    let guarded = Guarded::take(target, own_jars(target, Some(payload))?)?;
    fs::create_dir_all(&env.data)?;
    let resumed = resumed(env, &state);
    let before = state.previous.clone();
    // The set this run kept, or the interrupted run it finishes: it decides where Roll back goes.
    let finished = if pieces.iter().all(|p| p.current) {
        guarded.unchanged(target)?;
        // An interrupted run killed after its last write: finishing it is recording what it kept.
        match resumed {
            Some(Kept::Set(kept)) => Some(kept),
            _ => None,
        }
    } else {
        let old_release = old_release(&state);
        state.status = Status::InProgress;
        if resumed.is_none() {
            state.kept = None;
        }
        // A place being left keeps its record until its pieces are gone: a run that stops before finds it again.
        if moved.paths().is_empty() {
            state.target = Some(target.clone());
        }
        ledger::save(&path, &state)?;
        let saves = target.game.as_ref().and_then(|g| g.saves.as_deref());
        if let (Some(saves), false) = (
            saves,
            state.save_backup.as_ref().is_some_and(|b| b.is_dir()),
        ) {
            (env.step)(Step::SaveBackup);
            state.save_backup = Some(safety::backup_saves(saves, &env.data.join("save-backups"))?);
            ledger::save(&path, &state)?;
        }
        let mut owned = owned_paths(target, true)?;
        // A piece at both places (mods copied over by hand) is kept once: the one at `target`, which Roll back
        // reinstalls there. The old copy is still removed.
        for path in moved.paths() {
            if !owned.iter().any(|o| o.file_name() == path.file_name()) {
                owned.push(path);
            }
        }
        // What the first removal changes, as it was: a run that fails and finds it so changed nothing. Only when this
        // run replaces an installed set: a resumed run starts from a partial one.
        let untouched = (resumed.is_none() && !owned.is_empty())
            .then(|| contents(&owned))
            .transpose()?;
        let kept = match resumed.clone() {
            Some(kept) => {
                // Nothing is kept for Roll back, but a file changed by hand since the run stopped is the user's: the set
                // holding it goes to changed/, as the consent page says.
                if changed_by_hand(target) {
                    (env.step)(Step::KeepPrevious);
                    let items: Vec<&Path> = owned.iter().map(PathBuf::as_path).collect();
                    ledger::keep_previous(&env.data, &old_release, &items)?;
                }
                kept
            }
            None => {
                let kept = if owned.is_empty() {
                    Kept::Nothing
                } else {
                    (env.step)(Step::KeepPrevious);
                    let items: Vec<&Path> = owned.iter().map(PathBuf::as_path).collect();
                    let set = ledger::keep_previous(&env.data, &old_release, &items)?;
                    // While this run is interrupted, Roll back undoes it, when it can: a set with files changed by hand
                    // or without a piece cannot be installed again, so Roll back keeps going to the one kept before.
                    if goes_back(&set, target, &payload.release) {
                        state.previous = Some(set.clone());
                    }
                    Kept::Set(set)
                };
                state.kept = Some(kept.clone());
                ledger::save(&path, &state)?;
                kept
            }
        };
        let previous = match kept {
            Kept::Set(set) => Some(set),
            Kept::Nothing => None,
        };
        // A bridge JAR the user disabled in the launcher stays disabled.
        let disabled = mods::bridge_disabled(&target.minecraft_mods);
        (env.step)(Step::Remove);
        let removed = moved
            .remove(env.closed)
            .and_then(|()| {
                state.target = Some(target.clone());
                ledger::save(&path, &state)
            })
            .and_then(|()| remove_owned(env, target, false, true));
        // Stopped while removing, before any of them (a game found running at the first removal, a locked file): already
        // back, also when the old place is elsewhere.
        let untouched = removed.is_err()
            && untouched.is_some_and(|before| contents(&owned).is_ok_and(|now| now == before));
        let done = removed
            .and_then(|_| put(env, target, payload, disabled))
            .and_then(|()| check(env, target, payload, &guarded));
        if let Err(error) = done {
            (env.step)(Step::Restore);
            let restored = if untouched {
                Ok(())
            } else {
                restore(env, target, previous.as_deref())
            };
            return Err(match restored {
                Ok(()) => {
                    // The disk is as recorded before this run, or before the interrupted run it finished.
                    state.status = match (&previous, &resumed) {
                        (None, _) => Status::Removed,
                        (Some(_), Some(_)) => Status::Installed,
                        (Some(_), None) => status_before,
                    };
                    if let Some(previous) = &previous {
                        state.previous = ledger::previous_without(
                            &env.data,
                            previous,
                            before.as_deref(),
                            |p| goes_back(p, target, &state.release),
                        )?;
                    }
                    state.kept = None;
                    ledger::save(&path, &state)?;
                    // Only once recorded: stopped before, the set is only an extra one, which the next run clears.
                    if let Some(previous) = &previous {
                        fs::remove_dir_all(previous)?;
                    }
                    error.context(Refusal::new(Kind::InstallRestored, ""))
                }
                Err(undo) => error
                    .context(format!(
                        "the previous state could not be restored: {undo:#}"
                    ))
                    .context(match previous {
                        Some(previous) => {
                            Refusal::new(Kind::InstallNotRestored, previous.display())
                        }
                        // A first install replaced nothing: what it wrote is left behind.
                        None => Refusal::new(Kind::InstallNotRemoved, ""),
                    }),
            });
        }
        previous
    };
    if let Some(kept) = &finished {
        state.previous = Some(kept.clone());
    }
    // Roll back goes to the set this run kept, unless it cannot: files changed by hand (the commit below sets that set
    // aside, where no later run deletes it), a piece missing, or the release installed now (Roll back of an
    // interrupted update, a reinstall for a new game build, an install after Uninstall). It then keeps going to the
    // set kept before, under the same condition.
    if let Some(set) = state
        .previous
        .clone()
        .filter(|p| !goes_back(p, target, &payload.release))
    {
        state.previous = ledger::previous_without(&env.data, &set, before.as_deref(), |p| {
            goes_back(p, target, &payload.release)
        })?;
    }
    (env.step)(Step::Record);
    state.status = Status::Installed;
    state.kept = None;
    state.target = Some(target.clone());
    state.release = payload.release.clone();
    state.lua_revision = payload.lua_revision;
    ledger::save(&path, &state)?;
    // Only once recorded: stopped before, `previous/` holds only extra sets, which the next run clears.
    if finished.is_some() {
        ledger::commit_previous(&env.data, state.previous.as_deref())?;
    }
    Ok(state)
}

/// What [`install`] would do on `target`, without writing anything: shown before the user consents, and all a dry run
/// does.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Plan {
    /// Files and folders created or replaced, in the order they are written; empty when the release is in place.
    pub writes: Vec<PathBuf>,
    /// Owned files the install replaces, kept in `previous/` first; none when it finishes an interrupted run.
    pub replaced: Vec<PathBuf>,
    /// Files of ours that changed or went missing since they were installed (by hand, an antivirus): the install
    /// replaces them, keeping the changed ones first, in `changed/` once done.
    pub changed: Vec<PathBuf>,
    /// Copies made before the first write.
    pub backups: Vec<PathBuf>,
}

pub fn plan(env: &Env, target: &Target, payload: &Payload) -> Result<Plan> {
    left_behind(env, target)?;
    let mods_dir = &target.minecraft_mods;
    let exists = mods_dir.is_dir();
    if exists && verify(env, target, payload)?.iter().all(|p| p.current) {
        return Ok(Plan::default());
    }
    let mut plan = Plan::default();
    if !payload.jar.1.is_empty() {
        plan.writes.push(mods_dir.join(mods::MANIFEST));
        // Fabric API comes with the JAR unless the folder has one the installer did not add.
        let listed = mods::manifest(mods_dir)
            .map(|m| m.files)
            .unwrap_or_default();
        let mut foreign_fabric = false;
        if exists {
            for entry in fs::read_dir(mods_dir)? {
                let name = entry?.file_name().to_string_lossy().into_owned();
                foreign_fabric |= mods::is_fabric_api(&name) && !listed.contains_key(&name);
            }
        }
        if !foreign_fabric {
            plan.writes.push(mods_dir.join(pins::FABRIC_API_FILE));
        }
        let disabled = exists && mods::bridge_disabled(mods_dir);
        let suffix = if disabled { mods::DISABLED } else { "" };
        plan.writes
            .push(mods_dir.join(format!("{}{suffix}", payload.jar.0)));
    }
    if let Some(game) = &target.game {
        if !target.bridge.exists() {
            plan.writes.push(target.bridge.clone());
        }
        if !payload.lua.is_empty() {
            plan.writes.push(game.mods.join(deploy::MOD_NAME));
        }
        if !payload.dll.is_empty() {
            plan.writes.extend(
                [adapter::CONFIG, adapter::MANIFEST, adapter::DLL].map(|n| game.win64().join(n)),
            );
        }
    }
    plan.writes.push(state_path(env));
    let state = ledger::load(&state_path(env)).unwrap_or_default();
    plan.changed = changed_files(target);
    // Finishing an interrupted run keeps nothing for Roll back: that run kept the set it replaced. Only a set with
    // files changed by hand since is kept, in changed/.
    if resumed(env, &state).is_none() {
        plan.replaced = owned_paths(target, true)?;
        plan.replaced.extend(Moved::find(env, target).paths());
        if !plan.replaced.is_empty() {
            plan.backups
                .push(env.data.join("previous").join(old_release(&state)));
        }
    } else if changed_by_hand(target) {
        plan.backups.push(env.data.join("changed"));
    }
    let saves = target.game.as_ref().and_then(|g| g.saves.as_ref());
    if saves.is_some() && !state.save_backup.is_some_and(|b| b.is_dir()) {
        plan.backups.push(env.data.join("save-backups"));
    }
    Ok(plan)
}

/// Rolls back to the set kept in `previous/` by the last install, through the same checks; the replaced set becomes
/// the new previous one.
pub fn rollback(env: &Env, target: &Target) -> Result<State> {
    let state = ledger::load(&state_path(env)).unwrap_or_default();
    let previous = state
        .previous
        .filter(|p| p.is_dir())
        .context(Refusal::new(Kind::NoPrevious, ""))?;
    let payload = Payload::from_previous(&previous)?;
    install(env, target, &payload)
}

/// Puts back owned files that changed or went missing, with content identical to what their manifest records (and
/// to `payload`). Unlisted or foreign content is never touched: the repair refuses before writing anything. Returns
/// the repaired paths.
pub fn repair(env: &Env, target: &Target, payload: &Payload) -> Result<Vec<PathBuf>> {
    gates(target, env)?;
    let guarded = Guarded::take(target, own_jars(target, Some(payload))?)?;
    let plan = repair_plan(env, target, payload)?;
    if plan.is_empty() {
        return Ok(Vec::new());
    }
    (env.step)(Step::Repair);
    for (path, bytes) in &plan {
        (env.closed)()?;
        let name = path.file_name().unwrap_or_default().to_string_lossy();
        mods::write_atomic(path.parent().unwrap_or(Path::new("")), &name, bytes)?;
    }
    (env.step)(Step::Verify);
    for piece in verify(env, target, payload)? {
        if !matches!(piece.ownership, Ownership::Owned | Ownership::Absent) {
            bail!(Refusal::new(
                Kind::StillModified,
                piece_path(target, piece.name).display()
            ));
        }
    }
    guarded.unchanged(target)?;
    Ok(plan.into_iter().map(|(path, _)| path).collect())
}

/// What [`repair`] would write: each drifted owned file with the content its manifest records, activating files last.
/// Refuses, without writing, what a repair cannot fix.
pub fn repair_plan(
    env: &Env,
    target: &Target,
    payload: &Payload,
) -> Result<Vec<(PathBuf, Vec<u8>)>> {
    match ledger::load(&state_path(env)).map(|s| s.status) {
        // An interrupted run left pieces removed or half replaced, with no manifest saying what they should be.
        Some(Status::InProgress) => bail!(Refusal::new(Kind::Interrupted, "")),
        // What an Uninstall left in place (files changed by hand) is the user's.
        Some(Status::Removed) => return Ok(Vec::new()),
        // A recorded piece gone as a whole has no manifest left to repair from: Install puts it back.
        Some(Status::Installed) => {
            if let Some(piece) = verify(env, target, payload)?
                .into_iter()
                .find(|p| p.ownership == Ownership::Absent)
            {
                bail!(Refusal::new(
                    Kind::PieceMissing,
                    piece_path(target, piece.name).display()
                ));
            }
        }
        None => {}
    }
    let mut plan: Vec<(PathBuf, Vec<u8>)> = Vec::new();
    let mut add = |dir: &Path,
                   ownership: Ownership,
                   drifted: &dyn Fn() -> Result<Vec<(String, String)>>,
                   content: &dyn Fn(&str) -> Option<Vec<u8>>|
     -> Result<()> {
        match ownership {
            Ownership::Absent => return Ok(()),
            Ownership::Foreign(refusal) => bail!(refusal),
            Ownership::Owned | Ownership::Modified(_) => {}
        }
        for (name, expected) in drifted()? {
            match content(&name) {
                Some(bytes) if sha256_hex(&bytes) == expected => {
                    plan.push((dir.join(&name), bytes))
                }
                _ => bail!(Refusal::new(Kind::RunUpdate, dir.join(&name).display())),
            }
        }
        Ok(())
    };
    let mods_dir = &target.minecraft_mods;
    add(
        mods_dir,
        mods::ownership(mods_dir)?,
        &|| mods::drifted(mods_dir),
        // A disabled copy is put back disabled.
        &|name| {
            let name = name.strip_suffix(mods::DISABLED).unwrap_or(name);
            if name == payload.jar.0 {
                Some(payload.jar.1.clone())
            } else {
                mods::is_fabric_api(name).then(|| payload.fabric_api.clone())
            }
        },
    )?;
    if let Some(game) = &target.game {
        add(
            &game.mods.join(deploy::MOD_NAME),
            deploy::ownership(&game.mods)?,
            &|| deploy::drifted(&game.mods),
            &|relative| match relative {
                deploy::CONFIG => deploy::config(&target.bridge).ok(),
                _ => payload.lua.get(relative).cloned(),
            },
        )?;
        add(
            &game.win64(),
            adapter::ownership(&game.win64())?,
            &|| adapter::drifted(&game.win64()),
            &|name| match name {
                adapter::DLL => Some(payload.dll.clone()),
                adapter::CONFIG => adapter::directory_file(&target.bridge).ok(),
                _ => None,
            },
        )?;
    }
    // What activates a piece comes last.
    let activates = |path: &Path| {
        let name = path.file_name().unwrap_or_default().to_string_lossy();
        name == "enabled.txt" || name == adapter::DLL || mods::is_bridge_jar(&name)
    };
    plan.sort_by_key(|(path, _)| activates(path));
    Ok(plan)
}

/// Removes every owned piece. Foreign or modified pieces are never forced: they are returned as typed refusals, with their paths.
/// Fabric API goes too only with `remove_fabric` (other mods may need it); a created instance, the pack, the save
/// backups and `state.json` stay.
pub fn uninstall(env: &Env, target: &Target, remove_fabric: bool) -> Result<Refusals> {
    (env.step)(Step::Checks);
    (env.closed)()?;
    let guarded = Guarded::take(target, own_jars(target, None)?)?;
    let path = state_path(env);
    let mut state = ledger::load(&path);
    // Recorded first: a run stopped midway leaves pieces removed, which Install again finishes and Repair refuses.
    if let Some(state) = &mut state {
        state.status = Status::InProgress;
        ledger::save(&path, state)?;
    }
    (env.step)(Step::Remove);
    Moved::find(env, target).remove(env.closed)?;
    let refusals = remove_owned(env, target, !remove_fabric, false)?;
    (env.step)(Step::Verify);
    guarded.unchanged(target)?;
    // Removed also with pieces left in place: they changed by hand, and are the user's now.
    if let Some(state) = &mut state {
        state.status = Status::Removed;
        state.kept = None;
        ledger::save(&path, state)?;
    }
    Ok(refusals)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A large modpack: the review page's plan asks about every JAR a dozen times, and must open and parse each one only
    /// once. Counted, not timed: a timing limit failed on a loaded machine.
    #[test]
    fn planning_beside_a_large_modpack_reads_each_jar_once() {
        use std::io::Write;
        let root = tempfile::tempdir().unwrap();
        let home = platform::canonicalize(root.path()).unwrap();
        let target = Target {
            game: None,
            minecraft_mods: home.join("mods"),
            bridge: home.join("bridge"),
        };
        fs::create_dir(&target.minecraft_mods).unwrap();
        let env = Env {
            data: home.join("data"),
            closed: &|| Ok(()),
            pin: "",
            step: &|_| {},
        };
        let v1 = Payload {
            release: "0.1.0+test".into(),
            lua_revision: None,
            lua: Default::default(),
            dll: Vec::new(),
            jar: ("stellar-bridge-0.1.0.jar".into(), b"jar v1".to_vec()),
            fabric_api: b"fabric api".to_vec(),
            licenses: BTreeMap::new(),
        };
        install(&env, &target, &v1).unwrap();
        let mut jar = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        jar.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        jar.write_all(br#"{"schemaVersion": 1, "id": "somemod"}"#)
            .unwrap();
        let bytes = jar.finish().unwrap().into_inner();
        let modpack: Vec<PathBuf> = (0..20)
            .map(|n| target.minecraft_mods.join(format!("mod-{n}.jar")))
            .collect();
        for path in &modpack {
            fs::write(path, &bytes).unwrap();
        }
        let v2 = Payload {
            release: "0.2.0+test".into(),
            jar: (v1.jar.0.clone(), b"jar v2".to_vec()),
            ..v1
        };
        assert!(!plan(&env, &target, &v2).unwrap().writes.is_empty());
        let read = mods::READ.lock().unwrap();
        for path in &modpack {
            let times = read.iter().filter(|p| *p == path).count();
            assert_eq!(times, 1, "{} read {times} times", path.display());
        }
    }
}
