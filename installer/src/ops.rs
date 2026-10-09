//! The operations on a chosen target: install (which is also the update), repair, roll back, uninstall and verify.
//! Each one runs its checks before the first write, calls the games-closed guard before every write, leaves the
//! protected files and the Stellar Blade saves exactly as they were, and records what it did in `state.json`.

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::os::unix::fs::DirBuilderExt;
use std::path::{Path, PathBuf};

use crate::ledger::{self, State, Status};
use crate::payload::Payload;
use crate::safety::{self, Protected, Snapshot, sha256_file, sha256_hex};
use crate::{Guard, Ownership, adapter, deploy, mods, pins, platform};

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
    /// The private bridge directory both sides share; created with mode 0700 when missing.
    pub bridge: PathBuf,
}

/// What every operation needs from its caller.
pub struct Env<'a> {
    /// The installer data folder: `state.json`, `previous/`, `save-backups/`.
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
}

fn hashes<'a>(files: impl IntoIterator<Item = (&'a str, &'a [u8])>) -> BTreeMap<String, String> {
    files
        .into_iter()
        .map(|(name, bytes)| (name.to_string(), sha256_hex(bytes)))
        .collect()
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
        ownership == Ownership::Owned && mods::drifted(&target.minecraft_mods)?.is_empty() && {
            let listed = mods::manifest(&target.minecraft_mods)?.files;
            let fabric = sha256_hex(&payload.fabric_api);
            listed.get(&payload.jar.0) == Some(&sha256_hex(&payload.jar.1))
                && listed.iter().all(|(name, hash)| {
                    name == &payload.jar.0 || (mods::is_fabric_api(name) && *hash == fabric)
                })
        }
    };
    pieces.push(Piece {
        name: JAR,
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
            ownership,
            current,
        });
        let ownership = adapter::ownership(&game.win64(), env.pin)?;
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
                expected.is_ok_and(|e| {
                    Some(e)
                        == adapter::manifest(&game.win64(), env.pin)
                            .ok()
                            .map(|m| m.files)
                })
            }
        };
        pieces.push(Piece {
            name: ADAPTER,
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
            bail!("Protected files changed: {}", changed.join(", "));
        }
        if saves(target)? != self.saves {
            bail!("The Stellar Blade saves changed");
        }
        Ok(())
    }
}

/// Paths of every owned piece: what an update keeps in `previous/` before replacing it, and what an uninstall removes.
pub fn owned_paths(env: &Env, target: &Target) -> Result<Vec<PathBuf>> {
    let mut paths = Vec::new();
    let mods_dir = &target.minecraft_mods;
    // A dedicated instance about to be created, or a deleted one, has no mods folder: nothing is owned there.
    if mods::ownership(mods_dir)? == Ownership::Owned {
        paths.push(mods_dir.join(mods::MANIFEST));
        paths.extend(mods::present(mods_dir)?.into_iter().map(|(_, path)| path));
    }
    if let Some(game) = &target.game {
        if deploy::ownership(&game.mods)? == Ownership::Owned {
            paths.push(game.mods.join(deploy::MOD_NAME));
        }
        if adapter::ownership(&game.win64(), env.pin)? == Ownership::Owned {
            paths.extend(
                [adapter::MANIFEST, adapter::CONFIG, adapter::DLL].map(|n| game.win64().join(n)),
            );
        }
    }
    Ok(paths)
}

/// Removes every owned piece; foreign or modified pieces are left alone and returned as refusals.
fn remove_owned(env: &Env, target: &Target, keep_fabric: bool) -> Result<Vec<String>> {
    let mut refusals = Vec::new();
    let mut take =
        |name: &str, ownership: Ownership, remove: &dyn Fn() -> Result<()>| -> Result<()> {
            match ownership {
                Ownership::Absent => Ok(()),
                Ownership::Owned => remove(),
                Ownership::Foreign(refusal) | Ownership::Modified(refusal) => {
                    refusals.push(format!("{name}: {refusal}"));
                    Ok(())
                }
            }
        };
    if let Some(game) = &target.game {
        take(
            ADAPTER,
            adapter::ownership(&game.win64(), env.pin)?,
            &|| adapter::remove(&game.exe, env.pin, env.closed).map(drop),
        )?;
        take(LUA, deploy::ownership(&game.mods)?, &|| {
            deploy::remove(&game.mods, env.closed).map(drop)
        })?;
    }
    take(JAR, mods::ownership(&target.minecraft_mods)?, &|| {
        mods::remove(&target.minecraft_mods, keep_fabric, env.closed)
    })?;
    Ok(refusals)
}

/// Creates the private bridge directory (mode 0700) when it is missing.
fn ensure_bridge(bridge: &Path, closed: Guard) -> Result<()> {
    if !bridge.is_absolute() {
        bail!(
            "The bridge directory must be absolute: {}",
            bridge.display()
        );
    }
    if !bridge.exists() {
        closed()?;
        if let Some(parent) = bridge.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::DirBuilder::new().mode(0o700).create(bridge)?;
    }
    Ok(())
}

/// Writes every piece `payload` carries: Fabric API and the JAR, then the Lua mod, then the adapter.
fn put(env: &Env, target: &Target, payload: &Payload) -> Result<()> {
    if !payload.jar.1.is_empty() {
        (env.step)(Step::Minecraft);
        mods::install(
            &target.minecraft_mods,
            (&payload.jar.0, &payload.jar.1),
            &payload.fabric_api,
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
        bail!(
            "{} does not match {} after writing: {:?}",
            piece.name,
            payload.release,
            piece.ownership
        );
    }
    guarded.unchanged(target)
}

/// The checks every operation runs before its first write.
fn gates(target: &Target, env: &Env) -> Result<()> {
    (env.step)(Step::Checks);
    (env.closed)()?;
    if !target.minecraft_mods.is_dir() {
        bail!(
            "The Minecraft mods folder does not exist: {}",
            target.minecraft_mods.display()
        );
    }
    if let Some(game) = &target.game {
        platform::real_dir(&game.mods)?;
        // Checked here, not first when the Lua mod is written: by then the owned pieces would be gone.
        platform::bridge_ready(&target.bridge)?;
        if sha256_file(&platform::real_file(&game.exe)?, &mut |_| {})? != env.pin {
            bail!(
                "Unsupported Stellar Blade executable: wait for a bridge release that supports this game version"
            );
        }
    }
    Ok(())
}

fn state_path(env: &Env) -> PathBuf {
    env.data.join(ledger::STATE)
}

/// Installs `payload` on `target`, replacing an owned older install (an update). Foreign or modified pieces stop it
/// before anything changes. Records "in progress" first, makes the first-install save backup, keeps the replaced set in
/// `previous/`, then writes and verifies; on any failure it removes what it wrote and puts the kept set back.
pub fn install(env: &Env, target: &Target, payload: &Payload) -> Result<State> {
    gates(target, env)?;
    if payload.jar.1.is_empty() {
        bail!("{} has no bridge JAR to install", payload.release);
    }
    if target.game.is_some() && (payload.lua.is_empty() || payload.dll.is_empty()) {
        bail!("{} has no Stellar Blade side to install", payload.release);
    }
    let pieces = verify(env, target, payload)?;
    for piece in &pieces {
        if let Ownership::Foreign(refusal) | Ownership::Modified(refusal) = &piece.ownership {
            bail!("{}: {refusal}", piece.name);
        }
    }
    let path = state_path(env);
    let mut state = ledger::load(&path).unwrap_or_default();
    let guarded = Guarded::take(target, own_jars(target, Some(payload))?)?;
    fs::create_dir_all(&env.data)?;
    if pieces.iter().all(|p| p.current) {
        guarded.unchanged(target)?;
    } else {
        let old_release = match state.release.as_str() {
            "" => "untracked".to_string(),
            release => release.to_string(),
        };
        state.status = Status::InProgress;
        state.target = Some(target.clone());
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
        let owned = owned_paths(env, target)?;
        let mut previous = None;
        if !owned.is_empty() {
            (env.step)(Step::KeepPrevious);
            let items: Vec<&Path> = owned.iter().map(PathBuf::as_path).collect();
            previous = Some(ledger::keep_previous(&env.data, &old_release, &items)?);
            state.previous = previous.clone();
            ledger::save(&path, &state)?;
        }
        (env.step)(Step::Remove);
        let done = remove_owned(env, target, false)
            .and_then(|_| put(env, target, payload))
            .and_then(|()| check(env, target, payload, &guarded));
        if let Err(error) = done {
            (env.step)(Step::Restore);
            let restored = remove_owned(env, target, false).and_then(|refusals| {
                if !refusals.is_empty() {
                    bail!(refusals.join("; "));
                }
                if let Some(previous) = &previous {
                    let kept = Payload::from_previous(previous, env.pin)?;
                    put(env, target, &kept)?;
                    check(env, target, &kept, &guarded)?;
                }
                Ok(())
            });
            return Err(match restored {
                Ok(()) => {
                    state.status = if previous.is_some() { Status::Installed } else { Status::Removed };
                    ledger::save(&path, &state)?;
                    error.context("Installation failed; the previous state was restored")
                }
                Err(undo) => error.context(format!(
                    "Installation failed and the previous state could not be restored ({undo:#}); the replaced \
                     files are kept in {}",
                    previous.map_or("no previous set".into(), |p| p.display().to_string())
                )),
            });
        }
    }
    (env.step)(Step::Record);
    state.status = Status::Installed;
    state.target = Some(target.clone());
    state.release = payload.release.clone();
    state.lua_revision = payload.lua_revision;
    ledger::save(&path, &state)?;
    Ok(state)
}

/// What [`install`] would do on `target`, without writing anything: shown before the user consents, and all a dry run
/// does.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Plan {
    /// Files and folders created or replaced, in the order they are written; empty when the release is in place.
    pub writes: Vec<PathBuf>,
    /// Owned files the install replaces, kept in `previous/` first.
    pub replaced: Vec<PathBuf>,
    /// Copies made before the first write.
    pub backups: Vec<PathBuf>,
}

pub fn plan(env: &Env, target: &Target, payload: &Payload) -> Result<Plan> {
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
        plan.writes.push(mods_dir.join(&payload.jar.0));
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
    plan.replaced = owned_paths(env, target)?;
    let state = ledger::load(&state_path(env)).unwrap_or_default();
    if !plan.replaced.is_empty() {
        let release = if state.release.is_empty() {
            "untracked"
        } else {
            &state.release
        };
        plan.backups.push(env.data.join("previous").join(release));
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
        .context("There is no previous set to roll back to")?;
    let payload = Payload::from_previous(&previous, env.pin)?;
    install(env, target, &payload)
}

/// Puts back owned files that changed or went missing, with content identical to what their manifest records (and
/// to `payload`). Unlisted or foreign content is never touched: the repair refuses before writing anything. Returns
/// the repaired paths.
pub fn repair(env: &Env, target: &Target, payload: &Payload) -> Result<Vec<PathBuf>> {
    gates(target, env)?;
    let guarded = Guarded::take(target, own_jars(target, Some(payload))?)?;
    let plan = repair_plan(env, target, payload)?;
    (env.step)(Step::Repair);
    for (path, bytes) in &plan {
        (env.closed)()?;
        let name = path.file_name().unwrap_or_default().to_string_lossy();
        mods::write_atomic(path.parent().unwrap_or(Path::new("")), &name, bytes)?;
    }
    (env.step)(Step::Verify);
    for piece in verify(env, target, payload)? {
        if !matches!(piece.ownership, Ownership::Owned | Ownership::Absent) {
            bail!(
                "{} is still not as installed: {:?}",
                piece.name,
                piece.ownership
            );
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
                _ => bail!(
                    "{} differs from what this installer carries: run the update instead",
                    dir.join(&name).display()
                ),
            }
        }
        Ok(())
    };
    let mods_dir = &target.minecraft_mods;
    add(
        mods_dir,
        mods::ownership(mods_dir)?,
        &|| mods::drifted(mods_dir),
        &|name| {
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
            adapter::ownership(&game.win64(), env.pin)?,
            &|| adapter::drifted(&game.win64(), env.pin),
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

/// Removes every owned piece. Foreign or modified pieces are never forced: they are returned as refusals, verbatim.
/// Fabric API goes too only with `remove_fabric` (other mods may need it); a created instance, the pack, the save
/// backups and `state.json` stay.
pub fn uninstall(env: &Env, target: &Target, remove_fabric: bool) -> Result<Vec<String>> {
    (env.step)(Step::Checks);
    (env.closed)()?;
    let guarded = Guarded::take(target, own_jars(target, None)?)?;
    (env.step)(Step::Remove);
    let refusals = remove_owned(env, target, !remove_fabric)?;
    (env.step)(Step::Verify);
    guarded.unchanged(target)?;
    let path = state_path(env);
    if let Some(mut state) = ledger::load(&path)
        && refusals.is_empty()
    {
        state.status = Status::Removed;
        ledger::save(&path, &state)?;
    }
    Ok(refusals)
}
