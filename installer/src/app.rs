//! The window's state machine: welcome, detect (also the status page, with repair, roll back and uninstall), verify,
//! target, consent, install with its step list and log, done. [`App::step`] never blocks: detection, hashing and every
//! operation run as a [`Job`] on a worker thread that reports back through messages. The UI smoke test drives `step`
//! and runs each job in place on a fake tree.

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::Context as _;
use colony_ui::preferences::{Change, StandardPreferences};
use iced::{Subscription, Task, window};
use serde::{Deserialize, Serialize};

use crate::detect::{self, Override, Pack};
use crate::i18n::{self, Lang, t, t_fmt};
use crate::launcher::{self, Instance, Kind, Launcher};
use crate::ledger::{self, State, Status};
use crate::ops::{self, Piece, Step, Target};
use crate::payload::Payload;
use crate::safety::{self, Running};
use crate::{Kind as Refused, Ownership, Refusal, adapter, deploy, log, mods, platform};

/// What the window works with: where to look and where to write its own data.
#[derive(Clone)]
pub struct Ctx {
    pub home: PathBuf,
    /// `/proc` outside tests; Windows reads the live process list instead.
    pub proc_root: PathBuf,
    /// `~/.local/share/Colony/Stellar Bridge`: `state.json`, `previous/`, `save-backups/`.
    pub data: PathBuf,
    /// The log file, shown on the done page.
    pub log: Option<PathBuf>,
    /// `~/.config/Colony/Stellar Bridge/preferences.json`; `None` keeps preferences in memory.
    pub prefs_file: Option<PathBuf>,
    /// The supported executable: [`crate::pins::SB_EXE_SHA256`] outside tests.
    pub pin: String,
    pub payload: Option<Arc<Payload>>,
    /// Shows every plan and writes nothing.
    pub dry_run: bool,
}

impl Ctx {
    fn closed(&self, launchers: bool) -> impl Fn() -> anyhow::Result<()> + use<> {
        let proc_root = self.proc_root.clone();
        move || safety::closed_at(&proc_root, launchers)
    }
}

/// The window's own preferences file: colony-ui's standard ones and the language.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Prefs {
    #[serde(flatten)]
    pub standard: StandardPreferences,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
}

impl Default for Prefs {
    fn default() -> Self {
        Prefs {
            standard: StandardPreferences {
                selected_theme: "stellar_blade".into(),
                selected_variant: "eve".into(),
                ..StandardPreferences::default()
            },
            language: None,
        }
    }
}

impl Prefs {
    /// Never fails: a missing or unreadable file gives the defaults.
    pub fn load(path: &Path) -> Prefs {
        fs::read(path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Screen {
    Welcome,
    Detect,
    Verify,
    Target,
    Consent,
    Working,
    Done,
}

impl Screen {
    pub const WIZARD: [Screen; 7] = [
        Screen::Welcome,
        Screen::Detect,
        Screen::Verify,
        Screen::Target,
        Screen::Consent,
        Screen::Working,
        Screen::Done,
    ];
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Stellar Blade and Minecraft on this PC.
    Eve,
    /// Minecraft only: the JAR and Fabric API.
    Viewer,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// Also the update.
    Install,
    Repair,
    Rollback,
    Uninstall,
}

/// Where the Minecraft side goes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Choice {
    /// The target recorded in `state.json`.
    Current,
    /// A new dedicated instance in `Found::launchers[i]`.
    NewInstance(usize),
    /// `App::instances[i]`.
    Instance(usize),
    /// A `mods` folder typed by the user.
    Manual,
}

/// Everything detection found, read only.
#[derive(Debug, Clone, Default)]
pub struct Found {
    pub steam: Vec<detect::Steam>,
    pub game: Option<detect::Game>,
    pub ue4ss: Option<detect::Ue4ss>,
    pub dll_override: Option<Override>,
    pub launchers: Vec<Launcher>,
    pub instances: Vec<(Kind, Instance)>,
    pub state: Option<State>,
    /// What the status rows and the maintenance actions are about: the recorded target, else the game and an instance
    /// that holds bridge files.
    pub current: Option<Target>,
    pub pieces: Vec<Piece>,
    pub pieces_error: Option<String>,
    pub pack: Option<Pack>,
}

impl Found {
    /// Something of ours is installed, or a run was recorded: the maintenance actions apply.
    pub fn installed(&self) -> bool {
        self.pieces
            .iter()
            .any(|p| matches!(p.ownership, Ownership::Owned | Ownership::Modified(_)))
            || self
                .state
                .as_ref()
                .is_some_and(|s| s.status != Status::Removed)
    }

    /// `payload`'s release is recorded as installed and every piece is current: there is nothing to update.
    pub fn up_to_date(&self, payload: Option<&Payload>) -> bool {
        self.pieces_error.is_none()
            && !self.pieces.is_empty()
            && self.pieces.iter().all(|p| p.current)
            && self.state.as_ref().is_some_and(|s| {
                s.status == Status::Installed && payload.is_some_and(|p| p.release == s.release)
            })
    }
}

/// `error` in the window's language; its raw text goes to the log only.
fn localized(error: &anyhow::Error) -> String {
    log::line(&format!("Error detail: {error:#}"));
    i18n::error(error)
}

/// Reads everything the pages show. Nothing is written.
pub fn detect(ctx: &Ctx) -> Found {
    let steam = detect::steam(&ctx.home);
    let game = detect::game(&steam, &ctx.home);
    let ue4ss = game.as_ref().and_then(|g| detect::ue4ss(&g.win64));
    // Windows loads the adapter from the game folder: there is no override to look for.
    let dll_override = game
        .as_ref()
        .filter(|_| platform::PROTON)
        .map(|g| detect::dll_override(g.prefix.as_deref(), &g.launch_options));
    let launchers = launcher::prism(&ctx.home);
    let instances: Vec<(Kind, Instance)> = launchers
        .iter()
        .flat_map(|l| launcher::instances(l).into_iter().map(|i| (l.kind, i)))
        .collect();
    let state = ledger::load(&ctx.data.join(ledger::STATE));
    let current = state
        .as_ref()
        .and_then(|s| s.target.clone())
        .or_else(|| found_target(ctx, game.as_ref(), ue4ss.as_ref(), &instances));
    let (pieces, pieces_error) = match &current {
        Some(target) => {
            let env = ops::Env {
                data: ctx.data.clone(),
                closed: &|| Ok(()),
                pin: &ctx.pin,
                step: &|_| {},
            };
            let empty = Payload::default();
            match ops::verify(&env, target, ctx.payload.as_deref().unwrap_or(&empty)) {
                Ok(pieces) => (pieces, None),
                Err(error) => (Vec::new(), Some(localized(&error))),
            }
        }
        // Nothing installed yet: still show what already sits where the game side would go.
        None => match (&game, ue4ss.as_ref().and_then(|u| u.mods.as_ref())) {
            (Some(game), Some(mods)) => {
                let (mut pieces, mut error) = (Vec::new(), None);
                for (name, ownership) in [
                    (ops::LUA, deploy::ownership(mods)),
                    (ops::ADAPTER, adapter::ownership(&game.win64, &ctx.pin)),
                ] {
                    match ownership {
                        Ok(Ownership::Absent) => {}
                        Ok(ownership) => pieces.push(Piece {
                            name,
                            ownership,
                            current: false,
                        }),
                        Err(e) => {
                            error = Some(t_fmt(
                                "piece_refusal",
                                &[("piece", i18n::piece(name)), ("refusal", &localized(&e))],
                            ))
                        }
                    }
                }
                (pieces, error)
            }
            _ => (Vec::new(), None),
        },
    };
    let flatpak = current
        .as_ref()
        .is_some_and(|t| t.bridge.starts_with(detect::bridge_home(&ctx.home, true)));
    let pack = Some(detect::pack(
        &detect::bridge_home(&ctx.home, flatpak).join("avatar"),
    ));
    Found {
        steam,
        game,
        ue4ss,
        dll_override,
        launchers,
        instances,
        state,
        current,
        pieces,
        pieces_error,
        pack,
    }
}

fn game_side(game: &detect::Game, ue4ss: &detect::Ue4ss) -> Option<ops::Game> {
    Some(ops::Game {
        exe: game.exe.clone(),
        ue4ss: ue4ss.dir.clone(),
        mods: ue4ss.mods.clone()?,
        prefix: game.prefix.clone(),
        saves: game.saves.clone(),
    })
}

/// Without `state.json` (an install by the Python tools): the game side found, and the first instance whose `mods`
/// holds bridge files.
fn found_target(
    ctx: &Ctx,
    game: Option<&detect::Game>,
    ue4ss: Option<&detect::Ue4ss>,
    instances: &[(Kind, Instance)],
) -> Option<Target> {
    let (kind, instance) = instances.iter().find(|(_, i)| {
        fs::read_dir(i.mods()).is_ok_and(|entries| {
            entries.filter_map(Result::ok).any(|e| {
                let name = e.file_name().to_string_lossy().into_owned();
                name == mods::MANIFEST || mods::is_bridge_jar(&name)
            })
        })
    })?;
    Some(Target {
        game: game.zip(ue4ss).and_then(|(g, u)| game_side(g, u)),
        minecraft_mods: instance.mods(),
        bridge: detect::bridge_home(&ctx.home, *kind == Kind::PrismFlatpak).join("bridge"),
    })
}

#[derive(Debug, Clone)]
pub enum Modal {
    /// Confirm a maintenance action; the lines say what it will do.
    Confirm(Action, Vec<String>),
    /// Waits for these to close; re-checked every second.
    Games(Vec<Running>),
}

#[derive(Debug, Clone)]
pub struct Toast {
    pub id: u64,
    pub text: String,
    pub error: bool,
    pub shown: Instant,
}

impl Toast {
    /// Colony's delays: a success goes after 5 s, an error after 10 s.
    pub fn expired(&self, now: Instant) -> bool {
        now.duration_since(self.shown) >= Duration::from_secs(if self.error { 10 } else { 5 })
    }
}

/// The result shown on the done page.
#[derive(Debug, Clone)]
pub struct Outcome {
    pub action: Action,
    pub ok: bool,
    pub dry_run: bool,
    pub lines: Vec<String>,
}

#[derive(Debug, Clone)]
pub enum Message {
    TogglePreferences,
    Category(usize),
    ToggleSection(&'static str),
    Preference(Change),
    Language(Lang),
    DismissToast(u64),

    Start,
    Role(Role),
    Back,
    Rescan,
    Detected(Box<Found>),
    Continue,
    Hashed(f32),
    HashDone(Result<bool, String>),
    Choose(Choice),
    MultiMcPath(String),
    LoadMultiMc,
    ManualPath(String),
    ToConsent,
    Consent(bool),
    /// Install from the consent page, or open the confirm modal of a maintenance action.
    Begin(Action),
    RemoveFabric(bool),
    /// The confirm modal's button.
    Proceed,
    CancelModal,
    /// Every second while a toast shows or the games modal waits.
    Tick,
    Step(Step),
    Log(String),
    Finished(Result<Vec<String>, String>),
    Copy(String),
    CloseRequested(window::Id),
}

/// Blocking work, run off the UI thread by [`spawn`] and in place by the tests.
pub enum Job {
    Detect(Ctx),
    Hash { exe: PathBuf, pin: String },
    Run(Box<Work>),
}

/// An operation and everything it needs.
pub struct Work {
    pub ctx: Ctx,
    pub action: Action,
    pub target: Target,
    /// Create the dedicated instance in this launcher first.
    pub create: Option<Launcher>,
    pub remove_fabric: bool,
}

impl Job {
    pub fn run(self, send: &dyn Fn(Message)) {
        match self {
            Job::Detect(ctx) => send(Message::Detected(Box::new(detect(&ctx)))),
            Job::Hash { exe, pin } => {
                let total = fs::metadata(&exe).map_or(0, |m| m.len()).max(1);
                let mut shown = 0;
                let hashed = safety::sha256_file(&exe, &mut |done| {
                    let percent = done * 100 / total;
                    if percent > shown {
                        shown = percent;
                        send(Message::Hashed(percent as f32 / 100.0));
                    }
                });
                send(Message::HashDone(
                    hashed.map(|h| h == pin).map_err(|e| localized(&e.into())),
                ));
            }
            Job::Run(work) => {
                let Work {
                    ctx,
                    action,
                    target,
                    create,
                    remove_fabric,
                } = *work;
                let result = run(&ctx, action, target, create, remove_fabric, send);
                send(Message::Finished(result.map_err(|e| localized(&e))));
            }
        }
    }
}

fn run(
    ctx: &Ctx,
    action: Action,
    mut target: Target,
    create: Option<Launcher>,
    remove_fabric: bool,
    send: &dyn Fn(Message),
) -> anyhow::Result<Vec<String>> {
    let closed = ctx.closed(false);
    let env = ops::Env {
        data: ctx.data.clone(),
        closed: &closed,
        pin: &ctx.pin,
        step: &|step| send(Message::Step(step)),
    };
    let payload = || {
        ctx.payload
            .as_deref()
            .context(Refusal::new(Refused::NoPayload, ""))
    };
    match action {
        Action::Install => {
            let payload = payload()?;
            let mut created = None;
            if let Some(launcher) = &create {
                let args = if launcher.kind == Kind::PrismFlatpak {
                    launcher::bridge_jvm_args(&detect::bridge_home(&ctx.home, true))
                } else {
                    Vec::new()
                };
                // The launcher's own files change: Prism must be closed too.
                let instance = launcher::create_instance(
                    launcher,
                    launcher::INSTANCE_NAME,
                    &args,
                    &ctx.closed(true),
                )?;
                send(Message::Log(t_fmt(
                    "log_instance_created",
                    &[("path", &instance.dir.display().to_string())],
                )));
                target.minecraft_mods = instance.mods();
                created = Some(instance.dir);
            }
            let installed = ops::install(&env, &target, payload);
            if let Some(dir) = created {
                // Recorded whatever happened: the instance is the user's now and is never deleted.
                let path = ctx.data.join(ledger::STATE);
                fs::create_dir_all(&ctx.data)?;
                let mut state = ledger::load(&path).unwrap_or_default();
                state.created_instance = Some(dir);
                ledger::save(&path, &state)?;
            }
            let state = installed?;
            Ok(vec![t_fmt("done_release", &[("release", &state.release)])])
        }
        Action::Repair => {
            let repaired = ops::repair(&env, &target, payload()?)?;
            Ok(if repaired.is_empty() {
                vec![t("done_nothing_to_repair").to_string()]
            } else {
                repaired.iter().map(|p| p.display().to_string()).collect()
            })
        }
        Action::Rollback => {
            let state = ops::rollback(&env, &target)?;
            Ok(vec![t_fmt("done_release", &[("release", &state.release)])])
        }
        Action::Uninstall => {
            let refusals = ops::uninstall(&env, &target, remove_fabric)?;
            if refusals.is_empty() {
                return Ok(vec![t("done_removed_all").to_string()]);
            }
            let mut lines = vec![t("done_left_in_place").to_string()];
            for (name, refusal) in &refusals {
                log::line(&format!("Left in place: {name}: {refusal}"));
                lines.push(i18n::piece_refusal(name, refusal));
            }
            Ok(lines)
        }
    }
}

/// Runs `job` on a worker thread; its messages come back in order.
pub fn spawn(job: Job) -> Task<Message> {
    let (sender, receiver) = iced::futures::channel::mpsc::unbounded();
    std::thread::spawn(move || {
        job.run(&|message| {
            let _ = sender.unbounded_send(message);
        })
    });
    Task::run(receiver, |message| message)
}

pub struct App {
    pub ctx: Ctx,
    pub prefs: Prefs,
    pub preferences_open: bool,
    pub category: usize,
    pub expanded: HashSet<String>,

    pub screen: Screen,
    pub role: Role,
    /// The user picked the role: a rescan no longer replaces it with the recorded one.
    pub role_chosen: bool,
    pub found: Option<Found>,
    pub detecting: bool,
    pub hash_progress: Option<f32>,
    pub exe_ok: Option<bool>,
    /// Detected instances, then those of a MultiMC folder the user loaded.
    pub instances: Vec<(Kind, Instance)>,
    pub choice: Option<Choice>,
    pub multimc_path: String,
    pub manual_path: String,
    pub consent: bool,
    /// The consent page's list, and the target it was made for.
    pub plan: Option<(Target, Option<Launcher>, ops::Plan)>,
    pub remove_fabric: bool,
    pub modal: Option<Modal>,
    pub pending: Option<Action>,
    /// The action running or last run.
    pub action: Option<Action>,
    /// A job is writing: the window refuses to close.
    pub busy: bool,
    /// A close was asked for while busy: the window closes when the job finishes.
    pub close_pending: bool,
    pub steps: Vec<Step>,
    pub log: Vec<String>,
    pub outcome: Option<Outcome>,
    pub toasts: Vec<Toast>,
    next_toast: u64,
}

impl App {
    /// The window's first state, with detection already started.
    pub fn new(ctx: Ctx, prefs: Prefs) -> (App, Job) {
        prefs.standard.apply();
        i18n::set_lang(i18n::initial(prefs.language.as_deref()));
        if ctx.dry_run {
            log::line("Dry run: nothing will be written");
        }
        let job = Job::Detect(ctx.clone());
        let app = App {
            ctx,
            prefs,
            preferences_open: false,
            category: 0,
            expanded: HashSet::new(),
            screen: Screen::Welcome,
            role: Role::Eve,
            role_chosen: false,
            found: None,
            detecting: true,
            hash_progress: None,
            exe_ok: None,
            instances: Vec::new(),
            choice: None,
            multimc_path: String::new(),
            manual_path: String::new(),
            consent: false,
            plan: None,
            remove_fabric: false,
            modal: None,
            pending: None,
            action: None,
            busy: false,
            close_pending: false,
            steps: Vec::new(),
            log: Vec::new(),
            outcome: None,
            toasts: Vec::new(),
            next_toast: 0,
        };
        (app, job)
    }

    pub fn title(&self) -> String {
        if self.ctx.dry_run {
            format!("Stellar Bridge ({})", t("dry_run_badge"))
        } else {
            "Stellar Bridge".into()
        }
    }

    pub fn theme(&self) -> iced::Theme {
        let bg = colony_ui::active_palette().bg_primary;
        if bg.r * 0.299 + bg.g * 0.587 + bg.b * 0.114 > 0.5 {
            iced::Theme::Light
        } else {
            iced::Theme::Dark
        }
    }

    pub fn subscription(&self) -> Subscription<Message> {
        let close = window::close_requests().map(Message::CloseRequested);
        if matches!(self.modal, Some(Modal::Games(_))) || !self.toasts.is_empty() {
            let tick = iced::time::every(Duration::from_secs(1)).map(|_| Message::Tick);
            Subscription::batch([close, tick])
        } else {
            close
        }
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Copy(text) => {
                self.info(t("copied"));
                iced::clipboard::write(text)
            }
            Message::CloseRequested(_) => {
                if self.busy {
                    self.close_pending = true;
                    self.error(t("still_writing"));
                    Task::none()
                } else {
                    iced::exit()
                }
            }
            message => {
                let finished = matches!(message, Message::Finished(_));
                let job = self.step(message);
                if finished && self.close_pending {
                    return iced::exit();
                }
                job.map_or_else(Task::none, spawn)
            }
        }
    }

    fn toast(&mut self, text: String, error: bool) {
        self.next_toast += 1;
        self.toasts.push(Toast {
            id: self.next_toast,
            text,
            error,
            shown: Instant::now(),
        });
        if self.toasts.len() > 4 {
            self.toasts.remove(0);
        }
    }

    pub fn error(&mut self, text: impl Into<String>) {
        let text = text.into();
        log::line(&format!("Error: {text}"));
        self.toast(text, true);
    }

    fn info(&mut self, text: impl Into<String>) {
        self.toast(text.into(), false);
    }

    fn save_prefs(&mut self) {
        let Some(path) = &self.ctx.prefs_file else {
            return;
        };
        let saved = serde_json::to_string_pretty(&self.prefs)
            .map_err(anyhow::Error::from)
            .and_then(|text| Ok(fs::write(path, text + "\n")?));
        if let Err(error) = saved {
            self.error(t_fmt("prefs_not_saved", &[("error", &error.to_string())]));
        }
    }

    /// Why the Stellar Blade side cannot be installed, if it cannot.
    pub fn game_blocker(&self) -> Option<&'static str> {
        let found = self.found.as_ref()?;
        let Some(game) = &found.game else {
            return Some("blocker_no_game");
        };
        if game.updating {
            return Some("blocker_updating");
        }
        if !game.build_supported() {
            return Some("blocker_build");
        }
        match &found.ue4ss {
            None => return Some("blocker_no_ue4ss"),
            Some(ue4ss) if ue4ss.mods.is_none() => return Some("blocker_ue4ss_mods"),
            Some(_) => {}
        }
        // An install would refuse these anyway: say so before the hash and the target choice.
        found
            .pieces
            .iter()
            .any(|p| {
                matches!(p.name, ops::LUA | ops::ADAPTER)
                    && matches!(p.ownership, Ownership::Foreign(_) | Ownership::Modified(_))
            })
            .then_some("blocker_game_files")
    }

    /// The status page's primary button: its label key, and whether it starts the wizard. Up to date for the chosen
    /// role, it stays disabled; repair and uninstall remain beside it. A viewer who picks EVE can still add the game
    /// side.
    pub fn primary_action(&self) -> (&'static str, bool) {
        let found = self.found.as_ref();
        if !found.is_some_and(Found::installed) {
            ("action_install", self.can_continue())
        } else if found.is_some_and(|f| {
            f.up_to_date(self.ctx.payload.as_deref())
                && f.current
                    .as_ref()
                    .is_some_and(|t| t.game.is_some() == (self.role == Role::Eve))
        }) {
            ("action_up_to_date", false)
        } else {
            ("action_update", self.can_continue())
        }
    }

    pub fn can_continue(&self) -> bool {
        self.found.is_some()
            && self.ctx.payload.is_some()
            && (self.role == Role::Viewer || self.game_blocker().is_none())
    }

    /// The choices the target page offers, in order.
    pub fn choices(&self) -> Vec<Choice> {
        let Some(found) = &self.found else {
            return Vec::new();
        };
        let mut choices = Vec::new();
        if found.state.as_ref().is_some_and(|s| s.target.is_some()) {
            choices.push(Choice::Current);
        }
        for (i, launcher) in found.launchers.iter().enumerate() {
            if !launcher.instances.join(launcher::INSTANCE_NAME).exists() {
                choices.push(Choice::NewInstance(i));
            }
        }
        // A Flatpak Prism sees the bridge folders only through the dedicated instance's Java arguments.
        for (i, (kind, instance)) in self.instances.iter().enumerate() {
            if *kind != Kind::PrismFlatpak && instance.compatible() {
                choices.push(Choice::Instance(i));
            }
        }
        choices.push(Choice::Manual);
        choices
    }

    /// The target the current choice and role make, and the launcher to create the instance in.
    pub fn target(&self) -> Result<(Target, Option<Launcher>), String> {
        let found = self.found.as_ref().ok_or(t("detecting"))?;
        let choice = self.choice.ok_or(t("pick_target"))?;
        let native = || detect::bridge_home(&self.ctx.home, false).join("bridge");
        let (minecraft_mods, bridge, create) = match choice {
            Choice::Current => {
                let current = found
                    .state
                    .as_ref()
                    .and_then(|s| s.target.clone())
                    .ok_or(t("pick_target"))?;
                (current.minecraft_mods, current.bridge, None)
            }
            Choice::NewInstance(i) => {
                let launcher = found.launchers.get(i).ok_or(t("pick_target"))?;
                let flatpak = launcher.kind == Kind::PrismFlatpak;
                (
                    launcher
                        .instances
                        .join(launcher::INSTANCE_NAME)
                        .join("minecraft")
                        .join("mods"),
                    detect::bridge_home(&self.ctx.home, flatpak).join("bridge"),
                    Some(launcher.clone()),
                )
            }
            Choice::Instance(i) => {
                let (_, instance) = self.instances.get(i).ok_or(t("pick_target"))?;
                if !instance.mods().is_dir() {
                    return Err(t_fmt(
                        "mods_missing",
                        &[("path", &instance.mods().display().to_string())],
                    ));
                }
                (instance.mods(), native(), None)
            }
            Choice::Manual => {
                let mods = launcher::manual_mods(Path::new(self.manual_path.trim()))
                    .map_err(|e| localized(&e))?;
                // A Flatpak launcher cannot see the native bridge folder; only the dedicated instance carries the
                // Java arguments that point it at one it can see.
                if platform::canonicalize(&self.ctx.home.join(".var/app"))
                    .is_ok_and(|v| mods.starts_with(v))
                {
                    return Err(t("manual_flatpak").into());
                }
                (mods, native(), None)
            }
        };
        let game = match self.role {
            Role::Viewer => None,
            Role::Eve => {
                if self.exe_ok != Some(true) {
                    return Err(t("exe_not_verified").into());
                }
                let game = found.game.as_ref().ok_or(t("blocker_no_game"))?;
                let ue4ss = found.ue4ss.as_ref().ok_or(t("blocker_no_ue4ss"))?;
                Some(game_side(game, ue4ss).ok_or(t("blocker_ue4ss_mods"))?)
            }
        };
        Ok((
            Target {
                game,
                minecraft_mods,
                bridge,
            },
            create,
        ))
    }

    fn env<'a>(&'a self, closed: crate::Guard<'a>) -> ops::Env<'a> {
        ops::Env {
            data: self.ctx.data.clone(),
            closed,
            pin: &self.ctx.pin,
            step: &|_| {},
        }
    }

    /// Foreign or modified pieces at `target`, checked before the consent page so nothing (not even a new instance)
    /// is written when the install would refuse.
    fn refusals(&self, target: &Target) -> Vec<String> {
        let mut found = Vec::new();
        let mut add = |name: &str, ownership: anyhow::Result<Ownership>| match ownership {
            Ok(Ownership::Foreign(r) | Ownership::Modified(r)) => {
                log::line(&format!("Refused: {name}: {r}"));
                found.push(i18n::piece_refusal(name, &r))
            }
            Ok(_) => {}
            Err(error) => found.push(t_fmt(
                "piece_refusal",
                &[
                    ("piece", i18n::piece(name)),
                    ("refusal", &localized(&error)),
                ],
            )),
        };
        if target.minecraft_mods.is_dir() {
            add(ops::JAR, mods::ownership(&target.minecraft_mods));
        }
        if let Some(game) = &target.game {
            add(ops::LUA, deploy::ownership(&game.mods));
            let win64 = game.exe.parent().unwrap_or(Path::new(""));
            add(ops::ADAPTER, adapter::ownership(win64, &self.ctx.pin));
        }
        found
    }

    fn rescan(&mut self) -> Option<Job> {
        self.detecting = true;
        Some(Job::Detect(self.ctx.clone()))
    }

    fn previous_screen(&self) -> Screen {
        match self.screen {
            Screen::Detect => Screen::Welcome,
            Screen::Verify => Screen::Detect,
            Screen::Target if self.role == Role::Eve => Screen::Verify,
            Screen::Target => Screen::Detect,
            Screen::Consent => Screen::Target,
            other => other,
        }
    }

    /// What a maintenance action will do, for its confirm modal.
    fn confirm_lines(&mut self, action: Action) -> Result<Vec<String>, String> {
        let found = self.found.as_ref().ok_or(t("detecting"))?;
        let target = found.current.clone().ok_or(t("no_current_target"))?;
        let none = || Ok(());
        let env = self.env(&none);
        let show = |paths: Vec<PathBuf>| -> Vec<String> {
            paths.iter().map(|p| p.display().to_string()).collect()
        };
        match action {
            Action::Repair => {
                let payload = self.ctx.payload.as_deref().ok_or(t("no_payload"))?;
                let plan = ops::repair_plan(&env, &target, payload).map_err(|e| localized(&e))?;
                if plan.is_empty() {
                    return Err(t("done_nothing_to_repair").into());
                }
                Ok(show(plan.into_iter().map(|(path, _)| path).collect()))
            }
            Action::Rollback => {
                let previous = found
                    .state
                    .as_ref()
                    .and_then(|s| s.previous.clone())
                    .filter(|p| p.is_dir())
                    .ok_or(t("no_previous"))?;
                let release = previous.file_name().unwrap_or_default().to_string_lossy();
                let mut lines = vec![t_fmt("rollback_to", &[("release", &release)])];
                lines.extend(show(
                    ops::owned_paths(&env, &target).map_err(|e| localized(&e))?,
                ));
                Ok(lines)
            }
            Action::Uninstall => {
                // Fabric API stays unless the box is ticked.
                let removed = ops::owned_paths(&env, &target)
                    .map_err(|e| localized(&e))?
                    .into_iter()
                    .filter(|path| {
                        let name = path.file_name().unwrap_or_default().to_string_lossy();
                        self.remove_fabric
                            || !mods::is_fabric_api(name.trim_end_matches(mods::DISABLED))
                    })
                    .collect();
                let mut lines = show(removed);
                for piece in &found.pieces {
                    if let Ownership::Foreign(r) | Ownership::Modified(r) = &piece.ownership {
                        lines.push(t_fmt(
                            "left_in_place",
                            &[("what", &i18n::piece_refusal(piece.name, r))],
                        ));
                    }
                }
                Ok(lines)
            }
            Action::Install => Ok(Vec::new()),
        }
    }

    /// Whether the uninstall modal offers to remove Fabric API: only when this installer added it.
    pub fn fabric_listed(&self) -> bool {
        self.found
            .as_ref()
            .and_then(|f| f.current.as_ref())
            .and_then(|t| mods::manifest(&t.minecraft_mods).ok())
            .is_some_and(|m| m.files.keys().any(|n| mods::is_fabric_api(n)))
    }

    /// Checks the games, then starts `action` or waits for them in the games modal.
    fn launch(&mut self, action: Action) -> Option<Job> {
        let (target, create) = match action {
            Action::Install => match &self.plan {
                Some((target, create, _)) => (target.clone(), create.clone()),
                None => return None,
            },
            _ => match self.found.as_ref().and_then(|f| f.current.clone()) {
                Some(target) => (target, None),
                None => {
                    self.error(t("no_current_target"));
                    return None;
                }
            },
        };
        self.action = Some(action);
        if self.ctx.dry_run {
            self.modal = None;
            self.outcome = Some(Outcome {
                action,
                ok: true,
                dry_run: true,
                lines: vec![t("dry_run_nothing_written").to_string()],
            });
            self.screen = Screen::Done;
            return None;
        }
        match safety::running_at(&self.ctx.proc_root, create.is_some()) {
            Ok(running) if running.is_empty() => {}
            Ok(running) => {
                self.pending = Some(action);
                self.modal = Some(Modal::Games(running));
                return None;
            }
            Err(error) => {
                self.error(localized(&error));
                return None;
            }
        }
        self.modal = None;
        self.pending = None;
        self.busy = true;
        self.steps.clear();
        self.log.clear();
        self.outcome = None;
        self.screen = Screen::Working;
        log::line(&format!(
            "Starting {action:?} on {}",
            target.minecraft_mods.display()
        ));
        Some(Job::Run(Box::new(Work {
            ctx: self.ctx.clone(),
            action,
            target,
            create,
            remove_fabric: self.remove_fabric,
        })))
    }

    /// The whole state machine. Returns the blocking work to run, if any.
    pub fn step(&mut self, message: Message) -> Option<Job> {
        match message {
            Message::TogglePreferences => {
                self.preferences_open = !self.preferences_open;
                self.category = 0;
            }
            Message::Category(index) => self.category = index,
            Message::ToggleSection(key) => {
                if !self.expanded.remove(key) {
                    self.expanded.insert(key.to_string());
                }
            }
            Message::Preference(change) => {
                self.prefs.standard.update(change);
                if matches!(change, Change::Theme { .. }) {
                    self.info(t("theme_applied"));
                }
                self.save_prefs();
            }
            Message::Language(lang) => {
                i18n::set_lang(lang);
                self.prefs.language = Some(lang.tag().into());
                self.save_prefs();
            }
            Message::DismissToast(id) => self.toasts.retain(|t| t.id != id),

            Message::Start => self.screen = Screen::Detect,
            Message::Role(role) => {
                self.role = role;
                self.role_chosen = true;
                self.plan = None;
            }
            Message::Back => {
                if self.modal.is_none() && !self.busy {
                    self.screen = self.previous_screen();
                }
            }
            Message::Rescan => {
                if self.busy {
                    return None;
                }
                self.screen = Screen::Detect;
                self.outcome = None;
                return self.rescan();
            }
            Message::Detected(found) => {
                self.detecting = false;
                let found = *found;
                log::line(&format!(
                    "Detected: Stellar Blade {}, UE4SS {}, {} Minecraft instance(s), installed pieces: {}",
                    found.game.as_ref().map_or("absent", |g| g
                        .build_id
                        .as_deref()
                        .unwrap_or("unknown build")),
                    if found.ue4ss.is_some() {
                        "present"
                    } else {
                        "absent"
                    },
                    found.instances.len(),
                    found
                        .pieces
                        .iter()
                        .map(|p| format!("{} {:?}", p.name, p.ownership))
                        .collect::<Vec<_>>()
                        .join(", ")
                ));
                let loaded: Vec<_> = self
                    .instances
                    .iter()
                    .filter(|(kind, _)| *kind == Kind::MultiMc)
                    .cloned()
                    .collect();
                self.instances = found.instances.clone();
                self.instances.extend(loaded);
                let recorded = found.state.as_ref().and_then(|s| s.target.as_ref());
                if let Some(target) = recorded.filter(|_| !self.role_chosen) {
                    // An update keeps the recorded target and role, unless the user picked one.
                    self.role = if target.game.is_some() {
                        Role::Eve
                    } else {
                        Role::Viewer
                    };
                }
                let has_recorded = recorded.is_some();
                // A returning user lands on the status page, with its maintenance actions, not in the wizard.
                if self.found.is_none() && self.screen == Screen::Welcome && found.installed() {
                    self.screen = Screen::Detect;
                }
                self.found = Some(found);
                // A choice that no longer exists (the instance it would create now does) gives way.
                if self.choice.is_some_and(|c| !self.choices().contains(&c)) {
                    self.choice = None;
                }
                if has_recorded && self.choice.is_none() {
                    self.choice = Some(Choice::Current);
                }
            }
            Message::Continue => match self.screen {
                Screen::Detect if self.can_continue() => {
                    self.plan = None;
                    if self.role == Role::Eve {
                        self.screen = Screen::Verify;
                        if self.exe_ok.is_none() && self.hash_progress.is_none() {
                            let exe = self.found.as_ref()?.game.as_ref()?.exe.clone();
                            self.hash_progress = Some(0.0);
                            return Some(Job::Hash {
                                exe,
                                pin: self.ctx.pin.clone(),
                            });
                        }
                    } else {
                        self.screen = Screen::Target;
                    }
                }
                Screen::Verify if self.exe_ok == Some(true) || self.role == Role::Viewer => {
                    self.screen = Screen::Target
                }
                _ => {}
            },
            Message::Hashed(progress) => self.hash_progress = Some(progress),
            Message::HashDone(result) => {
                self.hash_progress = None;
                match result {
                    Ok(ok) => {
                        log::line(&format!("Executable matches the pin: {ok}"));
                        self.exe_ok = Some(ok);
                    }
                    Err(error) => self.error(error),
                }
            }
            Message::Choose(choice) => {
                self.choice = Some(choice);
                self.plan = None;
            }
            Message::MultiMcPath(path) => self.multimc_path = path,
            Message::LoadMultiMc => {
                let root = PathBuf::from(self.multimc_path.trim());
                match launcher::at(&root, Kind::MultiMc) {
                    Some(multimc) => {
                        self.instances.retain(|(kind, _)| *kind != Kind::MultiMc);
                        let loaded = launcher::instances(&multimc);
                        self.info(t_fmt(
                            "multimc_loaded",
                            &[("count", &loaded.len().to_string())],
                        ));
                        self.instances
                            .extend(loaded.into_iter().map(|i| (Kind::MultiMc, i)));
                    }
                    None => self.error(t("multimc_not_found")),
                }
            }
            Message::ManualPath(path) => {
                self.manual_path = path;
                self.choice = Some(Choice::Manual);
            }
            Message::ToConsent => match self.target() {
                Ok((target, create)) => {
                    let refusals = self.refusals(&target);
                    if !refusals.is_empty() {
                        self.error(refusals.join("\n"));
                        return None;
                    }
                    let Some(payload) = self.ctx.payload.clone() else {
                        self.error(t("no_payload"));
                        return None;
                    };
                    let none = || Ok(());
                    match ops::plan(&self.env(&none), &target, &payload) {
                        Ok(mut plan) => {
                            if let Some(launcher) = &create {
                                plan.writes
                                    .insert(0, launcher.instances.join(launcher::INSTANCE_NAME));
                            }
                            self.plan = Some((target, create, plan));
                            self.consent = false;
                            self.screen = Screen::Consent;
                        }
                        Err(error) => self.error(localized(&error)),
                    }
                }
                Err(error) => self.error(error),
            },
            Message::Consent(checked) => self.consent = checked,
            Message::Begin(Action::Install) => {
                if self.consent && self.screen == Screen::Consent {
                    return self.launch(Action::Install);
                }
            }
            Message::Begin(action) => {
                self.remove_fabric = false;
                match self.confirm_lines(action) {
                    Ok(lines) => self.modal = Some(Modal::Confirm(action, lines)),
                    Err(error) => self.error(error),
                }
            }
            Message::RemoveFabric(checked) => {
                self.remove_fabric = checked;
                if let Some(Modal::Confirm(Action::Uninstall, _)) = &self.modal {
                    match self.confirm_lines(Action::Uninstall) {
                        Ok(lines) => self.modal = Some(Modal::Confirm(Action::Uninstall, lines)),
                        Err(error) => self.error(error),
                    }
                }
            }
            Message::Proceed => match self.modal.clone() {
                Some(Modal::Confirm(action, _)) => return self.launch(action),
                // Unlocked once everything is closed; launch checks again.
                Some(Modal::Games(running)) if running.is_empty() => {
                    return self.pending.and_then(|action| self.launch(action));
                }
                _ => {}
            },
            Message::CancelModal => {
                self.modal = None;
                self.pending = None;
            }
            Message::Tick => {
                let now = Instant::now();
                self.toasts.retain(|toast| !toast.expired(now));
                if let Some(Modal::Games(_)) = &self.modal {
                    let launchers = self.plan.as_ref().is_some_and(|(_, c, _)| c.is_some())
                        && self.pending == Some(Action::Install);
                    match safety::running_at(&self.ctx.proc_root, launchers) {
                        Ok(running) => self.modal = Some(Modal::Games(running)),
                        Err(error) => self.error(localized(&error)),
                    }
                }
            }
            Message::Step(step) => {
                log::line(&format!("Step: {step:?}"));
                self.steps.push(step);
            }
            Message::Log(line) => {
                log::line(&line);
                self.log.push(line);
            }
            Message::Finished(result) => {
                self.busy = false;
                let action = self.action.unwrap_or(Action::Install);
                let (ok, lines) = match result {
                    Ok(lines) => (true, lines),
                    Err(error) => (false, error.lines().map(str::to_string).collect()),
                };
                for line in &lines {
                    log::line(line);
                }
                self.log.extend(lines.iter().cloned());
                if !ok {
                    self.error(lines.first().cloned().unwrap_or_default());
                }
                self.outcome = Some(Outcome {
                    action,
                    ok,
                    dry_run: false,
                    lines,
                });
                self.screen = Screen::Done;
                // The pages after this show what is on disk now.
                return self.rescan();
            }
            Message::Copy(_) | Message::CloseRequested(_) => {}
        }
        None
    }
}
