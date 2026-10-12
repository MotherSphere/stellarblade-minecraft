//! The operations end to end on a fake home: a Steam library with Stellar Blade, UE4SS, other loaders, a Proton prefix
//! and saves, and a Prism with a dedicated instance created by the installer. Detection builds the target; nothing
//! outside the temporary folder is read or written. On Windows the same runs without the prefix, with the saves under
//! the profile and the bridge path written as Windows opens it.

use sbmc_installer::detect;
use sbmc_installer::ledger::{self, Status};
use sbmc_installer::ops::{self, Env, Game, Target};
use sbmc_installer::payload::Payload;
use sbmc_installer::platform::{self, SteamKind};
use sbmc_installer::{Kind, Ownership, Refusal, adapter, deploy, launcher, mods, pins, safety};
use std::cell::Cell;
use std::collections::BTreeMap;
use std::fs;
use std::hash::Hash;
use std::path::{Path, PathBuf};
use std::rc::Rc;

fn write(path: &Path, data: impl AsRef<[u8]>) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, data).unwrap();
}

struct World {
    _root: tempfile::TempDir,
    home: PathBuf,
    target: Target,
    data: PathBuf,
    pin: String,
    running: Rc<Cell<bool>>,
    guard: Box<dyn Fn() -> anyhow::Result<()>>,
}

impl World {
    fn env(&self) -> Env<'_> {
        Env {
            data: self.data.clone(),
            closed: &*self.guard,
            pin: &self.pin,
            step: &|_| {},
        }
    }

    fn game(&self) -> &Game {
        self.target.game.as_ref().unwrap()
    }

    fn win64(&self) -> PathBuf {
        self.game().exe.parent().unwrap().to_path_buf()
    }

    /// Every file under the fake home except the installer's own data, by path: what "nothing changed" means.
    fn files(&self) -> BTreeMap<String, String> {
        let mut all = safety::tree(&self.home).unwrap();
        all.retain(|path, _| !path.starts_with("data/"));
        all
    }
}

fn world() -> World {
    let root = tempfile::tempdir().unwrap();
    let home = platform::canonicalize(root.path()).unwrap();
    let steam = home.join(".local/share/Steam");
    let library = home.join("Games/SteamLibrary");
    write(
        &steam.join("steamapps/libraryfolders.vdf"),
        format!(
            "\"libraryfolders\" {{ \"0\" {{ \"path\" \"{}\" }} \"1\" {{ \"path\" \"{}\" }} }}",
            // Steam escapes the backslashes of Windows paths.
            steam.display().to_string().replace('\\', "\\\\"),
            library.display().to_string().replace('\\', "\\\\")
        ),
    );
    write(
        &library.join("steamapps/appmanifest_3489700.acf"),
        "\"AppState\" { \"installdir\" \"StellarBlade\" \"buildid\" \"24463856\" \"StateFlags\" \"4\" }",
    );
    let win64 = library.join("steamapps/common/StellarBlade/SB/Binaries/Win64");
    write(&win64.join(adapter::EXE), "test executable");
    write(&win64.join("dwmapi.dll"), "UE4SS loader");
    write(&win64.join("dinput8.dll"), "Xion loader");
    write(&win64.join("ue4ss/UE4SS.dll"), "UE4SS");
    write(&win64.join("ue4ss/UE4SS-settings.ini"), "[General]\n");
    write(&win64.join("ue4ss/Mods/mods.txt"), "OtherMod : 1\n");
    write(&win64.join("ue4ss/Mods/OtherMod/Scripts/main.lua"), "other");
    let prefix = library.join("steamapps/compatdata/3489700/pfx");
    write(&prefix.join("user.reg"), "WINE REGISTRY Version 2\n");
    let saves = platform::save_dirs(Some(&prefix), &home).remove(0);
    write(
        &saves.join("76561190000000000/StellarBladeSave00.sav"),
        "save",
    );
    write(
        &platform::prism_roots(&home)[0].0.join("prismlauncher.cfg"),
        "InstanceDir=instances\n",
    );

    // Windows finds Steam through the registry, which is not faked here.
    let steams = detect::steam_at(vec![(steam.clone(), SteamKind::Native)]);
    let found = detect::game(&steams, &home).unwrap();
    assert_eq!(found.prefix.is_some(), platform::PROTON);
    assert!(found.build_supported());
    let ue4ss = detect::ue4ss(&found.win64).unwrap();
    let prism = &launcher::prism(&home)[0];
    let instance =
        launcher::create_instance(prism, launcher::INSTANCE_NAME, &[], &|| Ok(())).unwrap();
    write(&instance.game.join("options.txt"), "fov:0.5\n");
    write(&instance.mods().join("sodium.jar"), "sodium");
    let target = Target {
        game: Some(Game {
            saves: found.saves.clone(),
            exe: found.exe.clone(),
            ue4ss: ue4ss.dir,
            mods: ue4ss.mods.unwrap(),
            prefix: found.prefix.clone(),
        }),
        minecraft_mods: instance.mods(),
        bridge: detect::bridge_home(&home, false).join("bridge"),
    };
    let running = Rc::new(Cell::new(false));
    World {
        _root: root,
        data: home.join("data/Colony/Stellar Bridge"),
        pin: safety::sha256_hex(b"test executable"),
        home,
        target,
        guard: Box::new({
            let running = running.clone();
            move || {
                if running.get() {
                    anyhow::bail!("Close Stellar Blade first")
                }
                Ok(())
            }
        }),
        running,
    }
}

fn payload(version: u32) -> Payload {
    Payload {
        release: format!("0.{version}.0+test"),
        lua_revision: Some(50 + version),
        lua: BTreeMap::from([
            (
                "Scripts/main.lua".into(),
                format!("print('Loaded revision {}; v{version}')\n", 50 + version).into_bytes(),
            ),
            ("Scripts/bridge.lua".into(), b"return {}\n".to_vec()),
            ("enabled.txt".into(), Vec::new()),
        ]),
        dll: [adapter::test_dll(), version.to_le_bytes().to_vec()].concat(),
        jar: (
            "stellar-bridge-0.1.0.jar".into(),
            format!("jar v{version}").into_bytes(),
        ),
        fabric_api: b"fabric api".to_vec(),
        licenses: BTreeMap::new(),
    }
}

fn all_current(world: &World, payload: &Payload) -> bool {
    let pieces = ops::verify(&world.env(), &world.target, payload).unwrap();
    assert_eq!(pieces.len(), 3);
    pieces.iter().all(|p| p.current)
}

fn state(world: &World) -> ledger::State {
    ledger::load(&world.data.join(ledger::STATE)).unwrap()
}

#[test]
fn install_update_rollback_and_uninstall() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    let saves = w.game().saves.clone().unwrap();
    let saves_before = safety::tree(&saves).unwrap();

    let installed = ops::install(&w.env(), &w.target, &v1).unwrap();
    assert_eq!(
        (installed.status, installed.release.as_str()),
        (Status::Installed, "0.1.0+test")
    );
    assert!(all_current(&w, &v1));
    assert_eq!(
        fs::read(w.target.minecraft_mods.join(pins::FABRIC_API_FILE)).unwrap(),
        b"fabric api"
    );
    #[cfg(unix)]
    assert_eq!(
        std::os::unix::fs::PermissionsExt::mode(
            &fs::metadata(&w.target.bridge).unwrap().permissions()
        ) & 0o777,
        0o700
    );
    let game_path = platform::game_path(&w.target.bridge).unwrap();
    if platform::PROTON {
        assert_eq!(game_path, format!("Z:{}", w.target.bridge.display()));
    }
    assert_eq!(
        fs::read_to_string(w.win64().join(adapter::CONFIG)).unwrap(),
        game_path + "\n"
    );
    let backup = installed.save_backup.clone().unwrap();
    assert_eq!(safety::tree(&backup).unwrap(), saves_before);
    assert_eq!(installed.previous, None);

    // The same release again changes nothing, not even the previous set.
    let files = w.files();
    assert_eq!(
        ops::install(&w.env(), &w.target, &v1).unwrap().previous,
        None
    );
    assert_eq!(w.files(), files);
    assert_eq!(
        ops::plan(&w.env(), &w.target, &v1).unwrap(),
        ops::Plan::default()
    );
    let plan = ops::plan(&w.env(), &w.target, &payload(2)).unwrap();
    assert_eq!(w.files(), files, "a plan writes nothing");
    assert!(plan.writes.contains(&w.win64().join(adapter::DLL)));
    assert!(
        plan.replaced
            .contains(&w.game().mods.join(deploy::MOD_NAME))
    );
    assert!(
        plan.backups
            .iter()
            .any(|b| b.ends_with("previous/0.1.0+test"))
    );
    assert!(
        !plan.backups.iter().any(|b| b.ends_with("save-backups")),
        "one save backup only"
    );

    let updated = ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    let previous = updated.previous.clone().unwrap();
    assert!(previous.ends_with("previous/0.1.0+test"));
    let kept = Payload::from_previous(&previous).unwrap();
    assert_eq!(
        (&kept.lua, &kept.dll, &kept.jar),
        (&v1.lua, &v1.dll, &v1.jar)
    );
    assert_eq!(
        updated.save_backup,
        Some(backup.clone()),
        "one save backup, at the first install"
    );

    let rolled = ops::rollback(&w.env(), &w.target).unwrap();
    assert_eq!(rolled.release, "0.1.0+test");
    assert!(all_current(&w, &v1));
    assert!(rolled.previous.unwrap().ends_with("previous/0.2.0+test"));

    let refusals = ops::uninstall(&w.env(), &w.target, true).unwrap();
    assert_eq!(refusals, Vec::new());
    assert_eq!(state(&w).status, Status::Removed);
    let mut names: Vec<_> = fs::read_dir(&w.target.minecraft_mods)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    assert_eq!(names, vec!["sodium.jar"]);
    assert!(!w.game().mods.join(deploy::MOD_NAME).exists());
    assert!(!w.win64().join(adapter::DLL).exists());
    assert_eq!(
        fs::read(w.win64().join("dinput8.dll")).unwrap(),
        b"Xion loader"
    );
    assert!(backup.is_dir(), "save backups stay");
    assert!(
        w.target
            .minecraft_mods
            .parent()
            .unwrap()
            .join("options.txt")
            .exists(),
        "the instance stays"
    );
    assert_eq!(safety::tree(&saves).unwrap(), saves_before);
}

#[test]
fn refusals_happen_before_any_write() {
    let w = world();
    let v1 = payload(1);
    let untouched = |w: &World, files: &BTreeMap<String, String>| {
        assert_eq!(&w.files(), files);
        assert!(!w.data.join(ledger::STATE).exists());
    };
    let files = w.files();

    w.running.set(true);
    let error = ops::install(&w.env(), &w.target, &v1).unwrap_err();
    assert!(
        error.to_string().contains("Close Stellar Blade"),
        "{error:#}"
    );
    untouched(&w, &files);
    w.running.set(false);

    let other = Env {
        pin: &"0".repeat(64),
        ..w.env()
    };
    let error = ops::install(&other, &w.target, &v1).unwrap_err();
    assert!(
        error
            .to_string()
            .contains("Unsupported Stellar Blade executable"),
        "{error:#}"
    );
    untouched(&w, &files);

    let foreign_jar = w.target.minecraft_mods.join("stellar-bridge-0.0.9.jar");
    fs::write(&foreign_jar, "by hand").unwrap();
    let files = w.files();
    assert!(ops::install(&w.env(), &w.target, &v1).is_err());
    untouched(&w, &files);
    fs::remove_file(&foreign_jar).unwrap();

    let foreign_dll = w.win64().join("XInput1_3.dll");
    fs::write(&foreign_dll, "UE4SS 2.x").unwrap();
    let files = w.files();
    let error = ops::install(&w.env(), &w.target, &v1).unwrap_err();
    assert_eq!(
        error.downcast_ref::<Refusal>(),
        Some(&Refusal::new(Kind::ForeignAdapter, foreign_dll.display())),
        "{error:#}"
    );
    untouched(&w, &files);
    fs::remove_file(&foreign_dll).unwrap();

    // A file added to an owned folder stops an update (an update replaces only changed or missing listed files),
    // and an uninstall removes everything else.
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let script = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/notes.lua");
    fs::write(&script, "edited by hand").unwrap();
    let files = w.files();
    let error = ops::install(&w.env(), &w.target, &payload(2)).unwrap_err();
    assert_eq!(
        error.downcast_ref::<Refusal>(),
        Some(&Refusal::new(
            Kind::ModifiedLua,
            w.game().mods.join(deploy::MOD_NAME).display()
        )),
        "{error:#}"
    );
    assert_eq!(w.files(), files);
    w.running.set(true);
    assert!(ops::uninstall(&w.env(), &w.target, false).is_err());
    assert_eq!(w.files(), files);
    w.running.set(false);
    let refusals = ops::uninstall(&w.env(), &w.target, false).unwrap();
    assert_eq!(refusals.len(), 1);
    assert_eq!(
        refusals[0],
        (
            ops::LUA,
            Refusal::new(
                Kind::ModifiedLua,
                w.game().mods.join(deploy::MOD_NAME).display()
            )
        )
    );
    assert_eq!(fs::read_to_string(&script).unwrap(), "edited by hand");
    assert!(!w.win64().join(adapter::DLL).exists());
    assert!(
        w.target.minecraft_mods.join(pins::FABRIC_API_FILE).exists(),
        "kept for other mods"
    );
    assert_eq!(
        state(&w).status,
        Status::Installed,
        "not removed while a piece remains"
    );
}

#[test]
fn repair_puts_back_only_listed_files() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let lua = w.game().mods.join(deploy::MOD_NAME);
    let jar = w.target.minecraft_mods.join(&v1.jar.0);
    fs::remove_file(lua.join("enabled.txt")).unwrap();
    fs::write(w.win64().join(adapter::DLL), "damaged").unwrap();
    fs::write(&jar, "damaged").unwrap();
    assert!(!all_current(&w, &v1));

    // Another release cannot repair: its files are not what the manifests record.
    let files = w.files();
    let error = ops::repair(&w.env(), &w.target, &payload(2)).unwrap_err();
    assert!(error.to_string().contains("run the update"), "{error:#}");
    assert_eq!(w.files(), files);
    // Nor can anything with an unlisted file: no file is written, the drifted ones included.
    fs::write(lua.join("Scripts/notes.lua"), "mine").unwrap();
    let files = w.files();
    assert!(ops::repair(&w.env(), &w.target, &v1).is_err());
    assert_eq!(w.files(), files);
    fs::remove_file(lua.join("Scripts/notes.lua")).unwrap();
    w.running.set(true);
    assert!(ops::repair(&w.env(), &w.target, &v1).is_err());
    w.running.set(false);

    let mut repaired = ops::repair(&w.env(), &w.target, &v1).unwrap();
    repaired.sort();
    let mut expected = vec![lua.join("enabled.txt"), w.win64().join(adapter::DLL), jar];
    expected.sort();
    assert_eq!(repaired, expected);
    assert!(all_current(&w, &v1));
    assert_eq!(
        ops::repair(&w.env(), &w.target, &v1).unwrap(),
        Vec::<PathBuf>::new()
    );
}

#[test]
fn a_failed_update_puts_the_previous_set_back() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let files = w.files();
    // The adapter step refuses this DLL after the JAR and the Lua mod were already replaced.
    let broken = Payload {
        dll: b"not a DLL".to_vec(),
        ..payload(2)
    };
    let error = ops::install(&w.env(), &w.target, &broken).unwrap_err();
    assert!(
        format!("{error:#}").contains("previous state was restored"),
        "{error:#}"
    );
    assert!(all_current(&w, &v1));
    assert_eq!(w.files(), files);
    assert_eq!(
        (state(&w).status, state(&w).release),
        (Status::Installed, "0.1.0+test".into())
    );

    // A game started midway also blocks the restore: the replaced set waits in previous/ and the state says so.
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() > 4 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &payload(2)).unwrap_err();
    assert!(
        format!("{error:#}").contains("could not be restored"),
        "{error:#}"
    );
    assert_eq!(state(&w).status, Status::InProgress);
    let previous = state(&w).previous.unwrap();
    assert_eq!(Payload::from_previous(&previous).unwrap().jar, v1.jar);
    // Once the game is closed, rolling back finishes the job.
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

#[test]
fn a_viewer_gets_the_minecraft_side_only() {
    let w = world();
    let viewer = Target {
        game: None,
        ..w.target.clone()
    };
    fs::write(
        w.target
            .minecraft_mods
            .join("fabric-api-0.116.18+1.21.1.jar"),
        "theirs",
    )
    .unwrap();
    let v1 = payload(1);
    ops::install(&w.env(), &viewer, &v1).unwrap();
    let pieces = ops::verify(&w.env(), &viewer, &v1).unwrap();
    assert_eq!(pieces.len(), 1);
    assert!(pieces[0].current);
    assert_eq!(
        mods::manifest(&viewer.minecraft_mods).unwrap().files.len(),
        1,
        "their Fabric API is kept"
    );
    assert!(!w.target.bridge.exists());
    assert!(!w.game().mods.join(deploy::MOD_NAME).exists());
    assert_eq!(state(&w).save_backup, None);
    assert_eq!(ops::uninstall(&w.env(), &viewer, true).unwrap(), Vec::new());
    assert!(
        w.target
            .minecraft_mods
            .join("fabric-api-0.116.18+1.21.1.jar")
            .exists()
    );
}

/// The Python tools' Lua mod and adapter are an owned set to the installer: it updates them, keeps them in previous/,
/// and after the uninstall the Python tools install again.
#[cfg(unix)]
#[test]
fn python_installs_are_updated_and_kept() {
    let w = world();
    let v1 = payload(1);
    let tools = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("tools");
    let lab = w.home.join("repo");
    write(
        &lab.join("tools/deploy.py"),
        fs::read(tools.join("deploy.py")).unwrap(),
    );
    for (relative, bytes) in &v1.lua {
        write(
            &lab.join("stellar/Mods")
                .join(deploy::MOD_NAME)
                .join(relative),
            bytes,
        );
    }
    fs::create_dir_all(&w.target.bridge).unwrap();
    fs::set_permissions(
        &w.target.bridge,
        std::os::unix::fs::PermissionsExt::from_mode(0o700),
    )
    .unwrap();
    let python = |command: &str| {
        let mut run = std::process::Command::new("python3");
        run.arg(lab.join("tools/deploy.py"))
            .args([command, "--mods-dir"])
            .arg(&w.game().mods);
        if command == "install" {
            run.arg("--bridge-dir").arg(&w.target.bridge);
        }
        run.stdout(std::process::Stdio::null())
            .status()
            .unwrap()
            .success()
    };
    assert!(python("install"));
    adapter::install(&w.game().exe, &v1.dll, &w.target.bridge, &w.pin, &|| Ok(())).unwrap();

    // A failed update puts the Python tools' set back as it was; the Rust manifest differs only in formatting.
    let without_manifest = |w: &World| {
        let mut files = w.files();
        files.retain(|path, _| !path.ends_with(deploy::MANIFEST));
        (files, deploy::manifest(&w.game().mods).unwrap())
    };
    let before = without_manifest(&w);
    let broken = Payload {
        dll: b"not a DLL".to_vec(),
        ..payload(2)
    };
    let error = ops::install(&w.env(), &w.target, &broken).unwrap_err();
    assert!(
        format!("{error:#}").contains("previous state was restored"),
        "{error:#}"
    );
    assert_eq!(without_manifest(&w), before);

    let state = ops::install(&w.env(), &w.target, &payload(2)).unwrap();
    let previous = state.previous.unwrap();
    assert!(previous.ends_with("previous/untracked"));
    assert_eq!(deploy::ownership(&previous).unwrap(), Ownership::Owned);
    let error = ops::rollback(&w.env(), &w.target).unwrap_err();
    assert!(error.to_string().contains("no bridge JAR"), "{error:#}");
    assert!(
        ops::uninstall(&w.env(), &w.target, true)
            .unwrap()
            .is_empty()
    );
    assert!(python("install"));
    assert!(python("remove"));
}

/// With `SBMC_PAYLOAD` set (as `tools/check.py` builds it when the JAR and DLL exist), the real pieces install,
/// verify and uninstall on the fake home.
#[test]
fn the_embedded_payload_installs_when_present() {
    let Some(embedded) = Payload::embedded().unwrap() else {
        return;
    };
    let w = world();
    ops::install(&w.env(), &w.target, &embedded).unwrap();
    assert!(all_current(&w, &embedded));
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    assert_eq!(fs::read(main).unwrap(), embedded.lua["Scripts/main.lua"]);
    assert!(
        ops::uninstall(&w.env(), &w.target, true)
            .unwrap()
            .is_empty()
    );
}

/// A bridge folder that is no longer private, or would be created behind a link, stops an update before the owned
/// pieces are removed: nothing is left half installed.
#[cfg(unix)]
#[test]
fn an_unusable_bridge_folder_is_refused_before_any_write() {
    use std::os::unix::fs::PermissionsExt;
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    fs::set_permissions(&w.target.bridge, fs::Permissions::from_mode(0o755)).unwrap();
    let files = w.files();
    let error = ops::install(&w.env(), &w.target, &v2).unwrap_err();
    assert!(error.to_string().contains("mode 0700"), "{error:#}");
    assert_eq!(w.files(), files);
    assert_eq!(state(&w).status, Status::Installed);

    let fresh = world();
    let home = fresh.target.bridge.parent().unwrap();
    let elsewhere = fresh.home.join("other disk");
    fs::create_dir_all(&elsewhere).unwrap();
    std::os::unix::fs::symlink(&elsewhere, home).unwrap();
    let files = fresh.files();
    let error = ops::install(&fresh.env(), &fresh.target, &v1).unwrap_err();
    assert!(error.to_string().contains("without symlinks"), "{error:#}");
    assert_eq!(fresh.files(), files);
    assert!(!fresh.data.join(ledger::STATE).exists());
}

/// Prism's mod updater replaces the Fabric API the installer added, and Prism's Disable renames the bridge JAR: the
/// install stays owned, repair never adds a second Fabric API, and uninstall removes what is ours.
#[test]
fn prism_mod_updates_and_disables_are_handled() {
    let w = world();
    let viewer = Target {
        game: None,
        ..w.target.clone()
    };
    let v1 = payload(1);
    ops::install(&w.env(), &viewer, &v1).unwrap();
    let mods_dir = &viewer.minecraft_mods;
    fs::remove_file(mods_dir.join(pins::FABRIC_API_FILE)).unwrap();
    fs::write(mods_dir.join("fabric-api-0.116.18+1.21.1.jar"), "newer").unwrap();
    fs::rename(
        mods_dir.join(&v1.jar.0),
        mods_dir.join(format!("{}.disabled", v1.jar.0)),
    )
    .unwrap();
    let pieces = ops::verify(&w.env(), &viewer, &v1).unwrap();
    assert_eq!(pieces[0].ownership, Ownership::Owned);
    assert_eq!(
        ops::repair(&w.env(), &viewer, &v1).unwrap(),
        Vec::<PathBuf>::new()
    );
    assert!(!mods_dir.join(pins::FABRIC_API_FILE).exists());
    assert!(ops::uninstall(&w.env(), &viewer, true).unwrap().is_empty());
    let mut names: Vec<_> = fs::read_dir(mods_dir)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    assert_eq!(names, vec!["fabric-api-0.116.18+1.21.1.jar", "sodium.jar"]);
    assert_eq!(state(&w).status, Status::Removed);
}

/// The user deleted the instance in Prism: uninstall still removes the game side and records the removal.
#[test]
fn uninstall_after_the_instance_was_deleted() {
    let w = world();
    ops::install(&w.env(), &w.target, &payload(1)).unwrap();
    fs::remove_dir_all(w.target.minecraft_mods.parent().unwrap().parent().unwrap()).unwrap();
    assert!(
        ops::uninstall(&w.env(), &w.target, false)
            .unwrap()
            .is_empty()
    );
    assert!(!w.game().mods.join(deploy::MOD_NAME).exists());
    assert!(!w.win64().join(adapter::DLL).exists());
    assert_eq!(state(&w).status, Status::Removed);
}

/// Installing into another instance while the recorded one still holds the bridge would leave it there, forgotten:
/// it is refused before any write, and allowed once the first is uninstalled.
#[test]
fn moving_to_another_instance_needs_an_uninstall_first() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let other_mods = w.home.join("instances/Other/minecraft/mods");
    fs::create_dir_all(&other_mods).unwrap();
    let other = Target {
        minecraft_mods: other_mods.clone(),
        ..w.target.clone()
    };
    let files = w.files();
    let error = ops::plan(&w.env(), &other, &v1).unwrap_err();
    assert!(
        error.to_string().contains("uninstall it there first"),
        "{error:#}"
    );
    assert!(ops::install(&w.env(), &other, &v1).is_err());
    assert_eq!(w.files(), files);
    // A viewer install would forget the game side the same way.
    let viewer = Target {
        game: None,
        ..w.target.clone()
    };
    assert!(ops::install(&w.env(), &viewer, &v1).is_err());
    assert_eq!(w.files(), files);

    assert!(
        ops::uninstall(&w.env(), &w.target, true)
            .unwrap()
            .is_empty()
    );
    ops::install(&w.env(), &other, &v1).unwrap();
    assert_eq!(mods::ownership(&other_mods).unwrap(), Ownership::Owned);
    assert_eq!(state(&w).target.unwrap().minecraft_mods, other_mods);
}

/// Something else changing a protected file while the installer writes fails the install: the check after writing
/// compares the protected files with the snapshot taken before.
#[test]
fn a_protected_file_changed_midway_fails_the_install() {
    let w = world();
    let options = w
        .target
        .minecraft_mods
        .parent()
        .unwrap()
        .join("options.txt");
    let calls = Cell::new(0);
    let meddles = || {
        calls.set(calls.get() + 1);
        if calls.get() == 3 {
            fs::write(&options, "fov:1.0\n").unwrap();
        }
        Ok(())
    };
    let env = Env {
        closed: &meddles,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &payload(1)).unwrap_err();
    assert!(
        format!("{error:#}").contains("Protected files changed"),
        "{error:#}"
    );
    assert_ne!(state(&w).status, Status::Installed);
}

/// A game started during a repair stops it before the next file.
#[test]
fn a_game_started_during_a_repair_stops_it() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let script = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/bridge.lua");
    let jar = w.target.minecraft_mods.join(&v1.jar.0);
    fs::write(&script, "damaged").unwrap();
    fs::write(&jar, "damaged").unwrap();
    // The checks, then the script, then the JAR (activating files come last).
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() >= 3 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    assert!(ops::repair(&env, &w.target, &v1).is_err());
    assert_eq!(fs::read(&script).unwrap(), v1.lua["Scripts/bridge.lua"]);
    assert_eq!(fs::read(&jar).unwrap(), b"damaged");
}

/// A game found running at the first removal stops an update before anything changed: the previous state is intact,
/// so the failure must not say it could not be restored, nor leave the record "in progress".
#[test]
fn an_update_stopped_before_any_change_keeps_the_record_installed() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let files = w.files();
    // The checks pass, then the game is found running at the first removal and stays running.
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() >= 2 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &payload(2)).unwrap_err();
    assert_eq!(w.files(), files, "nothing changed on disk");
    assert!(all_current(&w, &v1));
    assert!(
        !format!("{error:#}").contains("could not be restored"),
        "{error:#}"
    );
    assert_eq!(state(&w).status, Status::Installed);
}

/// A protected file that changes during an update (Minecraft's options.txt here; Steam Cloud on the saves alike) is
/// reported, but once the old files are all back the restore itself succeeded.
#[test]
fn a_protected_change_during_an_update_still_reports_the_restore() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let options = w
        .target
        .minecraft_mods
        .parent()
        .unwrap()
        .join("options.txt");
    let calls = Cell::new(0);
    let meddles = || {
        calls.set(calls.get() + 1);
        if calls.get() == 2 {
            fs::write(&options, "fov:1.0\n").unwrap();
        }
        Ok(())
    };
    let env = Env {
        closed: &meddles,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &payload(2)).unwrap_err();
    assert!(
        format!("{error:#}").contains("Protected files changed"),
        "{error:#}"
    );
    assert!(all_current(&w, &v1), "the previous set is fully back");
    assert!(
        !format!("{error:#}").contains("could not be restored"),
        "{error:#}"
    );
    assert_eq!(
        (state(&w).status, state(&w).release),
        (Status::Installed, "0.1.0+test".into())
    );
}

/// An update that stopped midway (InProgress, previous/<old> complete) and is then finished with Install again must
/// still roll back to the old release: the second run must not overwrite previous/<old> with the partial set.
#[test]
fn finishing_an_interrupted_update_keeps_the_rollback_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    // The game starts midway: the update fails after the JAR is written, and its restore is blocked too.
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() > 7 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    ops::install(&env, &w.target, &v2).unwrap_err();
    assert_eq!(state(&w).status, Status::InProgress);
    assert_eq!(
        Payload::from_previous(&state(&w).previous.unwrap())
            .unwrap()
            .jar,
        v1.jar
    );
    // "Install again to finish it".
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    let kept = Payload::from_previous(&state(&w).previous.unwrap()).unwrap();
    assert_eq!(
        (&kept.jar, &kept.lua, &kept.dll),
        (&v1.jar, &v1.lua, &v1.dll),
        "previous/ must still hold v1"
    );
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// Same, when the first run crashed (the process died) after the JAR was written.
#[test]
fn finishing_a_crashed_update_keeps_the_rollback_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::LuaMod {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v2);
        }))
        .is_err()
    );
    assert_eq!(state(&w).status, Status::InProgress);
    ops::install(&w.env(), &w.target, &v2).unwrap();
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// A failed update that was fully restored leaves the older rollback set alone.
#[test]
fn a_restored_failed_update_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(state(&w).previous.unwrap().ends_with("previous/0.1.0+test"));
    let broken = Payload {
        dll: b"not a DLL".to_vec(),
        ..payload(3)
    };
    let error = ops::install(&w.env(), &w.target, &broken).unwrap_err();
    assert!(
        format!("{error:#}").contains("previous state was restored"),
        "{error:#}"
    );
    assert!(all_current(&w, &v2));
    let previous = state(&w).previous.unwrap();
    assert!(
        previous.ends_with("previous/0.1.0+test"),
        "rollback set replaced: {}",
        previous.display()
    );
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// A run that died before it kept anything left the installed set complete: finishing it keeps that set, not the
/// older one kept by the update before.
#[test]
fn finishing_an_update_that_died_before_keeping_keeps_the_installed_set() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::KeepPrevious {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v3);
        }))
        .is_err()
    );
    assert_eq!(state(&w).status, Status::InProgress);
    ops::install(&w.env(), &w.target, &v3).unwrap();
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v2));
}

/// A run killed after the old pieces were removed and before the new ones were written leaves the recorded pieces
/// absent: repair must not call that "nothing to repair".
#[test]
fn repair_after_an_interrupted_update_is_not_nothing_to_repair() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    // Guard calls: the checks, the adapter, the Lua mod and the JAR removals; the process dies at the first write.
    let calls = Cell::new(0);
    let killed = || {
        calls.set(calls.get() + 1);
        if calls.get() == 5 {
            panic!("killed");
        }
        Ok(())
    };
    let env = Env {
        closed: &killed,
        ..w.env()
    };
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        ops::install(&env, &w.target, &v2)
    }));
    assert!(run.is_err(), "the run was killed");
    assert_eq!(state(&w).status, Status::InProgress);
    let pieces = ops::verify(&w.env(), &w.target, &v2).unwrap();
    assert!(
        pieces.iter().all(|p| p.ownership == Ownership::Absent),
        "{pieces:?}"
    );

    let plan = ops::repair_plan(&w.env(), &w.target, &v2);
    assert!(
        !matches!(&plan, Ok(plan) if plan.is_empty()),
        "the confirm modal would say nothing to repair"
    );
    let repaired = ops::repair(&w.env(), &w.target, &v2);
    assert!(
        !matches!(&repaired, Ok(paths) if paths.is_empty()),
        "an interrupted update reported as nothing to repair: {repaired:?}"
    );
}

/// A run killed between creating a temporary JAR and renaming it leaves `.<name>.part` behind; the next update must
/// still go through.
#[test]
fn a_stray_part_file_from_a_killed_run_does_not_fail_the_next_update() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let stray = w.target.minecraft_mods.join(format!(".{}.part", v2.jar.0));
    fs::write(&stray, "partial").unwrap();
    let updated = ops::install(&w.env(), &w.target, &v2);
    assert!(updated.is_ok(), "{:#}", updated.unwrap_err());
    assert!(all_current(&w, &v2));
}

/// A repair killed between creating a temporary Lua script and renaming it leaves `.<name>.part` inside the mod
/// folder; it must not make the mod "modified" for good.
#[test]
fn a_stray_part_file_from_a_killed_lua_repair_does_not_block_the_update() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let stray = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/.bridge.lua.part");
    fs::write(&stray, "partial").unwrap();
    let updated = ops::install(&w.env(), &w.target, &v2);
    assert!(updated.is_ok(), "{:#}", updated.unwrap_err());
    assert!(all_current(&w, &v2));
    // The kept set is the listed files only: a rollback does not install the stray.
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
    assert!(
        !w.game()
            .mods
            .join(deploy::MOD_NAME)
            .join("Scripts/.bridge.lua.part")
            .exists()
    );
}

/// A repair killed while writing the adapter DLL leaves `.xinput1_3.dll.part` beside the game; the next repair puts
/// the DLL back the first time.
#[test]
fn a_stray_part_file_from_a_killed_adapter_repair_does_not_fail_the_next_repair() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    fs::write(w.win64().join(adapter::DLL), "damaged").unwrap();
    fs::write(w.win64().join(format!(".{}.part", adapter::DLL)), "partial").unwrap();
    let repaired = ops::repair(&w.env(), &w.target, &v1);
    assert!(repaired.is_ok(), "{:#}", repaired.unwrap_err());
    assert!(all_current(&w, &v1));
}

/// A bridge JAR the user disabled in Prism stays disabled through an update: the new release replaces it under the
/// disabled name instead of turning it back on.
#[test]
fn an_update_keeps_a_disabled_bridge_jar_disabled() {
    let w = world();
    let v1 = payload(1);
    let mut v2 = payload(2);
    v2.jar.0 = "stellar-bridge-0.2.0.jar".into();
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let mods_dir = &w.target.minecraft_mods;
    fs::rename(
        mods_dir.join(&v1.jar.0),
        mods_dir.join(format!("{}{}", v1.jar.0, mods::DISABLED)),
    )
    .unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(
        !mods_dir.join(&v2.jar.0).exists(),
        "the update turned the disabled bridge JAR back on"
    );
    let disabled = mods_dir.join(format!("{}{}", v2.jar.0, mods::DISABLED));
    assert_eq!(fs::read(&disabled).unwrap(), v2.jar.1);
    assert!(all_current(&w, &v2));
}

/// A bridge JAR copied by hand under another name is still the bridge (same Fabric mod id): an install next to it
/// would leave two copies of the mod, which Fabric refuses to load, so it is refused before any write.
#[test]
fn a_renamed_bridge_jar_is_found_by_its_mod_id() {
    use std::io::Write;
    let w = world();
    let mods_dir = &w.target.minecraft_mods;
    let mut jar = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    jar.start_file(
        "fabric.mod.json",
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
    )
    .unwrap();
    jar.write_all(br#"{"schemaVersion": 1, "id": "stellar_bridge", "version": "0.2.0"}"#)
        .unwrap();
    write(
        &mods_dir.join("sbmc-bridge.jar"),
        jar.finish().unwrap().into_inner(),
    );
    let files = w.files();
    assert!(
        ops::install(&w.env(), &w.target, &payload(1)).is_err(),
        "installed a second stellar_bridge next to sbmc-bridge.jar"
    );
    assert_eq!(w.files(), files);
}

/// A Steam update changes the executable and the next release pins the new one: the adapter installed for the old pin
/// is still ours (its files match its manifest) and the update replaces it.
#[test]
fn an_adapter_installed_for_an_older_pin_is_updated() {
    let mut w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    fs::write(&w.game().exe, "test executable, next build").unwrap();
    w.pin = safety::sha256_hex(b"test executable, next build");

    assert_eq!(
        adapter::ownership(&w.win64()).unwrap(),
        Ownership::Owned,
        "the adapter's files match its manifest"
    );
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    let manifest = adapter::manifest(&w.win64()).unwrap();
    assert_eq!(manifest.executable, w.pin);
}

/// A file of ours changed by hand (here: the newer release's own bytes copied in) must not leave a newer installer
/// with no way forward: today Update refuses the Modified piece and Repair answers "run the update instead".
#[test]
fn a_hand_changed_piece_does_not_dead_end_a_newer_installer() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let lua = w.game().mods.join(deploy::MOD_NAME);
    fs::write(lua.join("Scripts/main.lua"), &v2.lua["Scripts/main.lua"]).unwrap();
    fs::write(w.win64().join(adapter::DLL), &v2.dll).unwrap();

    let update = ops::install(&w.env(), &w.target, &v2);
    let repair = ops::repair(&w.env(), &w.target, &v2);
    assert!(
        update.is_ok() || repair.is_ok(),
        "dead end:\nupdate: {:#}\nrepair: {:#}",
        update.unwrap_err(),
        repair.unwrap_err()
    );
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
}

/// The same dead end without any hand copy: a file of ours went missing (an antivirus quarantining the adapter DLL)
/// and the player downloads the next release before repairing.
#[test]
fn a_missing_piece_does_not_dead_end_a_newer_installer() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    fs::remove_file(w.win64().join(adapter::DLL)).unwrap();

    let update = ops::install(&w.env(), &w.target, &v2);
    let repair = ops::repair(&w.env(), &w.target, &v2);
    assert!(
        update.is_ok() || repair.is_ok(),
        "dead end:\nupdate: {:#}\nrepair: {:#}",
        update.unwrap_err(),
        repair.unwrap_err()
    );
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
}

/// Replacing a piece changed by hand is shown before consent and keeps the changed files, but Roll back never goes to
/// that kept set: it would reinstall half a piece.
#[test]
fn replacing_a_changed_piece_is_listed_kept_and_not_rolled_back_to() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let dll = w.win64().join(adapter::DLL);
    fs::remove_file(&dll).unwrap();
    let plan = ops::plan(&w.env(), &w.target, &v2).unwrap();
    assert_eq!(plan.changed, vec![dll.clone()]);
    assert!(plan.replaced.contains(&w.win64().join(adapter::MANIFEST)));
    assert!(!plan.replaced.contains(&dll), "nothing to keep");
    // Unlisted content is still refused, before any write.
    let notes = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/notes.lua");
    fs::write(&notes, "mine").unwrap();
    let files = w.files();
    assert!(ops::install(&w.env(), &w.target, &v2).is_err());
    assert_eq!(w.files(), files);
    fs::remove_file(&notes).unwrap();

    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    // The changed set stays on disk, but Roll back never offers it: there was no set kept before.
    assert!(
        w.data
            .join("changed/0.1.0+test")
            .join(adapter::MANIFEST)
            .exists()
    );
    assert_eq!(state(&w).previous, None);
    let error = ops::rollback(&w.env(), &w.target).unwrap_err();
    assert_eq!(
        error.downcast_ref::<Refusal>().map(|r| r.kind),
        Some(Kind::NoPrevious),
        "{error:#}"
    );
    assert!(all_current(&w, &v2), "the refused rollback changed nothing");
}

/// UE4SS moved its Mods folder (Win64/Mods to Win64/ue4ss/Mods): an install into the new one must not leave the
/// recorded Lua mod in the old one, where no later run looks; it removes it there too.
#[test]
fn a_moved_ue4ss_mods_folder_is_not_left_behind() {
    let w = world();
    let win64 = w.win64();
    write(&win64.join("UE4SS.dll"), "UE4SS 2.x");
    write(&win64.join("Mods/mods.txt"), "OtherMod : 1\n");
    let old = Target {
        game: Some(Game {
            ue4ss: win64.clone(),
            mods: win64.join("Mods"),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    let v1 = payload(1);
    ops::install(&w.env(), &old, &v1).unwrap();
    assert_eq!(
        deploy::ownership(&win64.join("Mods")).unwrap(),
        Ownership::Owned
    );
    // Either fix holds: the install refuses, or it also removes the Lua mod from the old Mods folder.
    let installed = ops::install(&w.env(), &w.target, &payload(2));
    assert!(
        installed.is_err() || deploy::ownership(&win64.join("Mods")).unwrap() == Ownership::Absent,
        "installed into ue4ss/Mods and left the owned Lua mod in Win64/Mods"
    );
}

/// The game moved to another folder while the old one stayed (a copied install): the adapter recorded beside the old
/// executable is removed by the install at the new place, and kept for Roll back.
#[test]
fn an_adapter_left_beside_a_moved_executable_is_removed() {
    let w = world();
    let old_win64 = w.home.join("Old/Win64");
    write(&old_win64.join(adapter::EXE), "test executable");
    let old = Target {
        game: Some(Game {
            exe: old_win64.join(adapter::EXE),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    ops::install(&w.env(), &old, &payload(1)).unwrap();
    assert_eq!(adapter::ownership(&old_win64).unwrap(), Ownership::Owned);
    // What the confirm modals list beside the pieces at the target.
    assert!(ops::moved_paths(&w.env(), &w.target).contains(&old_win64.join(adapter::DLL)));
    ops::install(&w.env(), &w.target, &payload(2)).unwrap();
    assert_eq!(adapter::ownership(&old_win64).unwrap(), Ownership::Absent);
    assert!(all_current(&w, &payload(2)));
    let kept = Payload::from_previous(&state(&w).previous.unwrap()).unwrap();
    assert_eq!(kept.dll, payload(1).dll);
}

/// An update over a file changed by hand keeps that changed set aside, but Roll back still goes to the complete set
/// kept before it: the update must not drop that one.
#[test]
fn an_update_over_a_changed_piece_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "edited").unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// A user moving to the newer UE4SS copied their mods over: both Mods folders hold our Lua mod. The update keeps one
/// of them, installs at the new place and removes the old copy, and Roll back still works.
#[test]
fn a_lua_mod_in_both_mods_folders_is_kept_once_and_removed_from_the_old_one() {
    let w = world();
    let win64 = w.win64();
    write(&win64.join("UE4SS.dll"), "UE4SS 2.x");
    write(&win64.join("Mods/mods.txt"), "OtherMod : 1\n");
    let old = Target {
        game: Some(Game {
            ue4ss: win64.clone(),
            mods: win64.join("Mods"),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &old, &v1).unwrap();
    safety::copy_tree(
        &win64.join("Mods").join(deploy::MOD_NAME),
        &w.game().mods.join(deploy::MOD_NAME),
    )
    .unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert_eq!(
        deploy::ownership(&win64.join("Mods")).unwrap(),
        Ownership::Absent
    );
    assert!(all_current(&w, &v2));
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// An update to a moved Mods folder that stops before it cleared the old one: the record still names the old place,
/// so Install again removes the Lua mod there.
#[test]
fn an_update_stopped_before_clearing_the_old_mods_folder_finds_it_again() {
    let w = world();
    let win64 = w.win64();
    write(&win64.join("UE4SS.dll"), "UE4SS 2.x");
    write(&win64.join("Mods/mods.txt"), "OtherMod : 1\n");
    let old = Target {
        game: Some(Game {
            ue4ss: win64.clone(),
            mods: win64.join("Mods"),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    ops::install(&w.env(), &old, &payload(1)).unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::KeepPrevious {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &payload(2));
        }))
        .is_err()
    );
    ops::install(&w.env(), &w.target, &payload(2)).unwrap();
    assert_eq!(
        deploy::ownership(&win64.join("Mods")).unwrap(),
        Ownership::Absent
    );
}

/// A same-release run (an Update over a hand-changed file) records `previous/<release on record>`; a later update that
/// dies before keeping must not take that stale set for the one it kept.
#[test]
fn finishing_an_update_after_a_same_release_run_keeps_the_installed_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "edited").unwrap();
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::KeepPrevious {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v2);
        }))
        .is_err()
    );
    ops::install(&w.env(), &w.target, &v2).unwrap();
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// Roll back after an interrupted update reinstalls the set that update kept; the set kept before it stays the next
/// Roll back, as it was before the update.
#[test]
fn rolling_back_an_interrupted_update_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::LuaMod {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v3);
        }))
        .is_err()
    );
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v2));
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// A Steam update changes the executable: the same release is installed again for it. That run's kept set is the
/// release still installed, so Roll back keeps going to the set kept before.
#[test]
fn reinstalling_the_same_release_keeps_the_older_rollback_set() {
    let mut w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    fs::write(&w.game().exe, "test executable, next build").unwrap();
    w.pin = safety::sha256_hex(b"test executable, next build");
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(state(&w).previous.unwrap().ends_with("previous/0.1.0+test"));
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
}

/// before it: the changed set it kept can never be installed again.
#[test]
fn finishing_an_interrupted_update_over_a_changed_piece_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "edited").unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::Remove {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v2);
        }))
        .is_err()
    );
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(all_current(&w, &v2));
    assert!(
        w.data.join("previous/0.1.0+test").is_dir(),
        "v1 set still on disk"
    );
    ops::rollback(&w.env(), &w.target).unwrap_or_else(|e| panic!("Roll back to v1 lost: {e:#}"));
    assert!(all_current(&w, &v1));
}

/// keep that partial set over the complete one Roll back goes to.
#[test]
fn finishing_an_interrupted_reinstall_keeps_the_rollback_set() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(
        ops::uninstall(&w.env(), &w.target, false)
            .unwrap()
            .is_empty()
    );
    let crash = |step: ops::Step| {
        if step == ops::Step::LuaMod {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    assert!(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = ops::install(&env, &w.target, &v3);
        }))
        .is_err()
    );
    ops::install(&w.env(), &w.target, &v3).unwrap();
    assert!(all_current(&w, &v3));
    ops::rollback(&w.env(), &w.target).unwrap_or_else(|e| {
        panic!(
            "Roll back lost: {e:#}; previous/ = {:?}",
            fs::read_dir(w.data.join("previous"))
                .unwrap()
                .map(|e| e.unwrap().file_name())
                .collect::<Vec<_>>()
        )
    });
    assert!(all_current(&w, &v1));
}

/// already back: it needs no restore from the changed set, which cannot be installed again.
#[test]
fn an_update_over_a_changed_piece_stopped_before_any_change_is_restored() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "edited").unwrap();
    let files = w.files();
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() >= 2 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &v2).unwrap_err();
    assert_eq!(w.files(), files, "nothing changed on disk");
    let message = format!("{error:#}");
    let status = state(&w).status;
    let rollback = ops::rollback(&w.env(), &w.target).map(|_| all_current(&w, &v1));
    assert!(
        !message.contains("could not be restored")
            && status == Status::Installed
            && matches!(rollback, Ok(true)),
        "message: {message}\nstatus: {status:?}\nrollback: {:?}",
        rollback.map_err(|e| format!("{e:#}"))
    );
}

/// already back: the Lua mod was never at the new place.
#[test]
fn an_update_to_a_moved_mods_folder_stopped_before_any_change_is_restored() {
    let w = world();
    let win64 = w.win64();
    write(&win64.join("UE4SS.dll"), "UE4SS 2.x");
    write(&win64.join("Mods/mods.txt"), "OtherMod : 1\n");
    let old = Target {
        game: Some(Game {
            ue4ss: win64.clone(),
            mods: win64.join("Mods"),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    ops::install(&w.env(), &old, &payload(1)).unwrap();
    let files = w.files();
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() >= 2 {
            anyhow::bail!("Stellar Blade started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    let error = ops::install(&env, &w.target, &payload(2)).unwrap_err();
    assert_eq!(w.files(), files, "nothing changed on disk");
    let message = format!("{error:#}");
    assert!(
        !message.contains("could not be restored") && state(&w).status == Status::Installed,
        "{message}\n{:?}",
        state(&w).status
    );
}

/// The files changed by hand that an update kept aside survive a later failed run and a Roll back.
#[test]
fn changed_files_kept_aside_survive_a_failed_run_and_a_rollback() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "my edit").unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let kept_aside = || {
        safety::tree(&w.data)
            .unwrap()
            .into_iter()
            .filter(|(_, sha)| *sha == safety::sha256_hex(b"my edit"))
            .count()
    };
    assert_eq!(kept_aside(), 1);
    let broken = Payload {
        dll: b"not a DLL".to_vec(),
        ..payload(3)
    };
    let error = ops::install(&w.env(), &w.target, &broken).unwrap_err();
    assert!(
        format!("{error:#}").contains("previous state was restored"),
        "{error:#}"
    );
    assert!(all_current(&w, &v2));
    assert_eq!(kept_aside(), 1, "deleted by a failed run");
    ops::rollback(&w.env(), &w.target).unwrap();
    assert!(all_current(&w, &v1));
    assert_eq!(kept_aside(), 1, "deleted by Roll back");
}

/// removes that adapter by its folder, without the executable.
#[test]
fn an_adapter_left_where_the_game_was_uninstalled_does_not_block_the_update() {
    let w = world();
    let old_win64 = w.home.join("Old/Win64");
    write(&old_win64.join(adapter::EXE), "test executable");
    let old = Target {
        game: Some(Game {
            exe: old_win64.join(adapter::EXE),
            ..w.game().clone()
        }),
        ..w.target.clone()
    };
    ops::install(&w.env(), &old, &payload(1)).unwrap();
    fs::remove_file(old_win64.join(adapter::EXE)).unwrap();
    let updated = ops::install(&w.env(), &w.target, &payload(2));
    assert!(updated.is_ok(), "{:#}", updated.unwrap_err());
    assert_eq!(adapter::ownership(&old_win64).unwrap(), Ownership::Absent);
    assert!(all_current(&w, &payload(2)));
}

/// A large modpack: the review page's plan must not open and parse every JAR again and again. Each JAR is read once,
/// about 0.3 s here in a release build and 1.2 s in a test build; reading them at every call took 6.8 s and 11.5 s.
#[test]
fn planning_beside_a_large_modpack_stays_fast() {
    use std::io::Write;
    let w = world();
    ops::install(&w.env(), &w.target, &payload(1)).unwrap();
    let mut jar = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    jar.start_file("fabric.mod.json", options).unwrap();
    jar.write_all(br#"{"schemaVersion": 1, "id": "somemod"}"#)
        .unwrap();
    for i in 0..3000 {
        jar.start_file(
            format!("assets/somemod/textures/block/texture_{i}.png"),
            options,
        )
        .unwrap();
        jar.write_all(b"x").unwrap();
    }
    let bytes = jar.finish().unwrap().into_inner();
    for n in 0..200 {
        write(
            &w.target.minecraft_mods.join(format!("mod-{n}.jar")),
            &bytes,
        );
    }
    let start = std::time::Instant::now();
    ops::plan(&w.env(), &w.target, &payload(2)).unwrap();
    assert!(
        start.elapsed() < std::time::Duration::from_secs(3),
        "Review page blocked {:?}",
        start.elapsed()
    );
}

/// A changed bridge JAR the user disabled in Prism is listed where it is, under its disabled name, and repaired there.
#[test]
fn a_changed_disabled_jar_is_listed_at_its_real_path() {
    let w = world();
    let v1 = payload(1);
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let mods_dir = &w.target.minecraft_mods;
    let disabled = mods_dir.join(format!("{}{}", v1.jar.0, mods::DISABLED));
    fs::rename(mods_dir.join(&v1.jar.0), &disabled).unwrap();
    fs::write(&disabled, "edited").unwrap();
    let plan = ops::plan(&w.env(), &w.target, &payload(2)).unwrap();
    assert_eq!(plan.changed, vec![disabled.clone()]);
    let pieces = ops::verify(&w.env(), &w.target, &payload(2)).unwrap();
    assert!(
        matches!(&pieces[0].ownership, Ownership::Modified(r) if r.subject == disabled.display().to_string()),
        "{:?}",
        pieces[0].ownership
    );
    // Repair puts it back where it is, still disabled.
    assert_eq!(
        ops::repair(&w.env(), &w.target, &v1).unwrap(),
        vec![disabled.clone()]
    );
    assert_eq!(fs::read(&disabled).unwrap(), v1.jar.1);
    assert!(!mods_dir.join(&v1.jar.0).exists());
}

/// Repair after an interrupted first install names no internal folder.
#[test]
fn repair_after_an_interrupted_first_install_names_no_internal_folder() {
    let w = world();
    let calls = Cell::new(0);
    let killed = || {
        calls.set(calls.get() + 1);
        if calls.get() == 3 {
            panic!("killed");
        }
        Ok(())
    };
    let env = Env {
        closed: &killed,
        ..w.env()
    };
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        ops::install(&env, &w.target, &payload(1))
    }));
    assert!(run.is_err());
    assert_eq!(state(&w).status, Status::InProgress);
    let error = ops::repair_plan(&w.env(), &w.target, &payload(1)).unwrap_err();
    sbmc_installer::i18n::set_lang(sbmc_installer::i18n::Lang::En);
    let text = sbmc_installer::i18n::error(&error);
    assert!(!text.contains("untracked"), "{text}");
}

/// The files changed by hand that an interrupted update kept stay in the data folder after an Uninstall and two
/// updates: no later run deletes them.
#[test]
fn changed_files_kept_by_an_interrupted_update_survive_an_uninstall() {
    let w = world();
    let (v1, v2, v3, v4) = (payload(1), payload(2), payload(3), payload(4));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "my edit").unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::Minecraft {
            panic!("power cut");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _ = ops::install(&env, &w.target, &v3);
    }));
    assert!(run.is_err());
    let kept_aside = || {
        safety::tree(&w.data)
            .unwrap()
            .into_iter()
            .filter(|(_, sha)| *sha == safety::sha256_hex(b"my edit"))
            .count()
    };
    assert_eq!(kept_aside(), 1, "kept by the interrupted run");
    assert!(
        ops::uninstall(&w.env(), &w.target, false)
            .unwrap()
            .is_empty()
    );
    ops::install(&w.env(), &w.target, &v3).unwrap();
    ops::install(&w.env(), &w.target, &v4).unwrap();
    assert_eq!(kept_aside(), 1, "the hand-changed main.lua was deleted");
}

/// An update over a file changed by hand, killed after its last write, is finished by Install: the changed set then
/// goes to `changed/`, where the next update does not delete it.
#[test]
fn changed_files_kept_by_an_update_killed_before_its_verify_survive() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "my edit").unwrap();
    let crash = |step: ops::Step| {
        if step == ops::Step::Verify {
            panic!("killed");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _ = ops::install(&env, &w.target, &v2);
    }));
    assert!(run.is_err());
    ops::install(&w.env(), &w.target, &v2).unwrap();
    ops::install(&w.env(), &w.target, &v3).unwrap();
    let kept_aside = safety::tree(&w.data)
        .unwrap()
        .into_iter()
        .filter(|(_, sha)| *sha == safety::sha256_hex(b"my edit"))
        .count();
    assert_eq!(kept_aside, 1, "the hand-changed main.lua was deleted");
}

/// An update over a file changed by hand that fails and cannot be restored leaves the changed set in `previous/`:
/// after an Uninstall, the next updates still do not delete it.
#[test]
fn changed_files_kept_by_an_unrestored_update_survive_an_uninstall() {
    let w = world();
    let (v1, v2, v3, v4) = (payload(1), payload(2), payload(3), payload(4));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let main = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    fs::write(&main, "my edit").unwrap();
    let calls = Cell::new(0);
    let starts = || {
        calls.set(calls.get() + 1);
        if calls.get() == 6 {
            anyhow::bail!("Minecraft started")
        }
        Ok(())
    };
    let env = Env {
        closed: &starts,
        ..w.env()
    };
    ops::install(&env, &w.target, &v3).unwrap_err();
    assert!(
        ops::uninstall(&w.env(), &w.target, false)
            .unwrap()
            .is_empty()
    );
    ops::install(&w.env(), &w.target, &v3).unwrap();
    ops::install(&w.env(), &w.target, &v4).unwrap();
    let edit = safety::sha256_hex(b"my edit");
    let kept = safety::tree(&w.data).unwrap();
    assert!(
        kept.values().any(|h| *h == edit),
        "the hand-edited main.lua is gone: {:?}",
        kept.keys().collect::<Vec<_>>()
    );
}

/// A bridge JAR the user disabled in Prism and that also changed is replaced under its disabled name: it stays off.
#[test]
fn a_changed_disabled_bridge_jar_stays_disabled_through_an_update() {
    let w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    let mods_dir = &w.target.minecraft_mods;
    let jar = mods_dir.join(&v1.jar.0);
    let disabled = mods_dir.join(format!("{}{}", v1.jar.0, mods::DISABLED));
    fs::rename(&jar, &disabled).unwrap();
    fs::write(&disabled, "dev build").unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    assert!(
        disabled.exists(),
        "the update must keep the bridge JAR disabled"
    );
    assert!(
        !jar.exists(),
        "Fabric loads the bridge JAR the user disabled"
    );
}

/// An update over a deleted piece (the Lua mod gone with a UE4SS reinstall) keeps a set without it, which Roll back
/// could not install: Roll back still goes to the complete set kept before.
#[test]
fn an_update_over_a_missing_piece_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    fs::remove_dir_all(w.game().mods.join(deploy::MOD_NAME)).unwrap();
    ops::install(&w.env(), &w.target, &v3).unwrap();
    assert!(all_current(&w, &v3));
    let rolled = ops::rollback(&w.env(), &w.target);
    assert!(rolled.is_ok(), "{:#}", rolled.unwrap_err());
    assert!(all_current(&w, &v1));
}

/// An update over a deleted bridge JAR, listed on the review page, keeps its promise: Roll back still goes to the
/// release kept before.
#[test]
fn an_update_over_a_missing_bridge_jar_keeps_the_older_rollback_set() {
    let w = world();
    let (v1, v2, v3) = (payload(1), payload(2), payload(3));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let jar = w.target.minecraft_mods.join(&v2.jar.0);
    fs::remove_file(&jar).unwrap();
    let plan = ops::plan(&w.env(), &w.target, &v3).unwrap();
    assert_eq!(plan.changed, vec![jar.clone()]);
    ops::install(&w.env(), &w.target, &v3).unwrap();
    assert!(all_current(&w, &v3));
    let rolled = ops::rollback(&w.env(), &w.target);
    assert!(rolled.is_ok(), "{:#}", rolled.unwrap_err());
    assert!(all_current(&w, &v1));
}

/// A reinstall of the installed release for a new game build, killed at `step`, is finished by Install; Roll back
/// still goes to the release before.
fn reinstall_killed_at_keeps_the_older_rollback_set(step: ops::Step) {
    let mut w = world();
    let (v1, v2) = (payload(1), payload(2));
    ops::install(&w.env(), &w.target, &v1).unwrap();
    ops::install(&w.env(), &w.target, &v2).unwrap();
    fs::write(&w.game().exe, "test executable, next build").unwrap();
    w.pin = safety::sha256_hex(b"test executable, next build");
    let crash = |at: ops::Step| {
        if at == step {
            panic!("killed");
        }
    };
    let env = Env {
        step: &crash,
        ..w.env()
    };
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _ = ops::install(&env, &w.target, &v2);
    }));
    assert!(run.is_err());
    ops::install(&w.env(), &w.target, &v2).unwrap();
    let previous = state(&w).previous;
    let rolled = ops::rollback(&w.env(), &w.target).map(|_| all_current(&w, &v1));
    assert!(
        matches!(rolled, Ok(true)),
        "Roll back to v1 lost: previous = {previous:?}, rollback = {:?}",
        rolled.map_err(|e| format!("{e:#}"))
    );
}

#[test]
fn a_reinstall_killed_before_its_verify_keeps_the_older_rollback_set() {
    reinstall_killed_at_keeps_the_older_rollback_set(ops::Step::Verify);
}

#[test]
fn a_reinstall_killed_before_its_record_keeps_the_older_rollback_set() {
    reinstall_killed_at_keeps_the_older_rollback_set(ops::Step::Record);
}

// The ledger model: every start state, then one operation with a failure injected at every guard call and every step,
// then every recovery the window offers, twice; the invariants are checked after each action.

/// The panic standing for a kill: the process dies there, with nothing after it running.
struct Killed;

/// Everything under the fake home (relative path, bytes or `None` for a folder, mode), put back in place so a scenario
/// restarts without rebuilding the world: the absolute paths in `state.json` and the manifests stay valid.
type Disk = Vec<(PathBuf, Option<Vec<u8>>, u32)>;

fn mode(metadata: &fs::Metadata) -> u32 {
    #[cfg(unix)]
    return std::os::unix::fs::PermissionsExt::mode(&metadata.permissions());
    #[cfg(not(unix))]
    return u32::from(metadata.permissions().readonly());
}

fn take_disk(home: &Path) -> Disk {
    fn walk(home: &Path, dir: &Path, disk: &mut Disk) {
        let mut paths: Vec<_> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().path())
            .collect();
        paths.sort();
        for path in paths {
            let metadata = fs::symlink_metadata(&path).unwrap();
            assert!(!metadata.file_type().is_symlink(), "{}", path.display());
            let relative = path.strip_prefix(home).unwrap().to_path_buf();
            if metadata.is_dir() {
                disk.push((relative, None, mode(&metadata)));
                walk(home, &path, disk);
            } else {
                disk.push((relative, Some(fs::read(&path).unwrap()), mode(&metadata)));
            }
        }
    }
    let mut disk = Vec::new();
    walk(home, home, &mut disk);
    disk
}

fn put_disk(home: &Path, disk: &Disk) {
    for entry in fs::read_dir(home).unwrap() {
        let path = entry.unwrap().path();
        if fs::symlink_metadata(&path).unwrap().is_dir() {
            fs::remove_dir_all(&path).unwrap();
        } else {
            fs::remove_file(&path).unwrap();
        }
    }
    for (relative, bytes, _) in disk {
        match bytes {
            None => fs::create_dir(home.join(relative)).unwrap(),
            Some(bytes) => fs::write(home.join(relative), bytes).unwrap(),
        }
    }
    #[cfg(unix)]
    for (relative, _, mode) in disk.iter().rev() {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(home.join(relative), fs::Permissions::from_mode(*mode)).unwrap();
    }
}

/// What the user expects, which the disk and `state.json` must keep matching.
#[derive(Debug, Clone, Hash)]
struct Model {
    /// The releases Roll back may go to (`None`: no Roll back): the one installed before the last successful update.
    back: Vec<Option<u32>>,
    /// While a run is interrupted: the release complete on disk before it, if any. Roll back may undo that run.
    pending: Option<Option<u32>>,
    /// The disk last recorded as installed, with its release: a hand change there is the user's, not a broken install.
    tree: Option<(u32, BTreeMap<String, String>)>,
    /// Content changed by hand (its SHA-256), which no run deletes unless a confirm modal listed it.
    precious: Vec<String>,
}

impl Model {
    fn rollback_targets(&self) -> Vec<Option<u32>> {
        let mut targets = self.back.clone();
        if let Some(Some(pre)) = self.pending {
            targets.push(Some(pre));
        }
        targets
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Act {
    Install,
    Rollback,
    Repair,
    Uninstall,
}

#[derive(Debug, Clone, Copy)]
enum Fault {
    None,
    /// The guard refuses at its `at`th call (a game started), and after it unless `once` (it stays running).
    Refuse {
        at: usize,
        once: bool,
    },
    /// The process is killed at the guard's `at`th call.
    KillAtGuard(usize),
    /// The process is killed at the `at`th step reported.
    KillAtStep(usize),
}

enum Outcome {
    Done {
        release: Option<u32>,
        repaired: usize,
        refusals: usize,
    },
    Failed(String),
    Killed,
}

fn release_number(release: &str) -> Option<u32> {
    release.strip_prefix("0.")?.split('.').next()?.parse().ok()
}

/// The release a kept set holds, when it is exactly one of the test releases.
fn set_release(set: &Payload) -> Option<u32> {
    (1..=3).find(|n| {
        let p = payload(*n);
        set.jar == p.jar && set.lua == p.lua && set.dll == p.dll
    })
}

/// The release the disk holds, every piece current.
fn disk_release(w: &World) -> Option<u32> {
    (1..=3).find(|n| {
        ops::verify(&w.env(), &w.target, &payload(*n)).is_ok_and(|p| p.iter().all(|p| p.current))
    })
}

fn kind(ownership: &Ownership) -> &'static str {
    match ownership {
        Ownership::Absent => "absent",
        Ownership::Owned => "owned",
        Ownership::Foreign(_) => "foreign",
        Ownership::Modified(_) => "modified",
    }
}

/// The release complete on disk, by the record: installed, every piece owned and unchanged (an adapter for an older
/// executable included).
fn clean_release(w: &World, state: &Option<ledger::State>) -> Option<u32> {
    let state = state.as_ref().filter(|s| s.status == Status::Installed)?;
    let release = release_number(&state.release)?;
    ops::verify(&w.env(), &w.target, &payload(release))
        .ok()?
        .iter()
        .all(|p| p.current || (p.name == ops::ADAPTER && p.ownership == Ownership::Owned))
        .then_some(release)
}

fn data_hashes(w: &World) -> Vec<String> {
    let mut hashes: Vec<String> = w.files().into_values().collect();
    if w.data.is_dir() {
        hashes.extend(safety::tree(&w.data).unwrap().into_values());
    }
    hashes
}

/// The same files, the adapter's record of the executable it was installed for aside: a restore puts the kept adapter
/// back for the executable there now.
fn same_but_pin(a: &BTreeMap<String, String>, b: &BTreeMap<String, String>) -> bool {
    let strip = |files: &BTreeMap<String, String>| {
        let mut files = files.clone();
        files.retain(|path, _| !path.ends_with(adapter::MANIFEST));
        files
    };
    strip(a) == strip(b)
}

struct Violation {
    invariant: &'static str,
    cause: String,
    scenario: String,
    detail: String,
}

struct Explorer<'a> {
    w: &'a World,
    /// The payload the installer running the recoveries carries.
    installer: Payload,
    seen: std::collections::HashSet<u64>,
    found: Vec<Violation>,
}

impl Explorer<'_> {
    fn fail(&mut self, invariant: &'static str, cause: String, scenario: &str, detail: String) {
        self.found.push(Violation {
            invariant,
            cause,
            scenario: scenario.to_string(),
            detail,
        });
    }

    fn run(&self, act: Act, payload: &Payload, fault: Fault) -> (Outcome, usize, usize) {
        let w = self.w;
        let (guards, steps) = (Cell::new(0), Cell::new(0));
        let closed = || {
            guards.set(guards.get() + 1);
            match fault {
                Fault::Refuse { at, once }
                    if guards.get() == at || (!once && guards.get() > at) =>
                {
                    anyhow::bail!("Stellar Blade started")
                }
                Fault::KillAtGuard(at) if guards.get() == at => std::panic::panic_any(Killed),
                _ => Ok(()),
            }
        };
        let step = |_: ops::Step| {
            steps.set(steps.get() + 1);
            if matches!(fault, Fault::KillAtStep(at) if at == steps.get()) {
                std::panic::panic_any(Killed)
            }
        };
        let env = Env {
            data: w.data.clone(),
            closed: &closed,
            pin: &w.pin,
            step: &step,
        };
        let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match act {
            Act::Install => ops::install(&env, &w.target, payload).map(|s| Outcome::Done {
                release: release_number(&s.release),
                repaired: 0,
                refusals: 0,
            }),
            Act::Rollback => ops::rollback(&env, &w.target).map(|s| Outcome::Done {
                release: release_number(&s.release),
                repaired: 0,
                refusals: 0,
            }),
            Act::Repair => ops::repair(&env, &w.target, payload).map(|r| Outcome::Done {
                release: None,
                repaired: r.len(),
                refusals: 0,
            }),
            Act::Uninstall => ops::uninstall(&env, &w.target, false).map(|r| Outcome::Done {
                release: None,
                repaired: 0,
                refusals: r.len(),
            }),
        }));
        let outcome = match run {
            Ok(Ok(outcome)) => outcome,
            Ok(Err(error)) => Outcome::Failed(format!("{error:#}")),
            Err(payload) if payload.is::<Killed>() => Outcome::Killed,
            Err(payload) => std::panic::resume_unwind(payload),
        };
        (outcome, guards.get(), steps.get())
    }

    /// Runs `act` with `fault`, checks every invariant and moves the model on. Returns the guard and step counts.
    fn act(
        &mut self,
        m: &mut Model,
        act: Act,
        payload: &Payload,
        fault: Fault,
        scenario: &str,
    ) -> (usize, usize) {
        let w = self.w;
        let state_path = w.data.join(ledger::STATE);
        let state_before = ledger::load(&state_path);
        let clean_before = clean_release(w, &state_before);
        let pieces_before = ops::verify(&w.env(), &w.target, payload).unwrap_or_default();
        let files_before = w.files();
        // What the confirm modal or consent page lists.
        let listed: Vec<PathBuf> = match act {
            Act::Install => ops::plan(&w.env(), &w.target, payload)
                .map(|p| p.changed)
                .unwrap_or_default(),
            Act::Rollback => ops::changed_files(&w.target),
            Act::Repair => ops::repair_plan(&w.env(), &w.target, payload)
                .map(|p| p.into_iter().map(|(path, _)| path).collect())
                .unwrap_or_default(),
            Act::Uninstall => Vec::new(),
        };
        let precious_at: Vec<(String, Vec<String>)> = m
            .precious
            .iter()
            .map(|h| {
                let at = files_before
                    .iter()
                    .filter(|(_, sha)| *sha == h)
                    .map(|(path, _)| path.clone())
                    .collect();
                (h.clone(), at)
            })
            .collect();
        let (outcome, guards, steps) = self.run(act, payload, fault);
        let state = ledger::load(&state_path);
        let status = state.as_ref().map(|s| s.status);
        let class = match &outcome {
            Outcome::Done { .. } => "done",
            Outcome::Failed(_) => "failed",
            Outcome::Killed => "killed",
        };
        let when = format!("after {act:?} ({class})");

        match (&outcome, act) {
            (Outcome::Done { release, .. }, Act::Install | Act::Rollback) => {
                let release = release.unwrap_or(0);
                if disk_release(w) != Some(release) {
                    self.fail(
                        "I1",
                        format!("{when}: the disk is not the release it reports"),
                        scenario,
                        format!("reported v{release}, disk {:?}", disk_release(w)),
                    );
                }
                if act == Act::Rollback && !m.rollback_targets().contains(&Some(release)) {
                    self.fail(
                        "I2",
                        format!("{when}: Roll back reached another release"),
                        scenario,
                        format!(
                            "reached v{release}, expected one of {:?}",
                            m.rollback_targets()
                        ),
                    );
                }
                let pre = m.pending.unwrap_or(clean_before);
                match pre {
                    Some(pre) if pre != release => m.back = vec![Some(pre)],
                    Some(_) => {}
                    // The set it replaced cannot be installed again: Roll back keeps its target, unless it is
                    // installed now.
                    None => {
                        m.back.retain(|b| *b != Some(release));
                        if m.back.is_empty() {
                            m.back.push(None);
                        }
                    }
                }
                m.pending = None;
                m.tree = Some((release, w.files()));
            }
            (Outcome::Done { repaired, .. }, Act::Repair) => {
                let recorded = state_before
                    .as_ref()
                    .is_some_and(|s| s.status != Status::Removed);
                if let Some(piece) = pieces_before
                    .iter()
                    .find(|p| p.ownership != Ownership::Owned)
                    && *repaired == 0
                    && recorded
                {
                    self.fail(
                        "I6",
                        format!(
                            "{when}: nothing to repair while the {} is {}",
                            piece.name,
                            kind(&piece.ownership)
                        ),
                        scenario,
                        format!("record {:?}", state_before.as_ref().map(|s| s.status)),
                    );
                }
                if let Some(s) = state.as_ref().filter(|s| s.status == Status::Installed) {
                    m.tree = release_number(&s.release).map(|r| (r, w.files()));
                }
            }
            (Outcome::Done { refusals: 0, .. }, Act::Uninstall) => {
                m.back = m.rollback_targets();
                m.pending = None;
                m.tree = None;
            }
            (Outcome::Done { .. }, Act::Uninstall) => {}
            (Outcome::Failed(_) | Outcome::Killed, _) => {
                if let Outcome::Failed(error) = &outcome {
                    if matches!(fault, Fault::None) {
                        match act {
                            Act::Install => self.fail(
                                "I1",
                                format!(
                                    "Install again failed: {}",
                                    error.split(": ").next().unwrap_or("")
                                ),
                                scenario,
                                error.clone(),
                            ),
                            Act::Rollback
                                if state_before
                                    .as_ref()
                                    .and_then(|s| s.previous.as_ref())
                                    .is_some_and(|p| p.is_dir()) =>
                            {
                                self.fail(
                                    "I2",
                                    format!(
                                        "the offered Roll back failed: {}",
                                        error.split(": ").next().unwrap_or("")
                                    ),
                                    scenario,
                                    error.clone(),
                                )
                            }
                            _ => {}
                        }
                    }
                    let restored =
                        error.starts_with("Installation failed; the previous state was restored");
                    let resumed = state_before
                        .as_ref()
                        .is_some_and(|s| s.status == Status::InProgress);
                    // Finishing an interrupted run, the previous state is the release before that run.
                    if restored
                        && resumed
                        && m.pending
                            .is_some_and(|pre| pre.is_some() && disk_release(w) != pre)
                    {
                        self.fail("I1", format!("{when}: says restored but the release before the interrupted run is not back"), scenario, format!("disk {:?}, expected {:?}", disk_release(w), m.pending));
                    }
                    if restored && !resumed && !same_but_pin(&w.files(), &files_before) {
                        let changed: Vec<_> = w
                            .files()
                            .into_iter()
                            .filter(|(p, h)| files_before.get(p) != Some(h))
                            .map(|(p, _)| p)
                            .chain(
                                files_before
                                    .keys()
                                    .filter(|p| !w.files().contains_key(*p))
                                    .cloned(),
                            )
                            .collect();
                        self.fail(
                            "I1",
                            format!("{when}: says restored but the files differ"),
                            scenario,
                            format!("{changed:?}"),
                        );
                    }
                }
                if status == Some(Status::InProgress) {
                    if m.pending.is_none() {
                        m.pending = Some(clean_before);
                    }
                } else if m.pending.take().is_some() {
                    // An interrupted run put back as it was before it.
                    m.tree = state
                        .as_ref()
                        .filter(|s| s.status == Status::Installed)
                        .and_then(|s| release_number(&s.release))
                        .map(|r| (r, w.files()));
                }
            }
        }

        // I5: the record describes the disk.
        let pieces = ops::verify(&w.env(), &w.target, &self.installer);
        match (&state, pieces) {
            (_, Err(error)) => self.fail(
                "I5",
                format!("{when}: the pieces cannot be read"),
                scenario,
                format!("{error:#}"),
            ),
            (Some(s), Ok(_)) if s.status == Status::Installed => {
                let release = release_number(&s.release).unwrap_or(0);
                let as_recorded = m
                    .tree
                    .as_ref()
                    .is_some_and(|(r, t)| *r == release && *t == w.files());
                let pieces = ops::verify(&w.env(), &w.target, &crate::payload(release.max(1)))
                    .unwrap_or_default();
                if let Some(piece) = pieces
                    .iter()
                    .find(|p| !p.current && !matches!(p.ownership, Ownership::Modified(_)))
                    && !as_recorded
                {
                    self.fail(
                        "I5",
                        format!(
                            "{when}: recorded installed, but the {} is {} and not current",
                            piece.name,
                            kind(&piece.ownership)
                        ),
                        scenario,
                        format!("record v{release}, disk {:?}", disk_release(w)),
                    );
                }
            }
            (Some(s), Ok(pieces)) if s.status == Status::InProgress => drop(pieces),
            (_, Ok(pieces)) => {
                if let Some(piece) = pieces.iter().find(|p| p.ownership != Ownership::Absent) {
                    self.fail(
                        "I5",
                        format!(
                            "{when}: recorded {:?}, but the {} is {}",
                            status,
                            piece.name,
                            kind(&piece.ownership)
                        ),
                        scenario,
                        String::new(),
                    );
                }
            }
        }
        if let Some(s) = &state
            && s.status != Status::InProgress
            && s.kept.is_some()
        {
            self.fail(
                "I5",
                format!("{when}: a finished record still says what a run kept"),
                scenario,
                format!("{:?}", s.kept),
            );
        }

        // I2, I3: Roll back goes to a complete set of the expected release, which is still there.
        let targets = m.rollback_targets();
        match state.as_ref().and_then(|s| s.previous.clone()) {
            None if !targets.contains(&None) => self.fail(
                "I3",
                format!("{when}: no Roll back, expected {targets:?}"),
                scenario,
                String::new(),
            ),
            None => {}
            Some(previous) => match Payload::from_previous(&previous) {
                Err(error) => self.fail(
                    "I3",
                    format!("{when}: the Roll back set does not load"),
                    scenario,
                    format!("{}: {error:#}", previous.display()),
                ),
                Ok(set) => match set_release(&set) {
                    None => self.fail(
                        "I2",
                        format!("{when}: the Roll back set is partial"),
                        scenario,
                        previous.display().to_string(),
                    ),
                    Some(n) if status == Some(Status::Installed) && disk_release(w) == Some(n) => {
                        self.fail(
                            "I2",
                            format!("{when}: Roll back would go to the installed release"),
                            scenario,
                            format!("v{n}, expected one of {targets:?}"),
                        )
                    }
                    Some(n) if !targets.contains(&Some(n)) => self.fail(
                        "I2",
                        format!("{when}: Roll back would go to another release"),
                        scenario,
                        format!("v{n}, expected one of {targets:?}"),
                    ),
                    Some(_) => {}
                },
            },
        }

        // I4: files changed by hand survive, unless a confirm modal that does not promise to keep them listed them.
        let hashes = data_hashes(w);
        let mut kept = Vec::new();
        for (hash, at) in precious_at {
            let consented =
                act == Act::Repair && at.iter().any(|p| listed.iter().any(|l| l.ends_with(p)));
            if hashes.contains(&hash) || consented {
                if hashes.contains(&hash) {
                    kept.push(hash);
                }
            } else {
                self.fail(
                    "I4",
                    format!("{when}: a file changed by hand is gone"),
                    scenario,
                    format!("was at {at:?}; listed {listed:?}"),
                );
            }
        }
        m.precious = kept;
        (guards, steps)
    }

    /// Every recovery the window offers from here, `depth` actions deep.
    fn recover(&mut self, m: &Model, depth: u32, scenario: &str) {
        if depth == 0 {
            return;
        }
        let disk = take_disk(&self.w.home);
        let mut hasher = std::hash::DefaultHasher::new();
        for (path, bytes, _) in &disk {
            if path.components().any(|c| c.as_os_str() == "save-backups") {
                continue;
            }
            path.hash(&mut hasher);
            if path.file_name().is_some_and(|n| n == ledger::STATE) {
                let mut state = ledger::load(&self.w.home.join(path)).unwrap_or_default();
                (state.updated, state.save_backup) = (0, None);
                format!("{state:?}").hash(&mut hasher);
            } else {
                bytes.hash(&mut hasher);
            }
        }
        (m, depth).hash(&mut hasher);
        if !self.seen.insert(std::hash::Hasher::finish(&hasher)) {
            return;
        }
        let installer = self.installer.clone();
        for act in [Act::Install, Act::Rollback, Act::Repair] {
            put_disk(&self.w.home, &disk);
            let offered = ledger::load(&self.w.data.join(ledger::STATE))
                .and_then(|s| s.previous)
                .is_some_and(|p| p.is_dir());
            if act == Act::Rollback && !offered {
                continue;
            }
            let mut m = m.clone();
            let scenario = format!("{scenario} > {act:?}");
            self.act(&mut m, act, &installer, Fault::None, &scenario);
            self.recover(&m, depth - 1, &scenario);
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum Start {
    Fresh,
    V1,
    V1V2,
    V1V2LuaChanged,
    V1V2DllChanged,
    V1V2JarMissing,
    Uninstalled,
    /// A 0.3.1 installer killed midway through the update to v3: no `kept`, and 0.3.1 kept only the newest set.
    V031Interrupted,
}

#[derive(Debug, Clone, Copy)]
enum Op {
    Update,
    /// The installed release again, for a new game build.
    Reinstall,
    Repair,
    Rollback,
    Uninstall,
}

fn start(start: Start) -> (World, Model, u32) {
    let w = world();
    let mut m = Model {
        back: vec![None],
        pending: None,
        tree: None,
        precious: Vec::new(),
    };
    let mut latest = 1;
    if !matches!(start, Start::Fresh) {
        ops::install(&w.env(), &w.target, &payload(1)).unwrap();
        m.tree = Some((1, w.files()));
    }
    if !matches!(start, Start::Fresh | Start::V1) {
        ops::install(&w.env(), &w.target, &payload(2)).unwrap();
        (m.back, latest) = (vec![Some(1)], 2);
        m.tree = Some((2, w.files()));
    }
    let lua = w
        .game()
        .mods
        .join(deploy::MOD_NAME)
        .join("Scripts/main.lua");
    match start {
        Start::V1V2LuaChanged => {
            fs::write(&lua, "my edit").unwrap();
            m.precious.push(safety::sha256_hex(b"my edit"));
        }
        Start::V1V2DllChanged => {
            let dll = [adapter::test_dll(), b"my build".to_vec()].concat();
            fs::write(w.win64().join(adapter::DLL), &dll).unwrap();
            m.precious.push(safety::sha256_hex(&dll));
        }
        Start::V1V2JarMissing => {
            fs::remove_file(w.target.minecraft_mods.join(payload(2).jar.0)).unwrap()
        }
        Start::Uninstalled => {
            assert!(
                ops::uninstall(&w.env(), &w.target, false)
                    .unwrap()
                    .is_empty()
            );
            m.tree = None;
        }
        Start::V031Interrupted => {
            let killed = |step: ops::Step| {
                if step == ops::Step::LuaMod {
                    std::panic::panic_any(Killed)
                }
            };
            let env = Env {
                step: &killed,
                ..w.env()
            };
            let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let _ = ops::install(&env, &w.target, &payload(3));
            }));
            assert!(run.is_err());
            let path = w.data.join(ledger::STATE);
            let mut json: serde_json::Value =
                serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
            json.as_object_mut().unwrap().remove("kept");
            json["previous"] = serde_json::json!(w.data.join("previous/0.2.0+test"));
            fs::write(&path, serde_json::to_vec_pretty(&json).unwrap()).unwrap();
            fs::remove_dir_all(w.data.join("previous/0.1.0+test")).unwrap();
            (m.back, m.pending, m.tree, latest) = (vec![None], Some(Some(2)), None, 3);
        }
        _ => {}
    }
    if matches!(
        start,
        Start::V1V2LuaChanged | Start::V1V2DllChanged | Start::V1V2JarMissing
    ) {
        m.tree = Some((2, w.files()));
    }
    (w, m, latest)
}

/// Explores one start state and operation; returns the violations.
fn model_scenario(start_state: Start, op: Op) -> Vec<Violation> {
    let (mut w, mut m, latest) = start(start_state);
    if matches!(op, Op::Reinstall) {
        fs::write(&w.game().exe, "test executable, next build").unwrap();
        w.pin = safety::sha256_hex(b"test executable, next build");
        if let Some((_, tree)) = &mut m.tree {
            *tree = w.files();
        }
    }
    let installer = payload(if matches!(op, Op::Update) { 3 } else { latest });
    let act = match op {
        Op::Update | Op::Reinstall => Act::Install,
        Op::Repair => Act::Repair,
        Op::Rollback => Act::Rollback,
        Op::Uninstall => Act::Uninstall,
    };
    let base = take_disk(&w.home);
    let mut explorer = Explorer {
        w: &w,
        installer: installer.clone(),
        seen: Default::default(),
        found: Vec::new(),
    };
    let mut faults = vec![Fault::None];
    let mut n = 0;
    while n < faults.len() {
        let fault = faults[n];
        n += 1;
        put_disk(&w.home, &base);
        let mut model = m.clone();
        let scenario = format!("{start_state:?} / {op:?} [{fault:?}]");
        let (guards, steps) = explorer.act(&mut model, act, &installer, fault, &scenario);
        if matches!(fault, Fault::None) {
            for at in 1..=guards {
                faults.extend([
                    Fault::Refuse { at, once: true },
                    Fault::Refuse { at, once: false },
                    Fault::KillAtGuard(at),
                ]);
            }
            faults.extend((1..=steps).map(Fault::KillAtStep));
        }
        explorer.recover(&model, 2, &scenario);
    }
    explorer.found
}

/// The invariants every Update, Reinstall, Repair, Roll back and Uninstall keeps, whatever stops it, and through every
/// recovery the window offers after it:
/// I1 the disk is a release the record names, or Install again gets there; a restore that says so put every file back;
/// I2 Roll back goes to the release installed before the last successful update (or undoes an interrupted run), never
///    to a partial set, and does not fail when offered;
/// I3 the set Roll back goes to is never deleted while it is the way back;
/// I4 files changed by hand are never deleted, unless a Repair confirm modal listed them;
/// I5 `state.json` describes the disk: status, release, the Roll back set, `kept` only while in progress;
/// I6 Repair never says "nothing to repair" while a recorded piece is missing or changed.
#[test]
#[ignore = "finds open bugs: a restore from a set kept without a piece, a Roll back set without a piece, a stopped or \
            partial Uninstall still recorded installed, Roll back to the installed release after Uninstall"]
fn every_failure_and_recovery_keeps_the_ledger_invariants() {
    static HOOK: std::sync::Once = std::sync::Once::new();
    HOOK.call_once(|| {
        let default = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if !info.payload().is::<Killed>() {
                default(info)
            }
        }));
    });
    let starts = [
        Start::Fresh,
        Start::V1,
        Start::V1V2,
        Start::V1V2LuaChanged,
        Start::V1V2DllChanged,
        Start::V1V2JarMissing,
        Start::Uninstalled,
        Start::V031Interrupted,
    ];
    let ops = [
        Op::Update,
        Op::Reinstall,
        Op::Repair,
        Op::Rollback,
        Op::Uninstall,
    ];
    let found: Vec<Violation> = std::thread::scope(|scope| {
        let runs: Vec<_> = starts
            .iter()
            .flat_map(|s| ops.iter().map(move |o| (*s, *o)))
            .map(|(s, o)| scope.spawn(move || model_scenario(s, o)))
            .collect();
        runs.into_iter().flat_map(|r| r.join().unwrap()).collect()
    });
    // One line per cause, with its shortest scenario.
    let mut causes: BTreeMap<(&str, String), (usize, String, String)> = BTreeMap::new();
    for v in found {
        let entry = causes.entry((v.invariant, v.cause)).or_insert((
            0,
            v.scenario.clone(),
            v.detail.clone(),
        ));
        entry.0 += 1;
        if v.scenario.len() < entry.1.len() {
            (entry.1, entry.2) = (v.scenario, v.detail);
        }
    }
    let report: Vec<String> = causes
        .iter()
        .map(|((invariant, cause), (count, scenario, detail))| {
            format!("{invariant} {cause} (x{count})\n    {scenario}\n    {detail}")
        })
        .collect();
    assert!(
        report.is_empty(),
        "{} causes:\n{}",
        report.len(),
        report.join("\n")
    );
}
