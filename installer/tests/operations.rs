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

/// Replacing a piece changed by hand is shown before consent, keeps the changed files, and a rollback to that kept set
/// is refused clearly instead of reinstalling half a piece.
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
    let kept = state(&w).previous.unwrap();
    assert!(kept.join(adapter::MANIFEST).exists());
    let error = ops::rollback(&w.env(), &w.target).unwrap_err();
    assert_eq!(
        error.downcast_ref::<Refusal>().map(|r| r.kind),
        Some(Kind::PreviousChanged),
        "{error:#}"
    );
    assert!(all_current(&w, &v2), "the refused rollback changed nothing");
}
