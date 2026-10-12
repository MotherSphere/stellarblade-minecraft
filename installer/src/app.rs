//! The window's state machine: welcome, detect (also the status page, with repair, roll back and uninstall), verify,
//! target, consent, install with its step list and log, done; for a server operator, the server folder page; and from
//! the status page, the avatar pack builder's pages: ready check, consent, Oodle, progress and result.
//! [`App::step`] never blocks: detection, hashing and every operation run as a [`Job`] on a worker thread that reports
//! back through messages. The UI smoke test drives `step` and runs each job in place on a fake tree.

use std::collections::HashSet;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime};

use anyhow::Context as _;
use colony_ui::preferences::{Change, StandardPreferences};
use iced::{Subscription, Task, window};
use serde::{Deserialize, Serialize};

use crate::detect::{self, Override, Pack};
use crate::i18n::{self, Lang, t, t_fmt};
use crate::launcher::{self, Instance, Kind, Launcher};
use crate::ledger::{self, State, Status};
use crate::ops::{self, Piece, Step, Target};
use crate::pack::inputs::{self, Sources};
use crate::pack::{extract, owned, run};
use crate::payload::Payload;
use crate::safety::{self, Running};
use crate::server::{self, Folder, Layout};
use crate::{Kind as Refused, Ownership, Refusal, adapter, deploy, log, mods, pins, platform};

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
    /// The Java a server folder's start scripts run: `java` outside tests.
    pub java: OsString,
    /// `~/.cache/Colony/Stellar Bridge`: the pack build's work folder goes there.
    pub cache: PathBuf,
    /// Where the pack builder's EveExtract and Oodle come from: [`Sources::release`] outside tests.
    pub sources: Sources,
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
    /// The server operator's page: the folder, what it holds, the EULA box.
    Server,
    /// The avatar pack builder, from the status page: what it needs, then its consent, Oodle's, the build, the result.
    PackReady,
    PackConsent,
    PackOodle,
    PackWorking,
    PackDone,
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
    pub const SERVER: [Screen; 4] = [
        Screen::Welcome,
        Screen::Server,
        Screen::Working,
        Screen::Done,
    ];
    pub const PACK: [Screen; 5] = [
        Screen::PackReady,
        Screen::PackConsent,
        Screen::PackOodle,
        Screen::PackWorking,
        Screen::PackDone,
    ];

    pub fn is_pack(self) -> bool {
        Self::PACK.contains(&self)
    }

    /// The pages the sidebar lists for `role`.
    pub fn steps(role: Role) -> &'static [Screen] {
        if role == Role::Server {
            &Self::SERVER
        } else {
            &Self::WIZARD
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Stellar Blade and Minecraft on this PC.
    Eve,
    /// Minecraft only: the JAR and Fabric API.
    Viewer,
    /// A Fabric server folder: the JAR and Fabric API in its `mods`, a new folder made when needed.
    Server,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// Also the update.
    Install,
    Repair,
    Rollback,
    Uninstall,
    /// Install or update in a server folder.
    Server,
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
    /// The folder holding the avatar pack the status row is about (`detect::bridge_home`).
    pub pack_home: PathBuf,
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
                    (ops::ADAPTER, adapter::ownership(&game.win64)),
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
    let pack_home = detect::bridge_home(&ctx.home, flatpak);
    let pack = Some(detect::pack(&pack_home.join("avatar")));
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
        pack_home,
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

/// A server folder and the Java its start scripts would run, as the server page shows them.
#[derive(Debug, Clone)]
pub struct ServerCheck {
    pub folder: Folder,
    pub java: Option<u32>,
}

impl ServerCheck {
    /// The folder can take the bridge and the Java runs the server.
    pub fn ready(&self) -> bool {
        self.java.is_some_and(|v| v >= pins::JAVA_MIN)
    }
}

/// What a server folder holds, in the window's language: shown on the server page and printed by `--server`.
pub fn server_lines(check: &ServerCheck) -> Vec<String> {
    let folder = &check.folder;
    let path = folder.dir.display().to_string();
    let mut lines = vec![match &folder.layout {
        Layout::New => t_fmt("server_new", &[("path", &path)]),
        Layout::Ours => t_fmt("server_ours", &[("path", &path)]),
        Layout::Existing {
            loader: Some(loader),
        } => t_fmt("server_existing", &[("path", &path), ("loader", loader)]),
        Layout::Existing { loader: None } => t_fmt("server_existing_unknown", &[("path", &path)]),
    }];
    lines.push(
        match &folder.bridge {
            Ownership::Owned => t("server_bridge_owned"),
            _ => t("server_bridge_absent"),
        }
        .to_string(),
    );
    lines.push(match check.java {
        Some(version) if version >= pins::JAVA_MIN => {
            t_fmt("server_java", &[("version", &version.to_string())])
        }
        found => i18n::refusal(&Refusal::new(
            Refused::JavaVersion,
            found.map_or("none".to_string(), |v| format!("Java {v}")),
        )),
    });
    lines.push(
        t(match folder.eula {
            Some(true) => "server_eula_true",
            Some(false) => "server_eula_false",
            None => "server_eula_missing",
        })
        .to_string(),
    );
    if folder.online_mode == Some(false) {
        lines.push(t("server_offline").to_string());
    }
    lines
}

/// What a server install did, in the window's language.
pub fn server_done_lines(outcome: &server::Outcome) -> Vec<String> {
    let path = outcome.before.dir.display().to_string();
    let mut lines = vec![if outcome.unchanged {
        t("server_done_current").to_string()
    } else {
        t_fmt(
            "server_done_installed",
            &[("path", &outcome.before.mods().display().to_string())],
        )
    }];
    if outcome.before.layout == Layout::New {
        lines.push(t_fmt("server_done_created", &[("path", &path)]));
    }
    let accepted = outcome.eula_written || outcome.before.eula == Some(true);
    lines.push(if accepted {
        t("server_done_start").to_string()
    } else {
        t_fmt(
            "server_done_eula_todo",
            &[("path", &path), ("url", pins::MINECRAFT_EULA)],
        )
    });
    lines
}

/// Free disk a full pack build needs: the new pack, the installed one until the swap, and one batch of exports.
pub const PACK_DISK: u64 = 8_000_000_000;
/// Below this much free memory the bakes run on one worker, slowly.
pub const PACK_MEMORY: u64 = 2_000_000_000;

/// What the pack builder's ready check found. Reading it writes nothing.
#[derive(Debug, Clone)]
pub struct Ready {
    pub game: Option<detect::Game>,
    /// The folder holding `avatar/` and `bridge/`.
    pub home: PathBuf,
    /// The usmap the build would take (UE4SS's newest, else the cached copy for this build), or why there is none.
    pub usmap: Result<PathBuf, String>,
    /// Where the owned items would come from, and its time.
    pub owned: Option<owned::Source>,
    pub disk: Option<u64>,
    pub memory: Option<u64>,
    pub running: Vec<Running>,
    /// Whether this installer carries EveExtract (a build without `SBMC_EVE_EXTRACT` does not).
    pub sidecar: bool,
    pub oodle: Option<inputs::OodleRecord>,
}

impl Ready {
    /// What stops the build, as string keys; empty when it can start.
    pub fn blockers(&self, every: bool) -> Vec<&'static str> {
        let mut found = Vec::new();
        match &self.game {
            None => found.push("blocker_no_game"),
            Some(game) if game.updating => found.push("blocker_updating"),
            Some(_) => {}
        }
        if self.usmap.is_err() {
            found.push("pack_row_usmap");
        }
        if self.owned.is_none() && !every {
            found.push("pack_row_owned");
        }
        if self.disk.is_some_and(|d| d < PACK_DISK) {
            found.push("pack_row_disk");
        }
        if !self.running.is_empty() {
            found.push("pack_row_games");
        }
        if !self.sidecar {
            found.push("err_no_sidecar");
        }
        found
    }
}

/// The ready check: the game, the usmap, the owned items' source, disk, memory, the games, EveExtract and Oodle.
pub fn pack_ready(ctx: &Ctx, game: Option<detect::Game>, home: PathBuf) -> Ready {
    let cache = inputs::cache(&ctx.data);
    let usmap = match &game {
        None => Err(t("blocker_no_game").to_string()),
        Some(game) => inputs::usmap_in_game(&game.win64)
            .map(|found| inputs::check_game_usmap(&game.win64, &found).map(|()| found))
            .unwrap_or_else(|| {
                let cached = inputs::cached_usmap(&cache, game.build_id.as_deref());
                inputs::check_usmap(&cached)
                    .map(|()| cached)
                    .context(Refusal::new(Refused::NoUsmap, ""))
            })
            .map_err(|e| localized(&e)),
    };
    let list = home.join("bridge").join("eve-wardrobe.txt");
    let list_time = fs::metadata(&list)
        .and_then(|m| m.modified())
        .ok()
        .filter(|_| {
            fs::read(&list).is_ok_and(|b| owned::from_list(&String::from_utf8_lossy(&b)).is_some())
        });
    let save = game.as_ref().and_then(|g| {
        owned::newest_save(&platform::save_dirs(g.prefix.as_deref(), &ctx.home)).map(|(_, t)| t)
    });
    // As `owned::owned` chooses: the list when it is newer than the save.
    let owned = match (list_time, save) {
        (Some(list), save) if save.is_none_or(|s| list > s) => Some(owned::Source::List(list)),
        (_, Some(save)) => Some(owned::Source::Save(save)),
        _ => None,
    };
    let running = safety::running_at(&ctx.proc_root, false).unwrap_or_else(|error| {
        log::line(&format!("pack: the process list was not read: {error:#}"));
        vec![Running::StellarBlade, Running::Minecraft]
    });
    Ready {
        usmap,
        owned,
        disk: [home.as_path(), ctx.cache.as_path()]
            .iter()
            .filter_map(|p| platform::available_disk(p))
            .min(),
        memory: platform::available_memory(),
        running,
        sidecar: ctx.sources.sidecar.is_some(),
        oodle: inputs::oodle(&ctx.sources, &cache).map(|(_, record)| record),
        game,
        home,
    }
}

/// What a pack build ended with, in the window's language.
#[derive(Debug, Clone)]
pub struct PackResult {
    pub ok: bool,
    pub lines: Vec<String>,
    /// The build's warnings (materials or icons it skipped), as the log has them.
    pub warnings: Vec<String>,
    /// The log's last lines, kept when the build failed.
    pub tail: Vec<String>,
}

/// One pack build and what it needs.
pub struct PackWork {
    pub ctx: Ctx,
    pub game: detect::Game,
    pub home: PathBuf,
    pub every: bool,
    pub cancel: Arc<AtomicBool>,
}

/// `2026-10-11 14:05 UTC`.
pub fn utc(time: SystemTime) -> String {
    let secs = time
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    // Howard Hinnant's civil_from_days.
    let z = (secs / 86_400) as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    let minutes = secs % 86_400 / 60;
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02} UTC",
        minutes / 60,
        minutes % 60
    )
}

/// `3.1 GB`.
pub fn size(bytes: u64) -> String {
    format!("{:.1} GB", bytes as f64 / 1e9)
}

/// EveExtract unpacked and the usmap taken, then the build; what it did or why it stopped, in the window's language.
pub fn build_pack(work: &PackWork, send: &dyn Fn(Message)) -> PackResult {
    let ctx = &work.ctx;
    let cache = &inputs::cache(&ctx.data);
    // The bakes report from their worker threads: progress goes through a channel this thread forwards.
    let (sender, receiver) = std::sync::mpsc::channel();
    let build = move || -> anyhow::Result<run::Report> {
        let progress = |progress: run::Progress| {
            let _ = sender.send(progress);
        };
        let step = |item: &str, fraction: f32| {
            progress(run::Progress {
                step: run::Step::Inputs,
                item: item.to_string(),
                fraction,
            })
        };
        step("EveExtract", 0.0);
        let program = inputs::unpack_sidecar(&ctx.sources, cache)?;
        step("usmap", 0.0);
        let usmap = inputs::take_usmap(&work.game.win64, work.game.build_id.as_deref(), cache)?;
        let (oodle, _) = inputs::oodle(&ctx.sources, cache).context("Oodle is not ready")?;
        let build = run::Build {
            sidecar: extract::Sidecar {
                program,
                paks: work.game.dir.join("SB").join("Content").join("Paks"),
                usmap,
                oodle,
                bundle: Some(cache.join("bundle")),
            },
            home: work.home.clone(),
            work: ctx.cache.join("pack-work"),
            save_dirs: platform::save_dirs(work.game.prefix.as_deref(), &ctx.home),
            culture: i18n::lang().tag().into(),
            every: work.every,
            build_id: work.game.build_id.clone(),
        };
        let closed = ctx.closed(false);
        run::run(&build, &closed, &work.cancel, &progress)
    };
    let built = std::thread::scope(|scope| {
        let worker = scope.spawn(build);
        for progress in receiver {
            send(Message::PackProgress(Box::new(progress)));
        }
        worker
            .join()
            .unwrap_or_else(|_| Err(anyhow::anyhow!("the pack build panicked")))
    });
    match built {
        Ok(report) => {
            let checks = &report.checks;
            let mut lines = vec![t_fmt(
                "pack_result_meshes",
                &[
                    ("meshes", &checks.meshes.to_string()),
                    ("items", &checks.wardrobe_items.to_string()),
                    ("files", &checks.files.to_string()),
                    ("size", &size(checks.bytes)),
                ],
            )];
            lines.push(match &report.owned {
                None => t("pack_result_every").to_string(),
                Some((count, source)) => {
                    let (key, time) = match source {
                        owned::Source::List(time) => ("pack_result_owned_list", time),
                        owned::Source::Save(time) => ("pack_result_owned_save", time),
                    };
                    t_fmt(key, &[("count", &count.to_string()), ("date", &utc(*time))])
                }
            });
            lines.push(t_fmt(
                if report.incremental {
                    "pack_result_incremental"
                } else {
                    "pack_result_full"
                },
                &[
                    ("count", &report.meshes_exported.to_string()),
                    ("baked", &checks.baked.to_string()),
                ],
            ));
            let sections: usize = checks.untextured_sections.values().map(Vec::len).sum();
            for (count, key) in [
                (sections, "pack_result_untextured"),
                (checks.over_256_bones.len(), "pack_result_bones"),
                (report.missing.len(), "pack_result_missing"),
            ] {
                if count > 0 {
                    lines.push(t_fmt(key, &[("count", &count.to_string())]));
                }
            }
            lines.push(t("pack_result_note").to_string());
            for warning in &checks.warnings {
                log::line(&format!("pack warning: {warning}"));
            }
            PackResult {
                ok: true,
                lines,
                warnings: checks.warnings.clone(),
                tail: Vec::new(),
            }
        }
        Err(error) => {
            let headline = if error.is::<extract::Cancelled>() {
                t("pack_cancelled").to_string()
            } else {
                if error
                    .downcast_ref::<Refusal>()
                    .is_some_and(|r| r.kind == Refused::UsmapRejected)
                {
                    // That copy cannot read this game: the next build takes UE4SS's again, or asks for one.
                    let _ =
                        fs::remove_file(inputs::cached_usmap(cache, work.game.build_id.as_deref()));
                }
                if error.chain().any(|e| e.is::<Refusal>()) {
                    localized(&error)
                } else {
                    t("pack_failed").to_string()
                }
            };
            log::line(&format!("pack: {error:#}"));
            PackResult {
                ok: false,
                lines: vec![headline, t("pack_unchanged").to_string()],
                warnings: Vec::new(),
                tail: log::recent(),
            }
        }
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
    ServerPath(String),
    /// Reads the typed server folder and the Java version.
    ServerCheck,
    /// The folder text the check read, and what it found.
    ServerChecked(String, Box<Result<ServerCheck, String>>),
    /// The operator accepts Mojang's EULA for a folder without `eula.txt`.
    ServerEula(bool),

    /// The status page's pack button: the ready check starts.
    PackOpen,
    PackChecked(Box<Ready>),
    /// Every outfit, not only the owned ones.
    PackEvery(bool),
    PackConsent(bool),
    /// The ready check's or the consent page's Continue.
    PackNext,
    /// The Oodle zip the player has, as typed.
    OodleFile(String),
    /// Download Oodle (`true`), or take the typed zip.
    OodleFetch(bool),
    OodleProgress(f32),
    OodleDone(Result<(), String>),
    PackProgress(Box<run::Progress>),
    PackCancel,
    PackFinished(Box<PackResult>),
}

/// Blocking work, run off the UI thread by [`spawn`] and in place by the tests.
pub enum Job {
    Detect(Ctx),
    Hash {
        exe: PathBuf,
        pin: String,
    },
    Run(Box<Work>),
    /// Reads a server folder and runs `java -version`; writes nothing.
    ServerCheck {
        typed: String,
        java: OsString,
    },
    /// The pack builder's ready check; writes nothing.
    PackCheck {
        ctx: Ctx,
        game: Box<Option<detect::Game>>,
        home: PathBuf,
    },
    /// Oodle into the builder cache: downloaded (the player agreed), or from the zip they have.
    Oodle {
        ctx: Ctx,
        file: Option<PathBuf>,
        cancel: Arc<AtomicBool>,
    },
    Pack(Box<PackWork>),
}

/// An operation and everything it needs.
pub struct Work {
    pub ctx: Ctx,
    pub action: Action,
    pub target: Target,
    /// Create the dedicated instance in this launcher first.
    pub create: Option<Launcher>,
    pub remove_fabric: bool,
    /// For [`Action::Server`]: the server folder, and whether the operator accepted Mojang's EULA.
    pub server: Option<(PathBuf, bool)>,
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
                let result = match (work.action, &work.server) {
                    (Action::Server, Some((dir, eula))) => run_server(&work.ctx, dir, *eula),
                    _ => {
                        let Work {
                            ctx,
                            action,
                            target,
                            create,
                            remove_fabric,
                            ..
                        } = *work;
                        run(&ctx, action, target, create, remove_fabric, send)
                    }
                };
                send(Message::Finished(result.map_err(|e| localized(&e))));
            }
            Job::ServerCheck { typed, java } => {
                let checked = server::inspect(Path::new(&typed))
                    .map(|folder| ServerCheck {
                        folder,
                        java: server::java_version(&java),
                    })
                    .map_err(|e| localized(&e));
                send(Message::ServerChecked(typed, Box::new(checked)));
            }
            Job::PackCheck { ctx, game, home } => send(Message::PackChecked(Box::new(pack_ready(
                &ctx, *game, home,
            )))),
            Job::Oodle { ctx, file, cancel } => {
                let cache = inputs::cache(&ctx.data);
                let fetched = match file {
                    Some(zip) => inputs::oodle_from_file(&ctx.sources, &cache, &zip),
                    None => {
                        let mut shown = 0;
                        inputs::fetch_oodle(&ctx.sources, &cache, &cancel, &mut |done, total| {
                            let percent = done * 100 / total.max(1);
                            if percent > shown {
                                shown = percent;
                                send(Message::OodleProgress(percent as f32 / 100.0));
                            }
                        })
                    }
                };
                send(Message::OodleDone(
                    fetched.map(|_| ()).map_err(|e| localized(&e)),
                ));
            }
            Job::Pack(work) => send(Message::PackFinished(Box::new(build_pack(&work, send)))),
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
        // A Work for a server folder carries its folder and goes to run_server.
        Action::Server => anyhow::bail!("No server folder given"),
    }
}

/// Installs or updates the bridge in the server folder `dir`, refusing while a server runs there.
fn run_server(ctx: &Ctx, dir: &Path, accept_eula: bool) -> anyhow::Result<Vec<String>> {
    let payload = ctx
        .payload
        .as_deref()
        .context(Refusal::new(Refused::NoPayload, ""))?;
    let proc_root = ctx.proc_root.clone();
    let folder = dir.to_path_buf();
    let closed = move || safety::server_closed_at(&proc_root, &folder);
    let outcome = server::install(dir, payload, accept_eula, &ctx.java, &closed)?;
    Ok(server_done_lines(&outcome))
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

/// The avatar pack pages' state.
#[derive(Default)]
pub struct PackFlow {
    pub ready: Option<Ready>,
    pub checking: bool,
    pub every: bool,
    pub consent: bool,
    pub oodle_file: String,
    /// An Oodle download or check runs, this far.
    pub fetching: Option<f32>,
    pub cancel: Arc<AtomicBool>,
    pub progress: Option<run::Progress>,
    pub started: Option<Instant>,
    /// The build's time, once it ended.
    pub took: Option<Duration>,
    /// The log's last lines while the build runs.
    pub tail: Vec<String>,
    pub result: Option<PackResult>,
}

impl PackFlow {
    pub fn elapsed(&self) -> Duration {
        self.took
            .or_else(|| self.started.map(|s| s.elapsed()))
            .unwrap_or_default()
    }
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
    /// The server page: the typed folder, what it holds, and the EULA box.
    pub server_path: String,
    pub server: Option<Result<ServerCheck, String>>,
    pub server_checking: bool,
    pub server_eula: bool,
    pub pack: PackFlow,
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
            server_path: String::new(),
            server: None,
            server_checking: false,
            server_eula: false,
            pack: PackFlow::default(),
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
        let building = self.busy && self.screen == Screen::PackWorking;
        if matches!(self.modal, Some(Modal::Games(_))) || !self.toasts.is_empty() || building {
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
                    if self.screen == Screen::PackWorking {
                        // The build stops at its next check and leaves the installed pack as it was.
                        self.pack.cancel.store(true, Ordering::Relaxed);
                    }
                    self.error(t("still_writing"));
                    Task::none()
                } else {
                    iced::exit()
                }
            }
            message => {
                let finished = matches!(message, Message::Finished(_) | Message::PackFinished(_));
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
            Role::Viewer | Role::Server => None,
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
            add(ops::ADAPTER, adapter::ownership(win64));
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
            Screen::Server => Screen::Welcome,
            Screen::PackReady => Screen::Detect,
            Screen::PackConsent => Screen::PackReady,
            Screen::PackOodle => Screen::PackConsent,
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
                lines.extend(show(ops::owned_paths(&target).map_err(|e| localized(&e))?));
                Ok(lines)
            }
            Action::Uninstall => {
                // Fabric API stays unless the box is ticked.
                let removed = ops::owned_paths(&target)
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
            Action::Install | Action::Server => Ok(Vec::new()),
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

    /// The server page's install: the checked folder, refused while a server runs there (checked in the job).
    fn launch_server(&mut self) -> Option<Job> {
        let check = self.server.as_ref()?.as_ref().ok()?;
        if !check.ready() || self.ctx.payload.is_none() {
            return None;
        }
        let dir = check.folder.dir.clone();
        let eula = self.server_eula && check.folder.eula.is_none();
        self.action = Some(Action::Server);
        if self.ctx.dry_run {
            self.outcome = Some(Outcome {
                action: Action::Server,
                ok: true,
                dry_run: true,
                lines: vec![t("dry_run_nothing_written").to_string()],
            });
            self.screen = Screen::Done;
            return None;
        }
        self.busy = true;
        self.steps.clear();
        self.log.clear();
        self.outcome = None;
        self.screen = Screen::Working;
        log::line(&format!("Starting the server install in {}", dir.display()));
        Some(Job::Run(Box::new(Work {
            ctx: self.ctx.clone(),
            action: Action::Server,
            target: Target {
                game: None,
                minecraft_mods: dir.join("mods"),
                bridge: PathBuf::new(),
            },
            create: None,
            remove_fabric: false,
            server: Some((dir, eula)),
        })))
    }

    fn check_server(&mut self) -> Option<Job> {
        let typed = self.server_path.trim();
        if typed.is_empty() {
            self.error(t("server_pick"));
            return None;
        }
        self.server_checking = true;
        Some(Job::ServerCheck {
            typed: typed.to_string(),
            java: self.ctx.java.clone(),
        })
    }

    /// The pack build, once both consents are given and Oodle is ready; refused while a game runs.
    fn launch_pack(&mut self) -> Option<Job> {
        let ready = self.pack.ready.as_ref()?;
        let game = ready.game.clone()?;
        let home = ready.home.clone();
        self.pack.result = None;
        self.pack.took = None;
        if self.ctx.dry_run {
            self.pack.result = Some(PackResult {
                ok: true,
                lines: vec![t("dry_run_nothing_written").to_string()],
                warnings: Vec::new(),
                tail: Vec::new(),
            });
            self.screen = Screen::PackDone;
            return None;
        }
        match safety::running_at(&self.ctx.proc_root, false) {
            Ok(running) if running.is_empty() => {}
            Ok(running) => {
                self.pending = None;
                self.modal = Some(Modal::Games(running));
                return None;
            }
            Err(error) => {
                self.error(localized(&error));
                return None;
            }
        }
        self.busy = true;
        self.screen = Screen::PackWorking;
        self.pack.cancel = Arc::default();
        self.pack.started = Some(Instant::now());
        self.pack.progress = None;
        self.pack.tail.clear();
        log::line(&format!(
            "Starting the pack build in {} ({})",
            home.display(),
            if self.pack.every {
                "every outfit"
            } else {
                "owned outfits"
            }
        ));
        Some(Job::Pack(Box::new(PackWork {
            ctx: self.ctx.clone(),
            game,
            home,
            every: self.pack.every,
            cancel: self.pack.cancel.clone(),
        })))
    }

    /// Checks the games, then starts `action` or waits for them in the games modal.
    fn launch(&mut self, action: Action) -> Option<Job> {
        if action == Action::Server {
            return self.launch_server();
        }
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
            server: None,
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

            Message::Start => {
                self.screen = if self.role == Role::Server {
                    Screen::Server
                } else {
                    Screen::Detect
                }
            }
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
                self.outcome = None;
                if self.role == Role::Server {
                    self.screen = Screen::Server;
                    return self.check_server();
                }
                self.screen = Screen::Detect;
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
            Message::Begin(Action::Server) => {
                if self.screen == Screen::Server && !self.busy {
                    return self.launch(Action::Server);
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
                    if self.screen.is_pack() {
                        self.modal = None;
                        return self.launch_pack();
                    }
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
                if action == Action::Server {
                    return self.check_server();
                }
                return self.rescan();
            }
            Message::ServerPath(path) => {
                self.server_path = path;
                self.server = None;
                self.server_eula = false;
                // A check still running reads the old text and is dropped when it ends.
                self.server_checking = false;
            }
            Message::ServerCheck => return self.check_server(),
            // A check of a folder the operator has since retyped would arm the page for a folder it no longer
            // shows; only the check of the text in the field counts.
            Message::ServerChecked(typed, _) if typed != self.server_path.trim() => {}
            Message::ServerChecked(_, checked) => {
                self.server_checking = false;
                match *checked {
                    Ok(check) => {
                        for line in server_lines(&check) {
                            log::line(&format!("Server folder: {line}"));
                        }
                        self.server = Some(Ok(check));
                    }
                    Err(error) => {
                        self.error(error.clone());
                        self.server = Some(Err(error));
                    }
                }
            }
            Message::ServerEula(checked) => self.server_eula = checked,

            Message::PackOpen => {
                if self.busy || self.detecting {
                    return None;
                }
                let found = self.found.as_ref()?;
                self.screen = Screen::PackReady;
                self.pack.ready = None;
                self.pack.checking = true;
                return Some(Job::PackCheck {
                    ctx: self.ctx.clone(),
                    game: Box::new(found.game.clone()),
                    home: found.pack_home.clone(),
                });
            }
            Message::PackChecked(ready) => {
                self.pack.checking = false;
                log::line(&format!(
                    "Pack ready check: usmap {}, owned {:?}, disk {:?}, memory {:?}, running {:?}, EveExtract {:?}, Oodle {}",
                    if ready.usmap.is_ok() {
                        "found"
                    } else {
                        "missing"
                    },
                    ready.owned,
                    ready.disk,
                    ready.memory,
                    ready.running,
                    ready.sidecar,
                    if ready.oodle.is_some() {
                        "ready"
                    } else {
                        "not yet"
                    },
                ));
                self.pack.ready = Some(*ready);
            }
            Message::PackEvery(checked) => self.pack.every = checked,
            Message::PackConsent(checked) => self.pack.consent = checked,
            Message::PackNext => match self.screen {
                Screen::PackReady
                    if self
                        .pack
                        .ready
                        .as_ref()
                        .is_some_and(|r| r.blockers(self.pack.every).is_empty()) =>
                {
                    self.pack.consent = false;
                    self.screen = Screen::PackConsent;
                }
                Screen::PackConsent if self.pack.consent => {
                    if self.pack.ready.as_ref().is_some_and(|r| r.oodle.is_some()) {
                        return self.launch_pack();
                    }
                    self.screen = Screen::PackOodle;
                }
                _ => {}
            },
            Message::OodleFile(path) => self.pack.oodle_file = path,
            Message::OodleFetch(download) => {
                if self.pack.fetching.is_some() || self.screen != Screen::PackOodle {
                    return None;
                }
                if self.ctx.dry_run {
                    return self.launch_pack();
                }
                let file = (!download).then(|| PathBuf::from(self.pack.oodle_file.trim()));
                if file.as_ref().is_some_and(|f| f.as_os_str().is_empty()) {
                    self.error(t("oodle_pick"));
                    return None;
                }
                log::line(&match &file {
                    Some(zip) => format!("Oodle: the player's zip {}", zip.display()),
                    None => format!(
                        "Oodle: download agreed, from {}",
                        self.ctx.sources.oodle.url
                    ),
                });
                self.pack.fetching = Some(0.0);
                self.pack.cancel = Arc::default();
                return Some(Job::Oodle {
                    ctx: self.ctx.clone(),
                    file,
                    cancel: self.pack.cancel.clone(),
                });
            }
            Message::OodleProgress(fraction) => self.pack.fetching = Some(fraction),
            Message::OodleDone(result) => {
                self.pack.fetching = None;
                match result {
                    Ok(()) => {
                        self.info(t("oodle_ready"));
                        return self.launch_pack();
                    }
                    Err(error) => self.error(error),
                }
            }
            Message::PackProgress(progress) => {
                self.pack.progress = Some(*progress);
                self.pack.tail = log::recent();
            }
            Message::PackCancel => {
                if self.busy && self.screen == Screen::PackWorking {
                    log::line("pack: cancel asked");
                    self.pack.cancel.store(true, Ordering::Relaxed);
                }
            }
            Message::PackFinished(result) => {
                self.busy = false;
                self.pack.took = Some(self.pack.elapsed());
                for line in &result.lines {
                    log::line(line);
                }
                if !result.ok {
                    self.error(result.lines.first().cloned().unwrap_or_default());
                }
                self.pack.result = Some(*result);
                self.screen = Screen::PackDone;
                // The status page shows the new pack.
                return self.rescan();
            }
            Message::Copy(_) | Message::CloseRequested(_) => {}
        }
        None
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use crate::pack::fixture;
    use crate::pack::inputs::Embedded;

    /// The window's build on the synthetic game, through EveExtract's real process contract: a pinned sidecar the
    /// installer carries and unpacks (this test binary, behind a script), the usmap from UE4SS's folder, Oodle from a zip
    /// the player has. Then the same build again, cancelled before it starts.
    #[test]
    fn the_window_builds_the_pack_through_the_sidecar() {
        let root = tempfile::tempdir().unwrap();
        let base = platform::canonicalize(root.path()).unwrap();
        let game_data = base.join("game-data");
        fixture::game(&game_data);
        let dir = base.join("StellarBlade");
        let win64 = dir.join("SB/Binaries/Win64");
        fixture::paks(&dir.join("SB/Content/Paks"));
        fs::create_dir_all(win64.join("ue4ss")).unwrap();
        fs::write(win64.join("ue4ss/Mappings.usmap"), [0xC4, 0x30, 3, 0]).unwrap();
        let script: &'static [u8] = fixture::sidecar_script(&game_data).leak();
        let zip = inputs::tests::zip_with("lib/liboodle-data-shared.so", b"fake oodle");
        let sources = inputs::tests::sources(
            "http://127.0.0.1:9/never".into(),
            &zip,
            Some(Embedded {
                bytes: script,
                sha256: safety::sha256_hex(script),
            }),
        );
        fs::create_dir_all(base.join("proc")).unwrap();
        let ctx = Ctx {
            home: base.join("home"),
            proc_root: base.join("proc"),
            data: base.join("data"),
            log: None,
            prefs_file: None,
            pin: String::new(),
            payload: None,
            dry_run: false,
            java: "java".into(),
            cache: base.join("cache"),
            sources,
        };
        fs::write(base.join("oodle.zip"), &zip).unwrap();
        inputs::oodle_from_file(
            &ctx.sources,
            &inputs::cache(&ctx.data),
            &base.join("oodle.zip"),
        )
        .unwrap();
        let game = detect::Game {
            steam_root: base.join("steam"),
            steam_kind: platform::SteamKind::Native,
            library: base.clone(),
            exe: win64.join(crate::adapter::EXE),
            win64: win64.clone(),
            dir,
            build_id: Some(pins::SB_BUILD_ID.into()),
            updating: false,
            prefix: None,
            saves: None,
            proton: None,
            launch_options: Vec::new(),
        };
        let home = detect::bridge_home(&ctx.home, false);
        let ready = pack_ready(&ctx, Some(game.clone()), home.clone());
        assert!(ready.sidecar);
        assert!(ready.usmap.is_ok() && ready.oodle.is_some() && ready.running.is_empty());
        assert_eq!(ready.owned, None, "no list and no save");
        assert_eq!(
            ready
                .blockers(true)
                .into_iter()
                .filter(|b| *b != "pack_row_disk")
                .count(),
            0
        );

        let work = PackWork {
            ctx: ctx.clone(),
            game,
            home: home.clone(),
            every: true,
            cancel: Arc::default(),
        };
        let messages = std::sync::Mutex::new(Vec::new());
        let result = build_pack(&work, &|m| messages.lock().unwrap().push(m));
        assert!(result.ok, "{:?}\n{:?}", result.lines, result.tail);
        assert_eq!(detect::pack(&home.join("avatar")), Pack::Fresh);
        assert_eq!(result.lines.last().unwrap(), t("pack_result_note"));
        assert!(!result.warnings.is_empty());
        let steps: Vec<run::Step> = messages
            .lock()
            .unwrap()
            .iter()
            .filter_map(|m| match m {
                Message::PackProgress(p) => Some(p.step),
                _ => None,
            })
            .collect();
        for step in [
            run::Step::Inputs,
            run::Step::Tables,
            run::Step::Meshes,
            run::Step::Export,
            run::Step::Bake,
            run::Step::Install,
        ] {
            assert!(steps.contains(&step), "{step:?} in {steps:?}");
        }
        assert!(inputs::cached_usmap(&inputs::cache(&ctx.data), Some(pins::SB_BUILD_ID)).is_file());
        assert!(
            !ctx.cache.join("pack-work").exists(),
            "the work folder is gone"
        );

        // Cancelled: the installed pack stays.
        let before = safety::tree(&home.join("avatar")).unwrap();
        work.cancel.store(true, Ordering::Relaxed);
        let result = build_pack(&work, &|_| {});
        assert!(!result.ok);
        assert_eq!(result.lines, [t("pack_cancelled"), t("pack_unchanged")]);
        assert_eq!(safety::tree(&home.join("avatar")).unwrap(), before);
    }

    #[test]
    fn dates_and_sizes() {
        assert_eq!(utc(SystemTime::UNIX_EPOCH), "1970-01-01 00:00 UTC");
        let time = SystemTime::UNIX_EPOCH + Duration::from_secs(1_791_727_500);
        assert_eq!(utc(time), "2026-10-11 14:05 UTC");
        assert_eq!(size(3_120_000_000), "3.1 GB");
    }
}
