//! Process entry point. The window comes with the wizard; for now the binary answers `--version` and `--help` only.

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("--version" | "-V") => {
            println!("stellar-bridge-installer {}", env!("CARGO_PKG_VERSION"))
        }
        Some("--help" | "-h") => println!(
            "stellar-bridge-installer {}\n\nInstalls, updates and removes the Stellar Blade x Minecraft bridge.\n\n\
             Options:\n  -V, --version  Print the version\n  -h, --help     Print this help",
            env!("CARGO_PKG_VERSION")
        ),
        _ => {
            eprintln!("The installer window is not available yet; see installer/README.md.");
            std::process::exit(2);
        }
    }
}
