//! Process entry point. The window comes with the wizard; for now the binary answers `--version`, `--licenses` and
//! `--help` only.

use sbmc_installer::payload::Payload;

fn main() {
    let version = env!("CARGO_PKG_VERSION");
    let payload = || match Payload::embedded() {
        Ok(payload) => payload,
        Err(error) => {
            eprintln!("The embedded bridge payload is damaged: {error:#}");
            std::process::exit(1);
        }
    };
    match std::env::args().nth(1).as_deref() {
        Some("--version" | "-V") => match payload() {
            Some(p) => println!(
                "stellar-bridge-installer {version} (bridge {}, Lua revision {})",
                p.release,
                p.lua_revision.map_or("unknown".into(), |r| r.to_string())
            ),
            None => println!("stellar-bridge-installer {version} (no bridge payload)"),
        },
        Some("--licenses") => {
            println!("stellar-bridge-installer is licensed under GPL-3.0-or-later.");
            for (name, text) in payload().map(|p| p.licenses).unwrap_or_default() {
                println!("\n== {name} ==\n\n{}", String::from_utf8_lossy(&text));
            }
        }
        Some("--help" | "-h") => println!(
            "stellar-bridge-installer {version}\n\nInstalls, updates and removes the Stellar Blade x Minecraft bridge.\n\n\
             Options:\n  -V, --version  Print the version and the bridge release it carries\n      \
             --licenses Print the licences of the embedded pieces\n  -h, --help     Print this help"
        ),
        _ => {
            eprintln!("The installer window is not available yet; see installer/README.md.");
            std::process::exit(2);
        }
    }
}
