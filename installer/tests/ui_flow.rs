//! The window's state machine on a fake home: every page of the wizard, a dedicated Prism instance, the games modal,
//! repair, update, roll back, uninstall, the close guard, and a dry run that leaves the tree byte for byte as it was.
//! Jobs run in place; every page is drawn after every message. Nothing outside the temporary folder is read or written.

use std::cell::RefCell;
use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use sbmc_installer::app::{Action, App, Choice, Ctx, Message, Modal, Prefs, Role, Screen};
use sbmc_installer::detect::Override;
use sbmc_installer::i18n::{self, Lang};
use sbmc_installer::ledger::{self, Status};
use sbmc_installer::ops::Step;
use sbmc_installer::payload::Payload;
use sbmc_installer::safety::{self, Running};
use sbmc_installer::{Kind, Ownership, Refusal, adapter, deploy, launcher, mods, ops, pins, ui};

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
    let bridge_lua = fake
        .win64
        .join("ue4ss/Mods")
        .join(deploy::MOD_NAME)
        .join("Scripts/bridge.lua");
    fs::remove_file(&bridge_lua).unwrap();
    drive(&mut app, Message::Rescan);
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
    app.ctx.payload = Some(payload(2));
    drive(&mut app, Message::Rescan);
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
