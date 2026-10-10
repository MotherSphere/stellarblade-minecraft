//! Process entry point: `--version`, `--licenses` and `--help` are answered before any window opens; otherwise the
//! log, the preferences and the data folder are set up and the window runs, with `--dry-run` showing every plan and
//! writing nothing.

use std::path::PathBuf;
use std::sync::Arc;

use colony_ui::paths;
use sbmc_installer::app::{self, App, Ctx, Prefs};
use sbmc_installer::ledger::PROGRAM;
use sbmc_installer::payload::Payload;
use sbmc_installer::{log, pins, platform, ui};

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

fn main() -> iced::Result {
    let version = env!("CARGO_PKG_VERSION");
    let log_path = paths::locate::cache_dir(PROGRAM).map(|d| d.join(log::NAME));
    let mut dry_run = false;
    for arg in std::env::args().skip(1) {
        match arg.as_str() {
            "--version" | "-V" => {
                match payload() {
                    Some(p) => println!(
                        "stellar-bridge-installer {version} (bridge {}, Lua revision {})",
                        p.release,
                        p.lua_revision.map_or("unknown".into(), |r| r.to_string())
                    ),
                    None => println!("stellar-bridge-installer {version} (no bridge payload)"),
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
                return Ok(());
            }
            "--help" | "-h" => {
                println!(
                    "stellar-bridge-installer {version}\n\nInstalls, updates, repairs and removes the Stellar Blade x \
                     Minecraft bridge. With no option it opens its window.\n\n\
                     Options:\n      --dry-run  Open the window, show every plan, write nothing\n  \
                     -V, --version  Print the version and the bridge release it carries\n      \
                     --licenses Print the licences of the embedded pieces\n  -h, --help     Print this help\n\n\
                     The log goes to {} and to stderr (to stderr only in a dry run).",
                    log_path
                        .as_ref()
                        .map_or("(no cache folder)".into(), |p| p.display().to_string())
                );
                return Ok(());
            }
            "--dry-run" => dry_run = true,
            other => {
                eprintln!("Unknown option {other}; see --help.");
                std::process::exit(2);
            }
        }
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
    let prefs_file = prefs_file.filter(|_| !dry_run);
    let ctx = Ctx {
        home,
        proc_root: PathBuf::from("/proc"),
        data,
        log,
        prefs_file,
        pin: pins::SB_EXE_SHA256.into(),
        payload: payload().map(Arc::new),
        dry_run,
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
