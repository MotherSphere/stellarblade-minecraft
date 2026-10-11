//! The window's state machine on a fake home: every page of the wizard, a dedicated Prism instance, the games modal,
//! repair, update, roll back, uninstall, the close guard, a dry run that leaves the tree byte for byte as it was, and
//! the server operator's page, and the avatar pack builder's pages (its ready check, consents, Oodle from a local
//! server, failed builds and a dry run) with a fake EveExtract.
//! Jobs run in place; every page is drawn after every message. Nothing outside the temporary folder is read or written.
//! Linux only: the fake home has the Linux layout and a fake `/proc`; Windows detection reads the registry and the
//! live process list, which a test cannot fake.
#![cfg(unix)]

use std::cell::RefCell;
use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use sbmc_installer::app::{self, Action, App, Choice, Ctx, Message, Modal, Prefs, Role, Screen};
use sbmc_installer::detect::Override;
use sbmc_installer::i18n::{self, Lang};
use sbmc_installer::ledger::{self, Status};
use sbmc_installer::ops::Step;
use sbmc_installer::pack::inputs::{self, Remote, Sources};
use sbmc_installer::payload::Payload;
use sbmc_installer::safety::{self, Running};
use sbmc_installer::{
    Kind, Ownership, Refusal, adapter, deploy, launcher, mods, ops, pins, server, ui,
};

/// The window's language is global: each test holds it for its whole run.
fn language() -> MutexGuard<'static, ()> {
    static LANGUAGE: Mutex<()> = Mutex::new(());
    LANGUAGE.lock().unwrap_or_else(|e| e.into_inner())
}

fn write(path: &Path, data: impl AsRef<[u8]>) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, data).unwrap();
}

struct Fake {
    _root: tempfile::TempDir,
    home: PathBuf,
    win64: PathBuf,
    saves: PathBuf,
    proc_root: PathBuf,
}

/// Steam with Stellar Blade in a second library, UE4SS and other loaders, a prefix without the override, saves, and
/// a native Prism with one unrelated instance.
fn fake() -> Fake {
    let root = tempfile::tempdir().unwrap();
    let home = fs::canonicalize(root.path()).unwrap();
    let steam = home.join(".local/share/Steam");
    let library = home.join("Games/SteamLibrary");
    write(
        &steam.join("steamapps/libraryfolders.vdf"),
        format!(
            "\"libraryfolders\" {{ \"0\" {{ \"path\" \"{}\" }} \"1\" {{ \"path\" \"{}\" }} }}",
            steam.display(),
            library.display()
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
    write(&win64.join("ue4ss/Mods/mods.txt"), "OtherMod : 1\n");
    write(&win64.join("ue4ss/Mods/OtherMod/Scripts/main.lua"), "other");
    let prefix = library.join("steamapps/compatdata/3489700/pfx");
    write(&prefix.join("user.reg"), "WINE REGISTRY Version 2\n");
    let saves = prefix.join("drive_c/users/steamuser/AppData/Local/SB/Saved/SaveGames");
    write(
        &saves.join("76561190000000000/StellarBladeSave00.sav"),
        "save",
    );
    let prism = home.join(".local/share/PrismLauncher");
    write(&prism.join("prismlauncher.cfg"), "InstanceDir=instances\n");
    write(
        &prism.join("instances/Vanilla/instance.cfg"),
        "name=Vanilla\n",
    );
    write(
        &prism.join("instances/Vanilla/mmc-pack.json"),
        r#"{"components": [{"uid": "net.minecraft", "version": "1.20.4"}]}"#,
    );
    let proc_root = home.join("proc");
    fs::create_dir_all(&proc_root).unwrap();
    // `java -version` for the server page: Java 21.
    write(
        &home.join("bin/java"),
        "#!/bin/sh\necho 'openjdk version \"21.0.5\" 2024-10-15' >&2\n",
    );
    sbmc_installer::platform::make_executable(&home.join("bin/java")).unwrap();
    Fake {
        _root: root,
        win64,
        saves,
        proc_root,
        home,
    }
}

fn payload(version: u32) -> Arc<Payload> {
    Arc::new(Payload {
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
    })
}

fn ctx(fake: &Fake, dry_run: bool) -> Ctx {
    Ctx {
        home: fake.home.clone(),
        proc_root: fake.proc_root.clone(),
        data: fake.home.join("data/Colony/Stellar Bridge"),
        log: None,
        prefs_file: None,
        pin: safety::sha256_hex(b"test executable"),
        payload: Some(payload(1)),
        dry_run,
        java: fake.home.join("bin/java").into_os_string(),
        cache: fake.home.join("cache/Colony/Stellar Bridge"),
        // Nothing listens there: a test that downloads serves its own files.
        sources: Sources {
            beside: None,
            sidecar: None,
            oodle: Remote {
                url: "http://127.0.0.1:9/gcc-x64-release.zip".into(),
                size: Some(1),
                sha256: "0".repeat(64),
            },
            oodle_entry: "lib/liboodle-data-shared.so".into(),
        },
    }
}

fn english() -> Prefs {
    Prefs {
        language: Some("en".into()),
        ..Prefs::default()
    }
}

/// Feeds `message`, runs the job it starts in place (its messages come next, in order), and draws the page after
/// every message.
fn drive(app: &mut App, message: Message) {
    let mut queue = VecDeque::from([message]);
    while let Some(message) = queue.pop_front() {
        if let Some(job) = app.step(message) {
            let sent = RefCell::new(Vec::new());
            job.run(&|m| sent.borrow_mut().push(m));
            for m in sent.into_inner().into_iter().rev() {
                queue.push_front(m);
            }
        }
        drop(ui::view(app));
    }
}

fn start(fake: &Fake, dry_run: bool) -> App {
    let (mut app, job) = App::new(ctx(fake, dry_run), english());
    let sent = RefCell::new(Vec::new());
    job.run(&|m| sent.borrow_mut().push(m));
    for message in sent.into_inner() {
        drive(&mut app, message);
    }
    assert!(app.found.is_some() && !app.detecting);
    app
}

fn errors(app: &App) -> Vec<String> {
    app.toasts
        .iter()
        .filter(|t| t.error)
        .map(|t| t.text.clone())
        .collect()
}

fn instance_mods(fake: &Fake) -> PathBuf {
    fake.home
        .join(".local/share/PrismLauncher/instances")
        .join(launcher::INSTANCE_NAME)
        .join("minecraft/mods")
}

#[test]
fn the_wizard_installs_maintains_and_removes_on_a_fake_tree() {
    let _language = language();
    let fake = fake();
    let saves = safety::tree(&fake.saves).unwrap();
    let mut app = start(&fake, false);
    assert_eq!(
        app.screen,
        Screen::Welcome,
        "a first install starts in the wizard"
    );

    // Welcome → detect: everything found, the override missing, nothing installed yet.
    drive(&mut app, Message::Start);
    assert_eq!(app.screen, Screen::Detect);
    let found = app.found.as_ref().unwrap();
    assert!(found.game.as_ref().unwrap().build_supported());
    assert_eq!(found.dll_override, Some(Override::Missing));
    assert!(!found.installed() && found.current.is_none());
    assert!(app.can_continue());
    assert_eq!(app.primary_action(), ("action_install", true));

    // Verify: the executable is hashed against the pin.
    drive(&mut app, Message::Continue);
    assert_eq!((app.screen, app.exe_ok), (Screen::Verify, Some(true)));

    // Target: a new dedicated instance; the 1.20.4 one is not offered.
    drive(&mut app, Message::Continue);
    assert_eq!(app.screen, Screen::Target);
    assert_eq!(app.choices(), vec![Choice::NewInstance(0), Choice::Manual]);
    drive(&mut app, Message::ToConsent);
    assert_eq!(app.screen, Screen::Target, "nothing chosen yet");
    assert_eq!(errors(&app).len(), 1);
    let toast = app.toasts[0].id;
    drive(&mut app, Message::DismissToast(toast));
    assert!(app.toasts.is_empty());
    drive(&mut app, Message::Choose(Choice::NewInstance(0)));
    drive(&mut app, Message::ToConsent);

    // Consent lists what will be written, before anything is.
    assert_eq!(app.screen, Screen::Consent);
    let (_, create, plan) = app.plan.clone().unwrap();
    assert!(create.is_some());
    let instance_dir = instance_mods(&fake)
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf();
    assert_eq!(plan.writes[0], instance_dir);
    for expected in [
        instance_mods(&fake).join("stellar-bridge-0.1.0.jar"),
        instance_mods(&fake).join(pins::FABRIC_API_FILE),
        fake.win64.join("ue4ss/Mods").join(deploy::MOD_NAME),
        fake.win64.join(adapter::DLL),
        fake.home
            .join(".local/share/stellar-blade-minecraft/bridge"),
    ] {
        assert!(
            plan.writes.contains(&expected),
            "{expected:?} not in {:?}",
            plan.writes
        );
    }
    assert!(plan.replaced.is_empty());
    assert!(plan.backups.iter().any(|b| b.ends_with("save-backups")));
    assert!(!instance_dir.exists());
    drive(&mut app, Message::Begin(Action::Install));
    assert_eq!(app.screen, Screen::Consent, "the box is not ticked");

    // Install: the steps in order, then done with the launch option to copy.
    drive(&mut app, Message::Consent(true));
    drive(&mut app, Message::Begin(Action::Install));
    assert_eq!(errors(&app), Vec::<String>::new());
    assert_eq!(app.screen, Screen::Done);
    assert!(app.outcome.as_ref().unwrap().ok && !app.busy);
    assert_eq!(
        app.steps,
        [
            Step::Checks,
            Step::SaveBackup,
            Step::Remove,
            Step::Minecraft,
            Step::LuaMod,
            Step::Adapter,
            Step::Verify,
            Step::Record
        ]
    );
    assert!(
        instance_mods(&fake)
            .join("stellar-bridge-0.1.0.jar")
            .is_file()
    );
    let state = ledger::load(&app.ctx.data.join(ledger::STATE)).unwrap();
    assert_eq!(state.status, Status::Installed);
    assert_eq!(state.created_instance, Some(instance_dir.clone()));
    let found = app.found.as_ref().unwrap();
    assert!(
        found
            .pieces
            .iter()
            .all(|p| p.ownership == Ownership::Owned && p.current)
    );
    assert_eq!(safety::tree(&fake.saves).unwrap(), saves);
    assert_eq!(
        fs::read(fake.win64.join("dinput8.dll")).unwrap(),
        b"Xion loader"
    );

    // Back on the status page, a changed file is repaired after its confirm modal.
    drive(&mut app, Message::Rescan);
    assert_eq!(app.screen, Screen::Detect);
    assert!(app.found.as_ref().unwrap().installed());
    assert_eq!(
        app.primary_action(),
        ("action_up_to_date", false),
        "this release, every piece current"
    );
    let bridge_lua = fake
        .win64
        .join("ue4ss/Mods")
        .join(deploy::MOD_NAME)
        .join("Scripts/bridge.lua");
    fs::remove_file(&bridge_lua).unwrap();
    drive(&mut app, Message::Rescan);
    assert_eq!(
        app.primary_action(),
        ("action_update", false),
        "a drifted game piece: repair it first"
    );
    drive(&mut app, Message::Begin(Action::Repair));
    match &app.modal {
        Some(Modal::Confirm(Action::Repair, lines)) => {
            assert_eq!(lines, &[bridge_lua.display().to_string()])
        }
        other => panic!("{other:?}"),
    }
    drive(&mut app, Message::Proceed);
    assert!(app.outcome.as_ref().unwrap().ok, "{:?}", app.outcome);
    assert_eq!(fs::read(&bridge_lua).unwrap(), b"return {}\n");

    // Update to another release through the same wizard: the recorded target is preselected.
    drive(&mut app, Message::Rescan);
    assert_eq!(
        app.primary_action(),
        ("action_up_to_date", false),
        "repaired"
    );
    // Reproducible builds: a docs-only release has the same pieces under a new name, still recorded by an update.
    let mut renamed = Arc::into_inner(payload(1)).unwrap();
    renamed.release = "0.1.0+docs".into();
    app.ctx.payload = Some(Arc::new(renamed));
    drive(&mut app, Message::Rescan);
    assert_eq!(
        app.primary_action(),
        ("action_update", true),
        "same pieces, another release"
    );
    app.ctx.payload = Some(payload(2));
    drive(&mut app, Message::Rescan);
    assert_eq!(
        app.primary_action(),
        ("action_update", true),
        "an older release"
    );
    assert_eq!((app.choice, app.role), (Some(Choice::Current), Role::Eve));
    drive(&mut app, Message::Continue);
    drive(&mut app, Message::Continue);
    drive(&mut app, Message::ToConsent);
    let (_, create, plan) = app.plan.clone().unwrap();
    assert!(create.is_none() && !plan.replaced.is_empty());
    drive(&mut app, Message::Consent(true));
    drive(&mut app, Message::Begin(Action::Install));
    assert!(app.outcome.as_ref().unwrap().ok, "{:?}", app.outcome);
    assert!(app.steps.contains(&Step::KeepPrevious));
    assert_eq!(
        fs::read(instance_mods(&fake).join("stellar-bridge-0.1.0.jar")).unwrap(),
        b"jar v2"
    );

    // Roll back to the kept release.
    drive(&mut app, Message::Rescan);
    drive(&mut app, Message::Begin(Action::Rollback));
    assert!(matches!(
        app.modal,
        Some(Modal::Confirm(Action::Rollback, _))
    ));
    drive(&mut app, Message::Proceed);
    assert!(app.outcome.as_ref().unwrap().ok, "{:?}", app.outcome);
    assert_eq!(
        fs::read(instance_mods(&fake).join("stellar-bridge-0.1.0.jar")).unwrap(),
        b"jar v1"
    );

    // A reopened installer lands on the status page, and keeps the recorded role until the user picks one.
    let mut reopened = start(&fake, false);
    assert_eq!(
        (reopened.screen, reopened.role),
        (Screen::Detect, Role::Eve)
    );
    drive(&mut reopened, Message::Role(Role::Viewer));
    drive(&mut reopened, Message::Rescan);
    assert_eq!(
        reopened.role,
        Role::Viewer,
        "a rescan keeps the user's role"
    );

    // Uninstall waits in the games modal while Stellar Blade runs, and the window refuses to close mid-write.
    drive(&mut app, Message::Rescan);
    write(&fake.proc_root.join("4242/comm"), "SB-Win64-Shippi\n");
    drive(&mut app, Message::Begin(Action::Uninstall));
    // The list follows the Fabric API box.
    let fabric = instance_mods(&fake)
        .join(pins::FABRIC_API_FILE)
        .display()
        .to_string();
    let listed = |app: &App| match &app.modal {
        Some(Modal::Confirm(Action::Uninstall, lines)) => lines.contains(&fabric),
        other => panic!("{other:?}"),
    };
    assert!(!listed(&app), "kept by default");
    drive(&mut app, Message::RemoveFabric(true));
    assert!(listed(&app));
    drive(&mut app, Message::Proceed);
    assert!(matches!(&app.modal, Some(Modal::Games(r)) if r == &[Running::StellarBlade]));
    drive(&mut app, Message::Proceed);
    assert!(
        matches!(app.modal, Some(Modal::Games(_))),
        "locked while it runs"
    );
    fs::remove_dir_all(fake.proc_root.join("4242")).unwrap();
    drive(&mut app, Message::Tick);
    assert!(matches!(&app.modal, Some(Modal::Games(r)) if r.is_empty()));
    app.busy = true;
    drop(app.update(Message::CloseRequested(iced::window::Id::unique())));
    assert_eq!(errors(&app).len(), 1, "close refused while busy");
    assert!(app.close_pending, "closes once the job is done");
    app.close_pending = false;
    app.busy = false;
    drive(&mut app, Message::Proceed);
    assert!(app.outcome.as_ref().unwrap().ok, "{:?}", app.outcome);
    assert_eq!(app.outcome.as_ref().unwrap().action, Action::Uninstall);
    let left: Vec<String> = fs::read_dir(instance_mods(&fake))
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    assert_eq!(left, Vec::<String>::new());
    assert!(!fake.win64.join(adapter::DLL).exists());
    assert!(
        instance_dir.join("instance.cfg").is_file(),
        "the instance stays"
    );
    let state = ledger::load(&app.ctx.data.join(ledger::STATE)).unwrap();
    assert_eq!(state.status, Status::Removed);
    assert_eq!(safety::tree(&fake.saves).unwrap(), saves);
}

#[test]
fn a_dry_run_walks_to_done_and_writes_nothing() {
    let _language = language();
    let fake = fake();
    let before = safety::tree(&fake.home).unwrap();
    let mut app = start(&fake, true);
    for message in [
        Message::Start,
        Message::Continue,
        Message::Continue,
        Message::Choose(Choice::NewInstance(0)),
        Message::ToConsent,
        Message::Consent(true),
        Message::Begin(Action::Install),
    ] {
        drive(&mut app, message);
    }
    let outcome = app.outcome.clone().unwrap();
    assert!(outcome.dry_run && app.screen == Screen::Done);
    assert!(!app.plan.as_ref().unwrap().2.writes.is_empty());
    assert_eq!(safety::tree(&fake.home).unwrap(), before);
    assert!(!app.ctx.data.exists());
}

#[test]
fn a_viewer_and_a_python_tools_install() {
    let _language = language();
    let fake = fake();
    // The Lua mod and adapter as the Python tools leave them, and a bridge JAR copied by hand.
    let bridge = fake
        .home
        .join(".local/share/stellar-blade-minecraft/bridge");
    fs::create_dir_all(&bridge).unwrap();
    fs::set_permissions(&bridge, std::os::unix::fs::PermissionsExt::from_mode(0o700)).unwrap();
    let mods_dir = fake.win64.join("ue4ss/Mods");
    let lua = BTreeMap::from([
        (
            "Scripts/main.lua".to_string(),
            b"print('Loaded revision 57;')\n".to_vec(),
        ),
        ("enabled.txt".to_string(), Vec::new()),
    ]);
    deploy::install(&mods_dir, &lua, Some(&bridge), &|| Ok(())).unwrap();
    let pin = safety::sha256_hex(b"test executable");
    adapter::install(
        &fake.win64.join(adapter::EXE),
        &adapter::test_dll(),
        &bridge,
        &pin,
        &|| Ok(()),
    )
    .unwrap();
    let instance = fake
        .home
        .join(".local/share/PrismLauncher/instances/EVE Bridge");
    write(&instance.join("instance.cfg"), "name=EVE Bridge\n");
    write(
        &instance.join("mmc-pack.json"),
        r#"{"components": [{"uid": "net.minecraft", "version": "1.21.1"},
            {"uid": "net.fabricmc.fabric-loader", "version": "0.19.5"}]}"#,
    );
    write(
        &instance.join("minecraft/mods/stellar-bridge-0.1.0.jar"),
        "hand copied",
    );

    let mut app = start(&fake, false);
    assert_eq!(
        app.screen,
        Screen::Detect,
        "something is installed: the status page"
    );
    drive(&mut app, Message::Start);
    let found = app.found.as_ref().unwrap();
    assert!(found.installed());
    assert!(matches!(found.pieces[0].ownership, Ownership::Foreign(_)));
    assert_eq!(found.pieces[1].ownership, Ownership::Owned);
    // The refusal shows in the window's language with its path, on the status row and in the toast.
    let jar = instance.join("minecraft/mods/stellar-bridge-0.1.0.jar");
    let refusal = Refusal::new(Kind::ForeignJar, jar.display());
    drive(&mut app, Message::Language(Lang::Fr));
    let (_, row) = ui::piece_status(&app.found.as_ref().unwrap().pieces[0]);
    assert_eq!(row, i18n::refusal(&refusal));
    assert!(
        row.starts_with("Refusé") && row.ends_with(&jar.display().to_string()),
        "{row}"
    );
    // Installing into that instance is refused before anything is written.
    drive(&mut app, Message::Role(Role::Viewer));
    drive(&mut app, Message::Continue);
    assert_eq!(app.screen, Screen::Target);
    drive(&mut app, Message::Choose(Choice::Instance(0)));
    drive(&mut app, Message::ToConsent);
    assert_eq!(app.screen, Screen::Target);
    assert_eq!(
        errors(&app),
        [format!("Mod du pont : {}", i18n::refusal(&refusal))]
    );
    assert_eq!(errors(&app)[0], i18n::piece_refusal(ops::JAR, &refusal));
    drive(&mut app, Message::Language(Lang::En));
    // Uninstall removes what the Python tools recorded and leaves the hand-copied JAR, saying so.
    drive(&mut app, Message::Rescan);
    drive(&mut app, Message::Begin(Action::Uninstall));
    drive(&mut app, Message::Proceed);
    let outcome = app.outcome.clone().unwrap();
    assert!(outcome.ok, "{outcome:?}");
    assert!(
        outcome
            .lines
            .iter()
            .any(|l| l.contains("stellar-bridge-0.1.0.jar")),
        "{outcome:?}"
    );
    assert!(!mods_dir.join(deploy::MOD_NAME).exists());
    assert!(!fake.win64.join(adapter::DLL).exists());
    assert!(
        instance
            .join("minecraft/mods/stellar-bridge-0.1.0.jar")
            .is_file()
    );
    assert!(mods::ownership(&instance.join("minecraft/mods")).is_ok());
}

/// A viewer install is up to date as a viewer only: picking EVE offers the update that adds the game side.
#[test]
fn a_viewer_can_add_the_game_side() {
    let _language = language();
    let fake = fake();
    let mut app = start(&fake, false);
    for message in [
        Message::Role(Role::Viewer),
        Message::Start,
        Message::Continue,
        Message::Choose(Choice::NewInstance(0)),
        Message::ToConsent,
        Message::Consent(true),
        Message::Begin(Action::Install),
        Message::Rescan,
    ] {
        drive(&mut app, message);
    }
    assert!(app.outcome.is_none() && errors(&app).is_empty());
    assert_eq!(app.primary_action(), ("action_up_to_date", false));
    drive(&mut app, Message::Role(Role::Eve));
    assert_eq!(app.primary_action(), ("action_update", true));
}

/// A foreign xinput1_3.dll (UE4SS 2.x uses that name) shows on the detect page and blocks the EVE install there,
/// before the hash; a Flatpak instance's mods folder typed by hand is refused; toasts go after Colony's delays.
#[test]
fn foreign_game_files_flatpak_folders_and_toasts() {
    let _language = language();
    let fake = fake();
    write(&fake.win64.join("xinput1_3.dll"), "UE4SS 2.x");
    let mut app = start(&fake, false);
    assert_eq!(
        app.screen,
        Screen::Welcome,
        "a foreign file is not an install"
    );
    drive(&mut app, Message::Start);
    let found = app.found.as_ref().unwrap();
    assert!(!found.installed());
    assert!(matches!(found.pieces[..], [ref p] if matches!(p.ownership, Ownership::Foreign(_))));
    assert_eq!(app.game_blocker(), Some("blocker_game_files"));
    assert!(!app.can_continue());

    drive(&mut app, Message::Role(Role::Viewer));
    drive(&mut app, Message::Continue);
    assert_eq!(app.screen, Screen::Target);
    // A core error shows in the window's language, its English text in the log only.
    drive(&mut app, Message::Language(Lang::Fr));
    drive(
        &mut app,
        Message::ManualPath(fake.saves.display().to_string()),
    );
    drive(&mut app, Message::ToConsent);
    let pick = Refusal::new(Kind::PickMods, fake.saves.display());
    assert_eq!(errors(&app), [i18n::refusal(&pick)]);
    assert_ne!(errors(&app)[0], pick.to_string());
    drive(&mut app, Message::Language(Lang::En));
    let toast = app.toasts[0].id;
    drive(&mut app, Message::DismissToast(toast));
    let flatpak = fake.home.join(
        ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/A/minecraft/mods",
    );
    fs::create_dir_all(&flatpak).unwrap();
    drive(&mut app, Message::ManualPath(flatpak.display().to_string()));
    drive(&mut app, Message::ToConsent);
    assert_eq!(app.screen, Screen::Target);
    assert!(errors(&app)[0].contains("Flatpak"), "{:?}", errors(&app));

    let now = std::time::Instant::now();
    let toast = &app.toasts[0];
    assert!(!toast.expired(now));
    assert!(toast.expired(now + std::time::Duration::from_secs(10)));
    app.toasts[0].shown = now - std::time::Duration::from_secs(11);
    drive(&mut app, Message::Tick);
    assert!(app.toasts.is_empty());
}

/// A server operator: a new server folder with the EULA box ticked, an update check, a running server that stops the
/// install, and a dry run that writes nothing.
#[test]
fn a_server_operator_makes_a_server_folder() {
    let _language = language();
    let fake = fake();
    let mut app = start(&fake, false);
    drive(&mut app, Message::Role(Role::Server));
    drive(&mut app, Message::Start);
    assert_eq!(app.screen, Screen::Server);
    assert!(Screen::steps(app.role).contains(&Screen::Server));
    drive(&mut app, Message::Begin(Action::Server));
    assert_eq!(app.screen, Screen::Server, "nothing checked yet");
    drive(&mut app, Message::ServerCheck);
    assert_eq!(errors(&app), vec!["Type the server folder first."]);

    let dir = fake.home.join("srv/minecraft");
    fs::create_dir_all(dir.parent().unwrap()).unwrap();
    drive(&mut app, Message::ServerPath(dir.display().to_string()));
    drive(&mut app, Message::ServerCheck);
    let check = app.server.clone().unwrap().unwrap();
    assert_eq!(check.folder.layout, server::Layout::New);
    assert!(check.ready());
    let lines = sbmc_installer::app::server_lines(&check);
    assert!(lines.iter().any(|l| l.contains("Java 21")), "{lines:?}");
    assert!(lines.iter().any(|l| l.contains("no eula.txt")), "{lines:?}");
    drive(&mut app, Message::ServerEula(true));
    drive(&mut app, Message::Begin(Action::Server));
    let outcome = app.outcome.clone().unwrap();
    assert!(outcome.ok, "{:?}", outcome.lines);
    assert_eq!((app.screen, outcome.action), (Screen::Done, Action::Server));
    assert!(dir.join(server::LAUNCHER).is_file());
    assert!(dir.join("mods/stellar-bridge-0.1.0.jar").is_file());
    assert!(
        fs::read_to_string(dir.join("eula.txt"))
            .unwrap()
            .contains("eula=true")
    );
    // The page shows the folder as it is now.
    assert_eq!(
        app.server.clone().unwrap().unwrap().folder.layout,
        server::Layout::Ours
    );
    drive(&mut app, Message::Rescan);
    assert_eq!(app.screen, Screen::Server);

    // A server running in the folder stops the install before anything changes.
    let files = safety::tree(&dir).unwrap();
    write(&fake.proc_root.join("4242/comm"), "java\n");
    write(
        &fake.proc_root.join("4242/cmdline"),
        b"java\0-jar\0fabric-server-launch.jar\0nogui\0",
    );
    std::os::unix::fs::symlink(&dir, fake.proc_root.join("4242/cwd")).unwrap();
    drive(&mut app, Message::Begin(Action::Server));
    let outcome = app.outcome.clone().unwrap();
    assert!(!outcome.ok);
    assert!(
        outcome.lines[0].contains("Stop the Minecraft server"),
        "{:?}",
        outcome.lines
    );
    assert_eq!(safety::tree(&dir).unwrap(), files);
    fs::remove_dir_all(fake.proc_root.join("4242")).unwrap();

    // A dry run checks and shows, and writes nothing.
    let mut dry = start(&fake, true);
    let other = fake.home.join("srv/other");
    drive(&mut dry, Message::Role(Role::Server));
    drive(&mut dry, Message::Start);
    drive(&mut dry, Message::ServerPath(other.display().to_string()));
    drive(&mut dry, Message::ServerCheck);
    drive(&mut dry, Message::Begin(Action::Server));
    assert!(dry.outcome.as_ref().unwrap().dry_run);
    assert!(!other.exists());

    // The EULA box ticked for one folder does not carry over to another.
    let mut retyped = start(&fake, false);
    let (first, second) = (fake.home.join("srv/first"), fake.home.join("srv/second"));
    drive(&mut retyped, Message::Role(Role::Server));
    drive(&mut retyped, Message::Start);
    drive(
        &mut retyped,
        Message::ServerPath(first.display().to_string()),
    );
    drive(&mut retyped, Message::ServerCheck);
    drive(&mut retyped, Message::ServerEula(true));
    drive(
        &mut retyped,
        Message::ServerPath(second.display().to_string()),
    );
    drive(&mut retyped, Message::ServerCheck);
    assert!(!retyped.server_eula);
    drive(&mut retyped, Message::Begin(Action::Server));
    assert!(retyped.outcome.as_ref().unwrap().ok);
    assert!(second.join(server::LAUNCHER).is_file());
    assert!(!second.join("eula.txt").exists());
    assert!(!first.exists());

    // A check that ends after the folder was retyped does not arm the page for the old folder.
    let mut late = start(&fake, false);
    let stale = fake.home.join("srv/stale");
    drive(&mut late, Message::Role(Role::Server));
    drive(&mut late, Message::Start);
    drive(&mut late, Message::ServerPath(stale.display().to_string()));
    let job = late.step(Message::ServerCheck).unwrap();
    drive(
        &mut late,
        Message::ServerPath(fake.home.join("srv/fresh").display().to_string()),
    );
    let sent = RefCell::new(Vec::new());
    job.run(&|m| sent.borrow_mut().push(m));
    for message in sent.into_inner() {
        drive(&mut late, message);
    }
    assert!(late.server.is_none());
    assert!(!late.server_checking, "the page can check the new folder");
    drive(&mut late, Message::ServerEula(true));
    drive(&mut late, Message::Begin(Action::Server));
    assert_eq!(late.screen, Screen::Server);
    assert!(!stale.exists());
}

/// A local HTTP server for `files` (path, bytes); anything else is a 404. Its base URL.
fn serve(files: Vec<(&'static str, Vec<u8>)>) -> String {
    use std::io::{BufRead, BufReader, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    std::thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let mut reader = BufReader::new(&stream);
            let mut request = String::new();
            reader.read_line(&mut request).unwrap_or_default();
            let mut header = String::new();
            while reader.read_line(&mut header).is_ok_and(|n| n > 2) {
                header.clear();
            }
            let path = request.split(' ').nth(1).unwrap_or_default();
            let (status, body) = match files.iter().find(|(p, _)| *p == path) {
                Some((_, body)) => ("200 OK", body.as_slice()),
                None => ("404 Not Found", &b"not here"[..]),
            };
            let mut stream = &stream;
            let _ = write!(
                stream,
                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = stream.write_all(body);
        }
    });
    url
}

/// A zip like OodleUE's, with a stand-in library.
fn oodle_zip() -> Vec<u8> {
    use std::io::Write;
    let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default();
    writer
        .start_file("lib/liboodle-data-shared.so", options)
        .unwrap();
    writer.write_all(b"not oodle").unwrap();
    writer.finish().unwrap().into_inner()
}

/// A stand-in EveExtract: it stops as EveExtract does on an unusable Oodle while `<home>/oodle-fails` exists, else it
/// finishes without reading anything (as with a usmap of another game build).
fn sidecar_script(home: &Path) -> Vec<u8> {
    format!(
        "#!/bin/sh\nif [ -e '{0}/oodle-fails' ]; then echo 'eve-extract: oodle is not a loadable Oodle library' >&2; exit 3; fi\nprintf 'MOUNTED\\t1\\t1\\nDONE\\t0\\t0\\n'\n",
        home.display()
    )
    .into_bytes()
}

fn usmap(fake: &Fake) -> PathBuf {
    let path = fake.win64.join("ue4ss/Mappings.usmap");
    write(&path, [0xC4, 0x30, 3, 0, 0, 0]);
    path
}

/// The ready check's free disk, fixed: the test's own disk may have less than a build needs.
fn roomy(app: &mut App) {
    app.pack.ready.as_mut().unwrap().disk = Some(app::PACK_DISK);
}

#[test]
fn the_pack_builder_checks_asks_downloads_and_reports() {
    let _language = language();
    let fake = fake();
    let script = sidecar_script(&fake.home);
    let zip = oodle_zip();
    let base = serve(vec![
        ("/v0.0.0/eve-extract-linux-x64", script.clone()),
        ("/oodle/gcc-x64-release.zip", zip.clone()),
    ]);
    let mut ctx = ctx(&fake, false);
    ctx.sources.sidecar = Some(Remote {
        url: format!("{base}/v0.0.0/eve-extract-linux-x64"),
        size: None,
        sha256: safety::sha256_hex(&script),
    });
    ctx.sources.oodle = Remote {
        url: format!("{base}/oodle/gcc-x64-release.zip"),
        size: Some(zip.len() as u64),
        sha256: safety::sha256_hex(&zip),
    };
    let (mut app, job) = App::new(ctx, english());
    let sent = RefCell::new(Vec::new());
    job.run(&|m| sent.borrow_mut().push(m));
    for message in sent.into_inner() {
        drive(&mut app, message);
    }
    drive(&mut app, Message::Start);
    assert_eq!(app.screen, Screen::Detect);

    // No usmap yet: the ready check says so and stops there.
    drive(&mut app, Message::PackOpen);
    assert_eq!(app.screen, Screen::PackReady);
    let ready = app.pack.ready.as_ref().unwrap();
    assert!(ready.usmap.is_err());
    assert_eq!(
        ready
            .owned
            .as_ref()
            .map(|o| matches!(o, sbmc_installer::pack::owned::Source::Save(_))),
        Some(true),
        "the save is the owned items' source"
    );
    assert!(matches!(ready.sidecar, inputs::Sidecar::Download(_)));
    assert!(ready.oodle.is_none() && ready.running.is_empty());
    assert!(ready.blockers(false).contains(&"pack_row_usmap"));
    drive(&mut app, Message::PackNext);
    assert_eq!(app.screen, Screen::PackReady);

    // UE4SS wrote one; a small disk and a running game stop the build too.
    let usmap = usmap(&fake);
    write(&fake.proc_root.join("4242/comm"), "SB-Win64-Shippi\n");
    drive(&mut app, Message::PackOpen);
    assert_eq!(app.pack.ready.as_ref().unwrap().usmap, Ok(usmap.clone()));
    app.pack.ready.as_mut().unwrap().disk = Some(1 << 30);
    assert_eq!(
        app.pack.ready.as_ref().unwrap().blockers(false),
        ["pack_row_disk", "pack_row_games"]
    );
    fs::remove_dir_all(fake.proc_root.join("4242")).unwrap();
    drive(&mut app, Message::PackOpen);
    roomy(&mut app);
    assert!(app.pack.ready.as_ref().unwrap().blockers(false).is_empty());
    drive(&mut app, Message::PackEvery(true));
    drive(&mut app, Message::PackEvery(false));

    // The consent, then Oodle's: a file that is not the pinned zip is refused.
    drive(&mut app, Message::PackNext);
    assert_eq!(app.screen, Screen::PackConsent);
    drive(&mut app, Message::PackNext);
    assert_eq!(app.screen, Screen::PackConsent, "the box is not ticked");
    drive(&mut app, Message::PackConsent(true));
    drive(&mut app, Message::PackNext);
    assert_eq!(app.screen, Screen::PackOodle);
    drive(&mut app, Message::OodleFetch(false));
    assert_eq!(errors(&app), [i18n::t("oodle_pick")]);
    let elsewhere = fake.home.join("Downloads/gcc-x64-release.zip");
    write(&elsewhere, b"another zip");
    drive(
        &mut app,
        Message::OodleFile(elsewhere.display().to_string()),
    );
    drive(&mut app, Message::OodleFetch(false));
    assert_eq!(app.screen, Screen::PackOodle);
    assert_eq!(
        errors(&app).last().unwrap(),
        &i18n::refusal(&Refusal::new(Kind::DigestMismatch, elsewhere.display()))
    );

    // Download agreed: Oodle and EveExtract come from the (local) server, the build starts, and EveExtract stops on
    // its Oodle. The result says so, with the log's last lines; nothing was installed.
    write(&fake.home.join("oodle-fails"), "");
    let usmap_bytes = fs::read(&usmap).unwrap();
    drive(&mut app, Message::OodleFetch(true));
    assert_eq!(app.screen, Screen::PackDone);
    assert!(!app.busy);
    let result = app.pack.result.clone().unwrap();
    assert!(!result.ok);
    assert_eq!(result.lines[1], i18n::t("pack_unchanged"));
    assert!(
        result
            .tail
            .iter()
            .any(|l| l.contains("not a loadable Oodle library")),
        "{:?}",
        result.tail
    );
    let builder = app.ctx.data.join("builder");
    assert_eq!(
        fs::read(builder.join(inputs::SIDECAR)).unwrap(),
        script,
        "EveExtract kept in the builder cache"
    );
    assert!(
        builder.join("oodle/liboodle-data-shared.so").is_file()
            && builder.join("oodle.json").is_file()
    );
    assert_eq!(
        fs::read(builder.join("usmap/24463856.usmap")).unwrap(),
        usmap_bytes
    );
    assert_eq!(
        fs::read(&usmap).unwrap(),
        usmap_bytes,
        "UE4SS's usmap is only read"
    );
    let home = fake.home.join(".local/share/stellar-blade-minecraft");
    assert!(!home.join("avatar").exists() && !home.join("avatar.tmp").exists());
    assert!(!app.ctx.cache.join("pack-work").exists());

    // Again: Oodle is ready, so the consent leads straight to the build. EveExtract reads no item table now (a
    // usmap of another game build): the result says so and the cached copy goes.
    fs::remove_file(fake.home.join("oodle-fails")).unwrap();
    drive(&mut app, Message::Rescan);
    drive(&mut app, Message::PackOpen);
    roomy(&mut app);
    let ready = app.pack.ready.as_ref().unwrap();
    assert!(ready.oodle.is_some());
    assert_eq!(
        ready.sidecar,
        inputs::Sidecar::Found(builder.join(inputs::SIDECAR))
    );
    drive(&mut app, Message::PackNext);
    drive(&mut app, Message::PackConsent(true));
    drive(&mut app, Message::PackNext);
    assert_eq!(app.screen, Screen::PackDone);
    let result = app.pack.result.clone().unwrap();
    assert_eq!(
        result.lines[0],
        i18n::refusal(&Refusal::new(Kind::UsmapRejected, "24463856"))
    );
    assert!(!builder.join("usmap/24463856.usmap").exists());

    // While a build runs, Cancel raises the flag the build checks.
    app.busy = true;
    app.screen = Screen::PackWorking;
    drive(&mut app, Message::PackCancel);
    assert!(app.pack.cancel.load(std::sync::atomic::Ordering::Relaxed));
}

#[test]
fn a_dry_run_pack_walks_to_its_result_in_french_and_writes_nothing() {
    let _language = language();
    let fake = fake();
    usmap(&fake);
    let before = safety::tree(&fake.home).unwrap();
    let (mut app, job) = App::new(
        ctx(&fake, true),
        Prefs {
            language: Some("fr".into()),
            ..Prefs::default()
        },
    );
    let sent = RefCell::new(Vec::new());
    job.run(&|m| sent.borrow_mut().push(m));
    for message in sent.into_inner() {
        drive(&mut app, message);
    }
    drive(&mut app, Message::Start);
    drive(&mut app, Message::PackOpen);
    roomy(&mut app);
    // This build pins no EveExtract: the ready check says so.
    assert_eq!(
        app.pack.ready.as_ref().unwrap().blockers(false),
        ["err_no_sidecar"]
    );
    app.pack.ready.as_mut().unwrap().sidecar =
        inputs::Sidecar::Found(fake.home.join("eve-extract"));
    for message in [
        Message::PackNext,
        Message::PackConsent(true),
        Message::PackNext,
        Message::OodleFetch(true),
    ] {
        drive(&mut app, message);
    }
    assert_eq!(app.screen, Screen::PackDone);
    let result = app.pack.result.clone().unwrap();
    assert_eq!(result.lines, ["Simulation : rien n'a été écrit."]);
    drive(&mut app, Message::Back);
    i18n::set_lang(Lang::En);
    assert_eq!(safety::tree(&fake.home).unwrap(), before);
    assert!(!app.ctx.data.exists());
}
