//! One pack build, both games closed. It reads the item tables and the owned items, builds the new pack in
//! `avatar.tmp` next to the installed `avatar`, then swaps it in: `avatar` → `avatar.old`, `avatar.tmp` → `avatar`,
//! `avatar.old` deleted. A cancelled or failed build deletes `avatar.tmp` and the work folder and leaves the installed
//! pack as it was.
//!
//! When the installed pack was built from the same game files (its `pack.txt` sources match the Paks), the build
//! starts from a copy of it and exports only the meshes it lacks (and those whose sections it could not texture last
//! time). Otherwise it starts from nothing: the core step exports the nine core meshes as new, the 30 effect, trail
//! and HUD textures, and the sword skeleton. Mesh exports run in batches; each batch's export folder is deleted once
//! its materials are baked.

use anyhow::{Context, Result, bail};
use rayon::prelude::*;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use super::bake::{self, Export};
use super::catalog;
use super::extract::{self, Cancelled, Sidecar};
use super::manifest::{self, Checks, last};
use super::owned;
use crate::{Guard, detect, log, platform};

/// The meshes every pack holds: face, default hair, ponytail, suit, hairpin, earrings, sword and the two drone bodies.
pub const CORE_MESHES: &[&str] = &[
    "SB/Content/Art/Character/PC/CH_P_EVE_Head/CH_P_EVE_Face_Jali3",
    "SB/Content/Art/Character/PC/00_HR/EVE_HR_01/EVE_HR_01",
    "SB/Content/Art/Character/PC/CH_P_EVE_Hair/CH_P_EVE_Hair_PonyTail_2",
    "SB/Content/Art/Character/PC/CH_P_EVE_52/CH_P_EVE_52_TypeB",
    "SB/Content/Art/Character/Weapon/Sword/CH_ACC_HairPin/CH_ACC_HairPin",
    "SB/Content/Art/Character/PC/00_ACC/ACC_EAR_19/ACC_EAR_19M",
    "SB/Content/Art/Character/Weapon/Sword/CH_W_Sword_01/CH_W_Sword_01",
    "SB/Content/Art/Character/NPC/CH_NPC_Drone/CH_NPC_Drone",
    "SB/Content/Art/Character/NPC/CH_NPC_Drone/CH_NPC_Drone_Bear",
];

/// The effect, trail and HUD textures of the core step (`bake::FX_TEXTURES`, `bake::trail`, `bake::HUD_TEXTURES`).
pub const CORE_TEXTURES: &[&str] = &[
    "SB/Content/Art/FX/Tex/Particles/T_A_SparkParticle_01",
    "SB/Content/Art/FX/Tex/Particles/T_A_BreakCrystal_01",
    "SB/Content/Art/FX/Tex/Flare/T_A_HorizonFlare_03",
    "SB/Content/Art/FX/Tex/Flare/T_A_LensFlare_01",
    "SB/Content/Art/FX/Tex/Flare/T_A_ShineFlare_01",
    "SB/Content/Art/FX/Tex/Electric/T_Semicircle_Arcs_2x2",
    "SB/Content/Art/FX/Tex/Electric/T_B_Electric_01",
    "SB/Content/Art/FX/Tex/Trail/T_A_StreakSwirl_01",
    "SB/Content/Art/FX/Tex/Trail/T_A_AnimTrail_01",
    "SB/Content/Art/FX/Tex/Trail/T_A_StreakNoise_05",
    "SB/Content/Art/UI/Texture/HUD/Tk_Idle_Base2",
    "SB/Content/Art/UI/Texture/HUD/Tk_Idle_Icon",
    "SB/Content/Art/UI/Texture/HUD/skill_hud_dashframe",
    "SB/Content/Art/UI/Texture/HUD/potion",
    "SB/Content/Art/UI/Texture/HUD/bullet_icon_01",
    "SB/Content/Art/UI/Texture/HUD/bullet_icon_02",
    "SB/Content/Art/UI/Texture/HUD/bullet_icon_03",
    "SB/Content/Art/UI/Texture/HUD/bullet_icon_04",
    "SB/Content/Art/UI/Texture/HUD/bullet_icon_05",
    "SB/Content/Art/UI/Texture/Skill/skill_icon/Skill_Icon_Dash",
    "SB/Content/Art/UI/Texture/PhotoMode/Icons/Icon_Camera",
    "SB/Content/Art/UI/Texture/Item/Icon_PotionR",
    "SB/Content/Art/UI/Texture/Item/Icon_Potion",
    "SB/Content/Art/UI/Texture/Item/Icon_PotionSmall",
    "SB/Content/Art/UI/Texture/Item/HealGrenade",
    "SB/Content/Art/UI/Texture/Item/Grenade",
    "SB/Content/Art/UI/Texture/Item/PluseGrenade",
    "SB/Content/Art/UI/Texture/Item/TrapGrenade",
    "SB/Content/Art/UI/Texture/Item/FlashGrenade",
    "SB/Content/Art/UI/Texture/Item/WbPump",
];

/// Dumped with `json`; its sockets place the sword trail.
pub const SWORD_SKELETON: &str =
    "SB/Content/Art/Character/Weapon/Sword/CH_W_Sword_01/CH_W_Sword_01_Skeleton";

/// The wardrobe screen's own icons (locks, empty slots).
pub const CHROME: &[&str] = &[
    "SB/Content/Art/UI/Texture/Equip/EquipLock",
    "SB/Content/Art/UI/Texture/Equip/ExospineNormal",
    "SB/Content/Art/UI/Texture/Equip/CommonGearDim",
    "SB/Content/Art/UI/Texture/Equip/Lookfocus_Icon00",
    "SB/Content/Art/UI/Texture/Equip/Lookfocus_Icon01",
    "SB/Content/Art/UI/Texture/Equip/Lookfocus_Icon02",
    "SB/Content/Art/UI/Texture/Equip/Lookfocus_Icon03",
    "SB/Content/Art/UI/Texture/Equip/Lookfocus_Icon06",
    "SB/Content/Art/UI/Texture/Common/icon_equipment_color",
];

/// Meshes per `export` run (its output is printed when the run ends) and icons per run.
const MESH_BATCH: usize = 8;
const ICON_BATCH: usize = 80;
// ponytail: about 100 paths per `avatar` run keeps a Windows command line far below its 32,767 characters; one job
// file per build if the mount cost matters.
const AVATAR_BATCH: usize = 100;
/// The memory one bake worker may take: a 4096² texture, its result and a resize pass.
const WORKER_MEMORY: u64 = 512 << 20;

/// What one build reads and where it writes.
#[derive(Debug, Clone)]
pub struct Build {
    pub sidecar: Sidecar,
    /// The folder holding `avatar/` and `bridge/` (`detect::bridge_home`).
    pub home: PathBuf,
    /// Scratch for EveExtract's output, deleted when the build ends.
    pub work: PathBuf,
    /// Where Stellar Blade's saves may be (`platform::save_dirs`).
    pub save_dirs: Vec<PathBuf>,
    /// The player's language for item names (`fr`, `en`); English is the fallback.
    pub culture: String,
    /// Every wardrobe outfit, not only the owned ones: a bigger, slower build that lets this player's Minecraft draw
    /// friends' outfits in multiplayer.
    pub every: bool,
    /// The game's Steam build id, which the usmap describes; named when EveExtract cannot read the item table.
    pub build_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    /// EveExtract and the usmap are made ready (the window's step, before the build itself).
    Inputs,
    Tables,
    Meshes,
    Export,
    Bake,
    Install,
}

/// Where a build is: its step, the item it works on and the whole build's fraction done (tables 5 %, mesh scan
/// 10 %, exports and bakes 80 %, install 5 %).
#[derive(Debug, Clone)]
pub struct Progress {
    pub step: Step,
    pub item: String,
    pub fraction: f32,
}

/// What a finished build did.
#[derive(Debug, Clone)]
pub struct Report {
    pub checks: Checks,
    /// The owned items and where they came from; `None` for an every-outfit build.
    pub owned: Option<(usize, owned::Source)>,
    /// Built from the installed pack (same game files) rather than from nothing.
    pub incremental: bool,
    pub meshes_exported: usize,
    /// Wardrobe meshes the game files lack.
    pub missing: Vec<String>,
}

/// Runs EveExtract in one mode over some paths, each output line to the callback.
pub type Extract<'a> = dyn FnMut(&str, &[String], &mut dyn FnMut(&str)) -> Result<()> + 'a;

/// A build with the EveExtract sidecar. `guard` runs before the build and again before the swap (games closed).
pub fn run(
    build: &Build,
    guard: Guard,
    cancel: &AtomicBool,
    progress: &(dyn Fn(Progress) + Sync),
) -> Result<Report> {
    let mut extract = |mode: &str, paths: &[String], line: &mut dyn FnMut(&str)| {
        build.sidecar.run(&build.work, mode, paths, cancel, line)
    };
    run_with(build, guard, cancel, progress, &mut extract)
}

fn remove(path: &Path) -> Result<()> {
    match fs::remove_dir_all(path) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
            Err(e).with_context(|| format!("{} not removed", path.display()))
        }
        _ => Ok(()),
    }
}

/// [`run`] with another extractor (the tests' stand-in).
pub fn run_with(
    build: &Build,
    guard: Guard,
    cancel: &AtomicBool,
    progress: &(dyn Fn(Progress) + Sync),
    extract: &mut Extract,
) -> Result<Report> {
    guard()?;
    let (avatar, tmp, old) = (
        build.home.join("avatar"),
        build.home.join("avatar.tmp"),
        build.home.join("avatar.old"),
    );
    // A swap cut short between its two renames: the previous pack goes back first.
    if !avatar.exists() && old.exists() {
        fs::rename(&old, &avatar)?;
    }
    remove(&old)?;
    remove(&tmp)?;
    remove(&build.work)?;
    fs::create_dir_all(&build.work)?;
    if !build.home.exists() {
        fs::create_dir_all(
            build
                .home
                .parent()
                .context("no parent for the pack folder")?,
        )?;
        platform::create_private_dir(&build.home)?;
    }
    let result = assemble(build, &avatar, &tmp, guard, cancel, progress, extract);
    if let Err(error) = &result {
        log::line(&format!(
            "pack: build stopped, the installed pack is unchanged: {error:#}"
        ));
        let _ = fs::remove_dir_all(&tmp);
    }
    let _ = fs::remove_dir_all(&build.work);
    result
}

fn assemble(
    build: &Build,
    avatar: &Path,
    tmp: &Path,
    guard: Guard,
    cancel: &AtomicBool,
    progress: &(dyn Fn(Progress) + Sync),
    extract: &mut Extract,
) -> Result<Report> {
    let work = build.work.as_path();
    let check = || {
        if cancel.load(Ordering::Relaxed) {
            Err(anyhow::Error::new(Cancelled))
        } else {
            Ok(())
        }
    };
    let say = |step, fraction: f32, item: &str| {
        progress(Progress {
            step,
            item: item.to_string(),
            fraction,
        })
    };
    let mut warnings: Vec<String> = Vec::new();

    // The item tables and names, in the player's language with English as the fallback.
    say(Step::Tables, 0.0, "");
    // The item table read through the usmap is its check: a usmap of another game build cannot read it.
    let tables: Vec<String> = catalog::TABLES.iter().map(|t| t.to_string()).collect();
    let mut read = false;
    extract("json", &tables, &mut |line| {
        read |= extract::item(line) == Some((true, catalog::TABLES[0]));
    })?;
    if !read {
        bail!(crate::Refusal::new(
            crate::Kind::UsmapRejected,
            build.build_id.as_deref().unwrap_or("?")
        ));
    }
    let mut cultures = vec![build.culture.as_str()];
    if build.culture != "en" {
        cultures.push("en");
    }
    let mut found = Vec::new();
    for culture in cultures {
        check()?;
        extract("raw", &[catalog::locres_path(culture)], &mut |_| {})?;
        let raw = work.join("raw");
        if fs::rename(
            raw.join("Game.locres"),
            raw.join(format!("{culture}.locres")),
        )
        .is_ok()
        {
            found.push(culture);
        }
    }
    if found.is_empty() {
        bail!("the game's item names (Game.locres) could not be read");
    }
    let rows = catalog::rows(work, &found).context("the game's item tables could not be read")?;
    if rows.is_empty() {
        bail!("the game's item tables list no wardrobe item");
    }
    let owned = match build.every {
        true => None,
        false => Some(owned::owned(
            &build.home.join("bridge").join("eve-wardrobe.txt"),
            &build.save_dirs,
            &rows,
        )?),
    };
    say(Step::Tables, 0.05, "");

    // The installed pack is the base when it was built from the same game files.
    let sources =
        detect::pak_sources(&build.sidecar.paks).context("the Paks folder could not be listed")?;
    let base_manifest = fs::read_to_string(avatar.join("pack.txt")).ok();
    let base = base_manifest
        .as_deref()
        .and_then(detect::pack_manifest)
        .filter(|(_, built)| *built == sources)
        .map(|_| avatar.to_path_buf());
    let checks_file = build.home.join("avatar.checks.json");
    let retry = match base {
        Some(_) => manifest::last_untextured(&checks_file),
        None => BTreeMap::new(),
    };
    platform::create_private_dir(tmp)?;
    // ponytail: the base is copied (about 3 GB for a full pack), as pack.py did; hard links would need every write in
    // avatar.tmp to replace its file rather than write through it.
    if let Some(base) = &base {
        for entry in fs::read_dir(base)? {
            check()?;
            let entry = entry?;
            if entry.file_type()?.is_file() {
                fs::copy(entry.path(), tmp.join(entry.file_name()))?;
            }
        }
    }
    let base_has = |file: &str| base.as_ref().is_some_and(|b| b.join(file).exists());

    // The core step: effect sprites, HUD textures and the sword trail, when the base lacks them.
    let core = [
        "CH_W_Sword_01.trail.txt",
        "fx.Ring.png",
        "hud.Tk_Idle_Base2.png",
    ];
    if !core.iter().all(|f| tmp.join(f).exists()) {
        check()?;
        let textures: Vec<String> = CORE_TEXTURES.iter().map(|t| t.to_string()).collect();
        extract("export", &textures, &mut |_| {})?;
        extract("json", &[SWORD_SKELETON.to_string()], &mut |_| {})?;
        let export = Export::scan(&work.join("export"))?;
        if !tmp.join(core[0]).exists() {
            let file = work
                .join("json")
                .join(format!("{}.json", last(SWORD_SKELETON)));
            let skeleton = serde_json::from_slice(
                &fs::read(&file).context("the sword skeleton was not dumped")?,
            )?;
            bake::trail(&export, &skeleton, tmp)?;
        }
        if !tmp.join(core[1]).exists() {
            bake::fx_textures(&export, tmp)?;
        }
        if !tmp.join(core[2]).exists() {
            bake::hud_textures(&export, tmp)?;
        }
        remove(&work.join("export"))?;
    }

    // Every wardrobe mesh once, for its bone count; the wanted ones go into the pack.
    let every: Vec<String> = rows
        .iter()
        .flat_map(|r| r.meshes.iter().cloned())
        .chain(CORE_MESHES.iter().map(|m| m.to_string()))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let mut scanned = 0;
    for batch in every.chunks(AVATAR_BATCH) {
        check()?;
        extract("avatar", batch, &mut |line| {
            if let Some((_, item)) = extract::item(line) {
                scanned += 1;
                say(
                    Step::Meshes,
                    0.05 + 0.10 * scanned as f32 / every.len() as f32,
                    last(item),
                );
            }
        })?;
    }
    let scan = work.join("avatar");
    let extracted: HashSet<String> = fs::read_dir(&scan)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            e.file_name()
                .to_str()?
                .strip_suffix(".sbav")
                .map(String::from)
        })
        .collect();
    let missing: Vec<String> = every
        .iter()
        .filter(|p| !extracted.contains(last(p)))
        .cloned()
        .collect();
    let owned_ids: Option<HashSet<u64>> =
        owned.as_ref().map(|(ids, _)| ids.iter().copied().collect());
    let wanted: Vec<&String> = rows
        .iter()
        .filter(|r| owned_ids.as_ref().is_none_or(|ids| ids.contains(&r.id)))
        .flat_map(|r| r.meshes.iter())
        .chain(every.iter().filter(|m| CORE_MESHES.contains(&m.as_str())))
        .filter(|p| extracted.contains(last(p)))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let names: BTreeSet<&str> = wanted.iter().map(|p| last(p)).collect();
    let mut changed = Vec::new();
    for name in &names {
        let (from, to) = (
            scan.join(format!("{name}.sbav")),
            tmp.join(format!("{name}.sbav")),
        );
        if to.exists() && fs::read(&to)? != fs::read(&from)? {
            changed.push(name.to_string());
        }
        fs::copy(&from, &to)?;
    }

    let mesh_list = fs::read_to_string(scan.join("meshes.txt")).unwrap_or_default();
    let materials = materials(&mesh_list, &mut warnings);

    let mut meshes: BTreeMap<String, (u32, Vec<String>)> = BTreeMap::new();
    for entry in fs::read_dir(tmp)? {
        let path = entry?.path();
        if let Some(stem) = path
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| n.strip_suffix(".sbav"))
        {
            let header =
                manifest::header(&fs::read(&path)?).with_context(|| format!("{stem}.sbav"))?;
            meshes.insert(stem.to_string(), header);
        }
    }
    let unbaked = |section: &String| {
        !manifest::UNDRAWN.contains(&section.as_str())
            && !manifest::BASES
                .iter()
                .any(|k| tmp.join(format!("{section}.{k}png")).exists())
    };
    let todo: BTreeSet<String> = meshes
        .values()
        .flat_map(|(_, s)| s.iter().filter(|s| unbaked(s)).cloned())
        .collect();
    // New meshes, and those whose materials the last build could not bake.
    let new: Vec<String> = wanted
        .iter()
        .filter(|p| retry.contains_key(last(p)) || !base_has(&format!("{}.sbav", last(p))))
        .map(|p| p.to_string())
        .collect();

    // Exports and bakes, a batch of meshes at a time; each batch's raw export is deleted once baked.
    let workers = workers();
    log::line(&format!(
        "pack: {} meshes to export, {} materials to bake, {workers} bake workers",
        new.len(),
        todo.len()
    ));
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(workers)
        .build()?;
    let units = (new.len() + todo.len()).max(1) as f32;
    let done = AtomicUsize::new(0);
    let mut attempted: HashSet<String> = HashSet::new();
    let mut baked = 0;
    let mut bake_some =
        |list: Vec<String>, export: &Export, warnings: &mut Vec<String>| -> Result<()> {
            let results: Vec<(String, Result<&str, String>)> = pool.install(|| {
                list.par_iter()
                    .map(|m| {
                        if cancel.load(Ordering::Relaxed) {
                            return (m.clone(), Err(String::new()));
                        }
                        let result =
                            bake::material(m, materials.get(m).map(String::as_str), export, tmp);
                        let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                        say(Step::Bake, 0.15 + 0.80 * n as f32 / units, m);
                        (m.clone(), result)
                    })
                    .collect()
            });
            check()?;
            for (m, result) in results {
                match result {
                    Ok(_) => baked += 1,
                    Err(error) => warnings.push(format!("material {m} skipped: {error}")),
                }
            }
            Ok(())
        };
    for batch in new.chunks(MESH_BATCH) {
        check()?;
        extract("export", batch, &mut |_| {})?;
        let n = done.fetch_add(batch.len(), Ordering::Relaxed) + batch.len();
        say(
            Step::Export,
            0.15 + 0.80 * n as f32 / units,
            last(&batch[0]),
        );
        let export = Export::scan(&work.join("export"))?;
        let list: Vec<String> = batch
            .iter()
            .filter_map(|p| meshes.get(last(p)))
            .flat_map(|(_, sections)| sections.iter())
            // A material shared by name is baked from the one `materials` keeps, in the batch that exports it.
            .filter(|s| {
                todo.contains(*s)
                    && materials
                        .get(*s)
                        .is_none_or(|path| export.find(path, ".json").is_some())
                    && attempted.insert(s.to_string())
            })
            .cloned()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        bake_some(list, &export, &mut warnings)?;
        remove(&work.join("export"))?;
    }
    // The base meshes' materials not baked yet have no export: they are listed as skipped.
    let rest: Vec<String> = todo
        .iter()
        .filter(|s| !attempted.contains(*s))
        .cloned()
        .collect();
    bake_some(rest, &Export::default(), &mut warnings)?;

    // Wardrobe icons (white glyphs on alpha, whole texture at 64²).
    let mut icons: HashMap<&str, &str> = HashMap::new();
    let mut wanted_icons: Vec<(&str, &str)> = Vec::new();
    for path in CHROME
        .iter()
        .copied()
        .chain(rows.iter().map(|r| r.icon.as_str()))
        .filter(|p| !p.is_empty())
    {
        let name = last(path);
        match icons.get(name) {
            Some(&first) if first != path => {
                warnings.push(format!("icon name {name}: {path} drawn as {first}"))
            }
            Some(_) => {}
            None => {
                icons.insert(name, path);
                if !tmp.join(format!("hud.{name}.png")).exists() {
                    wanted_icons.push((name, path));
                }
            }
        }
    }
    let paths: Vec<String> = wanted_icons.iter().map(|(_, p)| p.to_string()).collect();
    for batch in paths.chunks(ICON_BATCH) {
        check()?;
        extract("export", batch, &mut |_| {})?;
    }
    let export = Export::scan(&work.join("export"))?;
    for (name, path) in wanted_icons {
        match export.find(path, ".png") {
            Some(source) => bake::icon(source, tmp, name)?,
            None => warnings.push(format!("icon {name}: not exported")),
        }
    }
    remove(&work.join("export"))?;

    // wardrobe.txt and pack.txt.
    let mut bones: BTreeMap<String, u32> = BTreeMap::new();
    for name in &extracted {
        if let Ok((count, _)) = fs::read(scan.join(format!("{name}.sbav")))
            .map_err(anyhow::Error::from)
            .and_then(|b| manifest::header(&b))
        {
            bones.insert(name.clone(), count);
        }
    }
    bones.extend(meshes.iter().map(|(n, (b, _))| (n.clone(), *b)));
    let wardrobe = manifest::wardrobe(&rows, &bones, &|icon| {
        tmp.join(format!("hud.{icon}.png")).exists()
    });
    fs::write(tmp.join("wardrobe.txt"), wardrobe)?;
    let mut known: HashMap<String, String> = HashMap::new();
    for text in [
        base.as_ref()
            .and(base_manifest.as_deref())
            .unwrap_or_default(),
        &mesh_list,
    ] {
        for line in text.lines().filter(|l| l.starts_with("MESH ")) {
            if let Some(file) = line.split(' ').nth(1) {
                known.insert(file.to_string(), line.to_string());
            }
        }
    }
    let mesh_lines: Vec<String> = meshes
        .keys()
        .map(|n| {
            known
                .get(&format!("{n}.sbav"))
                .cloned()
                .unwrap_or_else(|| format!("MESH {n}.sbav - -"))
        })
        .collect();
    fs::write(
        tmp.join("pack.txt"),
        manifest::pack(&build.sidecar.paks, &sources, &mesh_lines)?,
    )?;

    // Checks before the pack replaces the installed one: every drawn section has its base texture (else it is
    // listed), meshes over 256 bones are listed, and nothing but meshes, PNGs and text is there.
    let mut checks = Checks {
        meshes: meshes.len(),
        baked,
        over_256_bones: meshes
            .iter()
            .filter(|(_, (b, _))| *b > manifest::LISTED_BONES)
            .map(|(n, _)| n.clone())
            .collect(),
        meshes_changed_from_base: changed,
        wardrobe_items: rows.len(),
        ..Checks::default()
    };
    for (name, (_, sections)) in &meshes {
        let untextured = manifest::untextured(tmp, sections);
        if let Some(base) = base.as_ref().filter(|_| base_has(&format!("{name}.sbav"))) {
            let before = manifest::untextured(base, sections);
            if !untextured.iter().all(|s| before.contains(s)) {
                bail!("{name} lost a texture");
            }
        }
        if !untextured.is_empty() {
            checks.untextured_sections.insert(name.clone(), untextured);
        }
    }
    for entry in fs::read_dir(tmp)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !entry.file_type()?.is_file()
            || ![".sbav", ".png", ".txt"].iter().any(|e| name.ends_with(e))
        {
            bail!("unexpected file in the pack: {name}");
        }
        checks.files += 1;
        checks.bytes += entry.metadata()?.len();
    }
    checks.warnings = warnings;

    // The swap: no cancel from here on.
    check()?;
    guard()?;
    say(Step::Install, 0.95, "");
    let old = avatar.with_file_name("avatar.old");
    let checks_tmp = checks_file.with_file_name("avatar.checks.json.tmp");
    fs::write(&checks_tmp, serde_json::to_string_pretty(&checks)? + "\n")?;
    let previous = avatar.exists();
    if previous {
        fs::rename(avatar, &old)?;
    }
    if let Err(error) = fs::rename(tmp, avatar) {
        if old.exists() {
            let _ = fs::rename(&old, avatar);
        }
        let _ = fs::remove_file(&checks_tmp);
        return Err(error.into());
    }
    // The new pack is in place: what follows only logs.
    if let Err(error) = fs::rename(&checks_tmp, &checks_file) {
        log::line(&format!(
            "pack: {} not written: {error}",
            checks_file.display()
        ));
    }
    if previous && let Err(error) = fs::remove_dir_all(&old) {
        log::line(&format!(
            "pack: the previous pack {} was not deleted: {error}",
            old.display()
        ));
    }
    say(Step::Install, 1.0, "");
    log::line(&format!(
        "pack: {} meshes, {} files, {} bytes, {} baked, {} warnings",
        checks.meshes,
        checks.files,
        checks.bytes,
        checks.baked,
        checks.warnings.len()
    ));
    Ok(Report {
        checks,
        owned: owned.map(|(ids, source)| (ids.len(), source)),
        incremental: base.is_some(),
        meshes_exported: new.len(),
        missing,
    })
}

/// Section material name → the material's object path, from `meshes.txt`. Names collide across folders, so materials
/// and their textures are read by object path, but the pack has one texture per name: it keeps the core meshes'
/// material (EVE's default suit draws `MI_Basebody_V02_F2`, which `CH_P_EVE_ReferenceBody` names too), else the first
/// listed, and warns about the other.
fn materials(list: &str, warnings: &mut Vec<String>) -> HashMap<String, String> {
    let core: HashSet<String> = CORE_MESHES
        .iter()
        .map(|m| format!("{}.sbav", last(m)))
        .collect();
    let mut lines: Vec<&str> = list.lines().collect();
    // Stable: the core meshes' lines first, each group in its listed order.
    lines.sort_by_key(|l| !l.split(' ').nth(1).is_some_and(|f| core.contains(f)));
    let mut materials: HashMap<String, String> = HashMap::new();
    for line in lines {
        let Some((name, path)) = line
            .strip_prefix("MATERIAL ")
            .and_then(|rest| rest.split_once(' '))
            .and_then(|(_, rest)| rest.rsplit_once(' '))
        else {
            continue;
        };
        let first = materials
            .entry(name.to_string())
            .or_insert_with(|| path.to_string());
        let warning = format!("material name {name}: {path} and {first} share one pack texture");
        if first != path && !warnings.contains(&warning) {
            warnings.push(warning);
        }
    }
    materials
}

/// Bake workers: one per 512 MiB of free memory, at most one per CPU.
fn workers() -> usize {
    let cpus = std::thread::available_parallelism().map_or(1, |n| n.get());
    let memory = platform::available_memory().map_or(1, |m| (m / WORKER_MEMORY) as usize);
    memory.clamp(1, cpus)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pack::fixture::{Setup, extract};
    use std::sync::Mutex;

    type Calls = Vec<(String, Vec<String>)>;

    /// A build over the synthetic game; `cancel_at` raises the cancel flag when that mode runs.
    fn go(
        setup: &Setup,
        build: &Build,
        cancel_at: Option<&str>,
        guard: Guard,
    ) -> (Result<Report>, Calls) {
        let calls = Mutex::new(Vec::new());
        let cancel = AtomicBool::new(false);
        let result = {
            let mut fake = extract(&setup.game, &build.work, &calls);
            let mut stand_in = |mode: &str, paths: &[String], line: &mut dyn FnMut(&str)| {
                if Some(mode) == cancel_at {
                    cancel.store(true, Ordering::Relaxed);
                }
                fake(mode, paths, line)
            };
            run_with(build, guard, &cancel, &|_| {}, &mut stand_in)
        };
        (result, calls.into_inner().unwrap())
    }

    fn snapshot(dir: &Path) -> BTreeMap<String, Vec<u8>> {
        fs::read_dir(dir)
            .unwrap()
            .flatten()
            .map(|e| {
                (
                    e.file_name().to_string_lossy().into_owned(),
                    fs::read(e.path()).unwrap(),
                )
            })
            .collect()
    }

    /// The pack against the private `pack.py`'s on the same synthetic game (digests from one reference run): every
    /// file name, image size and text file, and on Linux every pixel. Elsewhere libm's `pow`, `sin` and `hypot` may
    /// round a last bit differently, which can move a truncated byte by one.
    fn parity(files: &BTreeMap<String, Vec<u8>>) {
        let (mut shape, mut pixels) = (String::new(), String::new());
        for (name, bytes) in files.iter().filter(|(n, _)| *n != "pack.txt") {
            if name.ends_with(".png") {
                let image = image::load_from_memory(bytes).unwrap().to_rgba8();
                shape += &format!("{name} {} {}\n", image.width(), image.height());
                pixels += &format!("{name} {}\n", crate::safety::sha256_hex(image.as_raw()));
            } else {
                shape += &format!("{name} {}\n", crate::safety::sha256_hex(bytes));
            }
        }
        let digest = |text: &str| crate::safety::sha256_hex(text.as_bytes());
        assert_eq!(
            digest(&shape),
            "a23b89f711bfb62c46efafd706eb075279be79e4ea45c67c8291fb92a6f2993a",
            "{shape}"
        );
        if cfg!(target_os = "linux") {
            assert_eq!(
                digest(&pixels),
                "3925daa802e52de6e8b72f4a04efbd9ea2f95324b4975ce49134733b44cb9ef9",
                "{pixels}"
            );
        } else if digest(&pixels)
            != "3925daa802e52de6e8b72f4a04efbd9ea2f95324b4975ce49134733b44cb9ef9"
        {
            eprintln!("note: pixels differ from pack.py's by libm rounding");
        }
    }

    /// Minecraft's own readers (`Wardrobe.catalog`, `Wardrobe.manifest`) accept the pack's `wardrobe.txt` and
    /// `pack.txt`. Needs Java (`JAVA_HOME` or `java`) and the mod's compiled classes (`tools/check.py` builds them
    /// first); without them it says so and passes.
    fn java_reads(avatar: &Path, items: usize) {
        let classes =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../minecraft/build/classes/java/main");
        let java = std::env::var_os("JAVA_HOME").map_or_else(
            || PathBuf::from("java"),
            |h| PathBuf::from(h).join("bin/java"),
        );
        if cfg!(windows) || !classes.join("org/stellarbridge/Wardrobe.class").is_file() {
            eprintln!(
                "SKIP the Java readers: no compiled classes at {}",
                classes.display()
            );
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let source = dir.path().join("ReadPack.java");
        fs::write(
            &source,
            r#"import java.lang.reflect.Method;
import java.nio.file.*;
public class ReadPack {
    public static void main(String[] args) throws Exception {
        Class<?> wardrobe = Class.forName("org.stellarbridge.Wardrobe");
        Method catalog = wardrobe.getDeclaredMethod("catalog", String.class), manifest = wardrobe.getDeclaredMethod("manifest", String.class);
        catalog.setAccessible(true);
        manifest.setAccessible(true);
        Object items = catalog.invoke(null, Files.readString(Path.of(args[0], "wardrobe.txt")));
        Object pack = manifest.invoke(null, Files.readString(Path.of(args[0], "pack.txt")));
        System.out.println(items == null || pack == null ? "REFUSED" : "ITEMS " + ((java.util.Map<?, ?>) items).size());
    }
}
"#,
        )
        .unwrap();
        let output = match std::process::Command::new(&java)
            .arg("-cp")
            .arg(&classes)
            .arg(&source)
            .arg(avatar)
            .output()
        {
            Ok(output) => output,
            Err(error) => {
                eprintln!(
                    "SKIP the Java readers: {} not runnable: {error}",
                    java.display()
                );
                return;
            }
        };
        let stderr = String::from_utf8_lossy(&output.stderr);
        if stderr.contains("UnsupportedClassVersionError") {
            eprintln!(
                "SKIP the Java readers: {} is older than the mod's Java 21",
                java.display()
            );
            return;
        }
        let stdout = String::from_utf8_lossy(&output.stdout);
        assert_eq!(
            stdout.trim(),
            format!("ITEMS {items}"),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    fn exported(calls: &Calls) -> Vec<String> {
        calls
            .iter()
            .filter(|(m, _)| m == "export")
            .flat_map(|(_, p)| p.clone())
            .collect()
    }

    #[test]
    fn full_build_then_incremental_then_new_game_files() {
        let setup = Setup::new();
        let build = setup.build(true);
        let (report, calls) = go(&setup, &build, None, &|| Ok(()));
        let report = report.unwrap();
        let avatar = build.home.join("avatar");
        assert!(!report.incremental);
        assert_eq!(report.meshes_exported, 10);
        assert!(
            exported(&calls)
                .iter()
                .any(|p| p.ends_with("T_A_AnimTrail_01")),
            "the core step ran"
        );
        assert_eq!(detect::pack(&avatar), detect::Pack::Fresh);
        let checks = &report.checks;
        assert_eq!(checks.meshes, 10);
        assert_eq!(checks.over_256_bones, ["CH_P_EVE_77", "CH_P_EVE_77_NH"]);
        assert_eq!(
            checks.untextured_sections,
            BTreeMap::from([
                (
                    "ACC_EAR_19M".to_string(),
                    vec!["MI_ACC_EAR_19M".to_string()]
                ),
                ("CH_P_EVE_77".to_string(), vec!["M_Broken".to_string()]),
            ])
        );
        let warned = |text: &str| checks.warnings.iter().any(|w| w.contains(text));
        assert!(
            warned("material M_Broken skipped: M_Broken: no exported material"),
            "{:?}",
            checks.warnings
        );
        assert!(warned("material MI_ACC_EAR_19M skipped"));
        assert!(warned(
            "material name M_Suit: /Game/Art/Mat2/M_Suit.M_Suit and /Game/Art/Mat/M_Suit.M_Suit share one pack texture"
        ));
        assert!(
            warned("icon Ear_Icon_19: not exported")
                && warned("icon icon_equipment_color: not exported")
        );
        assert!(warned(
            "icon name NanoSuit_Icon_77: SB/Content/Art/UI/Texture/Other/NanoSuit_Icon_77 drawn as"
        ));
        assert_eq!(report.missing.len(), 3, "{:?}", report.missing);
        let files = snapshot(&avatar);
        assert!(
            !files.keys().any(|f| f.starts_with("MI_ACC_EAR_19M.")),
            "a failed bake leaves nothing"
        );
        assert!(
            files.contains_key("MI_ACC_EAR_19M_Emissive.png")
                && files.contains_key("M_Film.overlay.png")
        );
        assert_eq!(files.len(), checks.files);
        assert!(
            !build.work.exists()
                && !build.home.join("avatar.tmp").exists()
                && !build.home.join("avatar.old").exists()
        );
        let saved: serde_json::Value =
            serde_json::from_slice(&fs::read(build.home.join("avatar.checks.json")).unwrap())
                .unwrap();
        assert_eq!(saved, serde_json::to_value(checks).unwrap());
        parity(&files);
        java_reads(&avatar, 9);

        // Same game files: the installed pack is the base; only the meshes with untextured sections are exported
        // again, and the result is the same pack.
        let (again, calls) = go(&setup, &build, None, &|| Ok(()));
        let again = again.unwrap();
        assert!(again.incremental);
        assert_eq!(again.meshes_exported, 2);
        let exported = exported(&calls);
        assert!(
            !exported.iter().any(|p| p.ends_with("T_A_AnimTrail_01")),
            "no core step"
        );
        assert!(
            exported.iter().any(|p| p.ends_with("/CH_P_EVE_77"))
                && exported.iter().any(|p| p.ends_with("/ACC_EAR_19M"))
        );
        assert_eq!(snapshot(&avatar), files);

        // Other game files: a full build again.
        fs::write(setup.paks.join("~mods/New_P.pak"), "new").unwrap();
        let (third, _) = go(&setup, &build, None, &|| Ok(()));
        let third = third.unwrap();
        assert!(!third.incremental && third.meshes_exported == 10);
        assert_eq!(detect::pack(&avatar), detect::Pack::Fresh);
    }

    #[test]
    fn cancel_and_refusals_leave_the_installed_pack_unchanged() {
        let setup = Setup::new();
        let build = setup.build(true);
        go(&setup, &build, None, &|| Ok(())).0.unwrap();
        let avatar = build.home.join("avatar");
        let before = snapshot(&avatar);
        fs::write(setup.paks.join("~mods/New_P.pak"), "new").unwrap();
        for mode in ["json", "avatar", "export"] {
            let (result, _) = go(&setup, &build, Some(mode), &|| Ok(()));
            assert!(result.unwrap_err().is::<Cancelled>(), "{mode}");
            assert_eq!(snapshot(&avatar), before, "{mode}");
            assert!(
                !build.home.join("avatar.tmp").exists() && !build.work.exists(),
                "{mode}"
            );
        }
        let (result, calls) = go(&setup, &build, None, &|| bail!("Stellar Blade is running"));
        assert!(result.is_err() && calls.is_empty());
        assert_eq!(snapshot(&avatar), before);
        // A usmap of another game build: EveExtract cannot read the item table, and the build names that.
        fs::remove_file(setup.game.join("json/ItemTable.uasset.json")).unwrap();
        let (result, _) = go(&setup, &build, None, &|| Ok(()));
        let error = result.unwrap_err();
        assert_eq!(
            error.downcast_ref::<crate::Refusal>(),
            Some(&crate::Refusal::new(crate::Kind::UsmapRejected, "24463856"))
        );
        assert_eq!(snapshot(&avatar), before);
    }

    #[test]
    fn same_named_materials_keep_the_core_meshes_one() {
        let list = "MESH CH_P_EVE_ReferenceBody.sbav SB/Ref pak\n\
            MATERIAL CH_P_EVE_ReferenceBody.sbav Body /Game/Ref/M_Body.M_Body\n\
            MESH CH_P_EVE_52_TypeB.sbav SB/Suit pak\n\
            MATERIAL CH_P_EVE_52_TypeB.sbav Body /Game/Suit/M_Body.M_Body\n\
            MATERIAL CH_P_EVE_52_TypeB.sbav Skin /Game/Suit/M_Skin.M_Skin\n";
        let mut warnings = Vec::new();
        let found = materials(list, &mut warnings);
        assert_eq!(found["Body"], "/Game/Suit/M_Body.M_Body");
        assert_eq!(found["Skin"], "/Game/Suit/M_Skin.M_Skin");
        assert_eq!(
            warnings,
            [
                "material name Body: /Game/Ref/M_Body.M_Body and /Game/Suit/M_Body.M_Body share one pack texture"
            ]
        );
    }

    #[test]
    fn a_swap_cut_short_is_undone_first() {
        let setup = Setup::new();
        let build = setup.build(true);
        let old = build.home.join("avatar.old");
        fs::create_dir_all(&old).unwrap();
        fs::write(old.join("wardrobe.txt"), "SBAVWARDROBE 1\nEND 0\n").unwrap();
        let (result, _) = go(&setup, &build, Some("json"), &|| Ok(()));
        assert!(result.is_err());
        assert_eq!(
            fs::read_to_string(build.home.join("avatar/wardrobe.txt")).unwrap(),
            "SBAVWARDROBE 1\nEND 0\n"
        );
        assert!(!old.exists());
    }

    #[test]
    fn owned_build_takes_the_adapter_list() {
        let setup = Setup::new();
        let build = setup.build(false);
        assert!(
            go(&setup, &build, None, &|| Ok(())).0.is_err(),
            "no list and no save"
        );
        let list = build.home.join("bridge/eve-wardrobe.txt");
        fs::create_dir_all(list.parent().unwrap()).unwrap();
        let item = "ITEM 2000077 4 0000000100000002000000030000000a 1 0 5";
        let zeros = "0".repeat(32);
        fs::write(&list, format!("SBMC_WARDROBE 1\nSESSION {zeros}\nSEQ 7\nTIME 1\nRESULT listed\nCOUNT 1\n{item}\nEND 7\n")).unwrap();
        let report = go(&setup, &build, None, &|| Ok(())).0.unwrap();
        assert!(matches!(report.owned, Some((1, owned::Source::List(_)))));
        let meshes: Vec<String> = snapshot(&build.home.join("avatar"))
            .into_keys()
            .filter_map(|f| f.strip_suffix(".sbav").map(String::from))
            .collect();
        let expected = [
            "ACC_EAR_19M",
            "CH_NPC_Drone",
            "CH_NPC_Drone_Bear",
            "CH_P_EVE_52_TypeB",
            "CH_P_EVE_77",
            "CH_P_EVE_77_NH",
            "CH_P_EVE_Face_Jali3",
            "CH_W_Sword_01",
            "EVE_HR_01",
        ];
        assert_eq!(meshes, expected);
        let wardrobe = fs::read_to_string(build.home.join("avatar/wardrobe.txt")).unwrap();
        assert!(
            wardrobe.contains("\tCH_P_EVE_78\t"),
            "the catalog still lists every item"
        );
    }
}
