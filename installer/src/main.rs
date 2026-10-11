//! Process entry point: `--version`, `--licenses` and `--help` are answered before any window opens, and `--server`
//! works on a server folder without one (headless hosts); otherwise the log, the preferences and the data folder are
//! set up and the window runs, with `--dry-run` showing every plan and writing nothing.

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use anyhow::Context as _;
use colony_ui::paths;
use sbmc_installer::app::{self, App, Ctx, Prefs, ServerCheck};
use sbmc_installer::ledger::PROGRAM;
use sbmc_installer::pack::inputs::{Sources, sidecar_notices};
use sbmc_installer::payload::Payload;
use sbmc_installer::{Kind, Refusal, i18n, log, pins, platform, safety, server, ui};

/// The window's fonts come from colony-ui; their SIL OFL 1.1 texts travel with the binary.
const FONT_LICENSES: [(&str, &str); 3] = [
    (
        "JetBrainsMono Nerd Font",
        include_str!("../licenses/JetBrainsMonoNerdFont-OFL.txt"),
    ),
    (
        "OpenDyslexic",
        include_str!("../licenses/OpenDyslexic-OFL.txt"),
    ),
    (
        "Font Awesome 6 Free",
        include_str!("../licenses/FontAwesome-OFL.txt"),
    ),
];

/// The logo at 64 px as raw RGBA, rendered from `assets/logo.svg` (see the README). On Wayland the compositor takes the
/// icon from the `.desktop` file that matches the application id instead.
const ICON_RGBA: &[u8] = include_bytes!("../assets/icon-64.rgba");

fn window() -> iced::window::Settings {
    iced::window::Settings {
        size: iced::Size::new(1000.0, 720.0),
        icon: iced::window::icon::from_rgba(ICON_RGBA.to_vec(), 64, 64).ok(),
        // The basename of assets/stellar-bridge-installer.desktop.
        #[cfg(target_os = "linux")]
        platform_specific: iced::window::settings::PlatformSpecific {
            application_id: "stellar-bridge-installer".into(),
            ..Default::default()
        },
        ..Default::default()
    }
}

fn payload() -> Option<Payload> {
    match Payload::embedded() {
        Ok(payload) => payload,
        Err(error) => {
            eprintln!("The embedded bridge payload is damaged: {error:#}");
            std::process::exit(1);
        }
    }
}

/// `--server <dir>`: installs or updates the bridge in a server folder (made when missing or empty), or with
/// `uninstall` removes it, printing what it did in the user's language. Returns the exit code.
fn server_mode(dir: &Path, accept_eula: bool, uninstall: bool, dry_run: bool) -> i32 {
    let java = OsStr::new("java");
    let closed = || safety::server_closed_at(Path::new("/proc"), dir);
    let result = if dry_run {
        server::inspect(dir).map(|folder| {
            let java = server::java_version(java);
            let mut lines = app::server_lines(&ServerCheck { folder, java });
            lines.push(i18n::t("dry_run_nothing_written").to_string());
            lines
        })
    } else if uninstall {
        server::uninstall(dir, &closed).map(|()| vec![i18n::t("server_done_removed").to_string()])
    } else {
        payload()
            .context(Refusal::new(Kind::NoPayload, ""))
            .and_then(|payload| server::install(dir, &payload, accept_eula, java, &closed))
            .map(|outcome| app::server_done_lines(&outcome))
    };
    match result {
        Ok(lines) => {
            for line in lines {
                println!("{line}");
            }
            0
        }
        Err(error) => {
            let (shown, detail) = (i18n::error(&error), format!("{error:#}"));
            if detail != shown {
                log::line(&format!("Error detail: {detail}"));
            }
            eprintln!("{shown}");
            1
        }
    }
}

fn main() -> iced::Result {
    let version = env!("CARGO_PKG_VERSION");
    let log_path = paths::locate::cache_dir(PROGRAM).map(|d| d.join(log::NAME));
    let (mut dry_run, mut accept_eula, mut uninstall) = (false, false, false);
    let mut server_dir: Option<PathBuf> = None;
    let mut args = std::env::args_os().skip(1);
    while let Some(arg) = args.next() {
        match arg.to_string_lossy().as_ref() {
            "--version" | "-V" => {
                let sidecar = Sources::release()
                    .sidecar
                    .map_or("no EveExtract".into(), |s| {
                        format!("EveExtract {}", s.sha256)
                    });
                match payload() {
                    Some(p) => println!(
                        "stellar-bridge-installer {version} (bridge {}, Lua revision {}, {sidecar})",
                        p.release,
                        p.lua_revision.map_or("unknown".into(), |r| r.to_string())
                    ),
                    None => println!(
                        "stellar-bridge-installer {version} (no bridge payload, {sidecar})"
                    ),
                }
                return Ok(());
            }
            "--licenses" => {
                println!("stellar-bridge-installer is licensed under GPL-3.0-or-later.");
                for (name, text) in payload().map(|p| p.licenses).unwrap_or_default() {
                    println!("\n== {name} ==\n\n{}", String::from_utf8_lossy(&text));
                }
                for (name, text) in FONT_LICENSES {
                    println!("\n== {name} (embedded through colony-ui) ==\n\n{text}");
                }
                println!(
                    "\n== Fabric server launcher ==\n\n{}\n== Fabric Installer licence ==\n\n{}",
                    server::LAUNCHER_NOTICE,
                    server::LAUNCHER_LICENSE
                );
                if let Some(notices) = sidecar_notices() {
                    println!("\n== EveExtract (the pack builder, embedded here) ==\n\n{notices}");
                }
                return Ok(());
            }
            "--help" | "-h" => {
                println!(
                    "stellar-bridge-installer {version}\n\nInstalls, updates, repairs and removes the Stellar Blade x \
                     Minecraft bridge. With no option it opens its window.\n\n\
                     Options:\n      --dry-run       Open the window, show every plan, write nothing (with --server: \
                     show what the folder holds)\n      \
                     --server <DIR>  Without a window: install or update the bridge JAR and Fabric API in a Fabric \
                     server folder for Minecraft 1.21.1, or make one there when DIR is missing or empty\n      \
                     --accept-eula   With --server: write eula=true in a new eula.txt. Read Mojang's EULA first \
                     ({}): passing this option accepts it for that server\n      \
                     --uninstall     With --server: remove the bridge JAR; Fabric API and the server stay\n  \
                     -V, --version       Print the version, the bridge release and the EveExtract it carries\n      \
                     --licenses      Print the licences of the embedded pieces\n  -h, --help          Print this help\n\n\
                     The log goes to {} and to stderr (to stderr only in a dry run).",
                    pins::MINECRAFT_EULA,
                    log_path
                        .as_ref()
                        .map_or("(no cache folder)".into(), |p| p.display().to_string())
                );
                return Ok(());
            }
            "--dry-run" => dry_run = true,
            "--accept-eula" => accept_eula = true,
            "--uninstall" => uninstall = true,
            "--server" => match args.next() {
                Some(dir) => server_dir = Some(PathBuf::from(dir)),
                None => {
                    eprintln!("--server needs a folder; see --help.");
                    std::process::exit(2);
                }
            },
            other => {
                eprintln!("Unknown option {other}; see --help.");
                std::process::exit(2);
            }
        }
    }
    if server_dir.is_none() && (accept_eula || uninstall) {
        eprintln!("--accept-eula and --uninstall go with --server; see --help.");
        std::process::exit(2);
    }

    // A dry run creates no folder and writes no file: the log goes to stderr only, preferences stay in memory.
    let log = if dry_run {
        None
    } else {
        paths::cache_dir(PROGRAM)
            .ok()
            .and_then(|dir| log::open(&dir).ok())
    };
    log::line(&format!("stellar-bridge-installer {version}"));
    let (data, config) = if dry_run {
        (
            paths::locate::data_dir(PROGRAM),
            paths::locate::config_dir(PROGRAM),
        )
    } else {
        (paths::data_dir(PROGRAM), paths::config_dir(PROGRAM))
    };
    let (data, home) = match (data, platform::home()) {
        (Ok(data), Ok(home)) => (data, home),
        (Err(error), _) => {
            eprintln!("No data folder: {error}");
            std::process::exit(1);
        }
        (_, Err(error)) => {
            eprintln!("{error:#}");
            std::process::exit(1);
        }
    };
    let prefs_file = config.ok().map(|d| d.join("preferences.json"));
    let prefs = prefs_file.as_deref().map(Prefs::load).unwrap_or_default();
    if let Some(dir) = server_dir {
        i18n::set_lang(i18n::initial(prefs.language.as_deref()));
        std::process::exit(server_mode(&dir, accept_eula, uninstall, dry_run));
    }
    let prefs_file = prefs_file.filter(|_| !dry_run);
    // The pack build's work folder; made only when a build starts.
    let cache = paths::locate::cache_dir(PROGRAM).unwrap_or_else(|_| data.join("cache"));
    let ctx = Ctx {
        home,
        proc_root: PathBuf::from("/proc"),
        data,
        log,
        prefs_file,
        pin: pins::SB_EXE_SHA256.into(),
        payload: payload().map(Arc::new),
        dry_run,
        java: "java".into(),
        cache,
        sources: Sources::release(),
    };

    // A double-clicked exe on Windows got a console of its own: the window does not need it.
    platform::leave_own_console();
    colony_ui::fonts::BYTES
        .into_iter()
        .fold(
            iced::application(
                move || {
                    let (app, job) = App::new(ctx.clone(), prefs.clone());
                    (app, app::spawn(job))
                },
                App::update,
                ui::view,
            ),
            |application, font| application.font(font),
        )
        .title(App::title)
        .theme(App::theme)
        .subscription(App::subscription)
        .default_font(colony_ui::ui_font())
        .exit_on_close_request(false)
        .window(window())
        .run()
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_window_icon_matches_its_size() {
        assert!(super::window().icon.is_some());
    }
}
