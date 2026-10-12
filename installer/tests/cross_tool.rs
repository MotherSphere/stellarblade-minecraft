//! The installer and the Python tools share their ownership manifests: each removes what the other installed, and
//! each refuses what the other would refuse. Runs `tools/deploy.py` and `tools/input_proxy.py` on fake trees. The
//! Python tools are Linux developer tools: Windows players use the installer only.
#![cfg(unix)]

use sbmc_installer::{Ownership, adapter, deploy, safety};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

fn open() -> anyhow::Result<()> {
    Ok(())
}

fn tools() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("tools")
}

struct Lab {
    _root: tempfile::TempDir,
    base: PathBuf,
    source: PathBuf,
    mods: PathBuf,
    bridge: PathBuf,
}

/// A copy of `deploy.py` beside a fake Lua source (folder names that sort differently by component and by bytes, and
/// a non-ASCII file), a fake UE4SS Mods folder and a private bridge directory.
fn lab() -> Lab {
    let root = tempfile::tempdir().unwrap();
    let base = fs::canonicalize(root.path()).unwrap();
    fs::create_dir(base.join("tools")).unwrap();
    fs::copy(tools().join("deploy.py"), base.join("tools/deploy.py")).unwrap();
    let source = base.join("stellar/Mods").join(deploy::MOD_NAME);
    for (relative, text) in [
        ("Scripts/main.lua", "print('probe')\n"),
        ("Scripts/lib/inner/z.lua", "return 1\n"),
        ("Scripts/lib-extra/y.lua", "return 2\n"),
        ("Scripts/lib.lua", "return 3\n"),
        ("data/café.txt", "é\n"),
        ("enabled.txt", ""),
    ] {
        let path = source.join(relative);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }
    let mods = base.join("game/Mods");
    fs::create_dir_all(&mods).unwrap();
    fs::write(mods.join("mods.txt"), "OtherMod : 1\n").unwrap();
    let bridge = base.join("bridge 'quoted' é");
    fs::create_dir(&bridge).unwrap();
    fs::set_permissions(&bridge, fs::Permissions::from_mode(0o700)).unwrap();
    Lab {
        _root: root,
        base,
        source,
        mods,
        bridge,
    }
}

fn deploy_py(lab: &Lab, command: &str, bridge: bool) -> bool {
    let mut python = Command::new("python3");
    python
        .arg(lab.base.join("tools/deploy.py"))
        .args([command, "--mods-dir"])
        .arg(&lab.mods);
    if bridge {
        python.arg("--bridge-dir").arg(&lab.bridge);
    }
    let output = python.output().expect("python3 runs");
    assert_eq!(
        fs::read_to_string(lab.mods.join("mods.txt")).unwrap(),
        "OtherMod : 1\n"
    );
    if !output.status.success() {
        eprintln!(
            "deploy.py {command}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    output.status.success()
}

fn manifest(lab: &Lab) -> deploy::Manifest {
    serde_json::from_slice(
        &fs::read(lab.mods.join(deploy::MOD_NAME).join(deploy::MANIFEST)).unwrap(),
    )
    .unwrap()
}

#[test]
fn python_lua_install_is_removed_by_the_installer() {
    let lab = lab();
    assert!(deploy_py(&lab, "install", true));
    assert_eq!(deploy::ownership(&lab.mods).unwrap(), Ownership::Owned);
    deploy::remove(&lab.mods, &open).unwrap();
    assert_eq!(fs::read_dir(&lab.mods).unwrap().count(), 1);
}

#[test]
fn installer_lua_install_matches_python_and_is_removed_by_it() {
    let lab = lab();
    let tree = deploy::read_tree(&lab.source).unwrap();
    let installed = deploy::install(&lab.mods, &tree, Some(&lab.bridge), &open).unwrap();
    let ours = manifest(&lab);
    let config = fs::read(installed.join("Scripts/bridge_config.lua")).unwrap();
    assert!(deploy_py(&lab, "remove", false));
    assert!(!installed.exists());
    assert!(deploy_py(&lab, "install", true));
    assert_eq!(
        manifest(&lab),
        ours,
        "same manifest, directories in the same order"
    );
    assert_eq!(
        fs::read(installed.join("Scripts/bridge_config.lua")).unwrap(),
        config
    );
    deploy::remove(&lab.mods, &open).unwrap();
}

#[test]
fn both_tools_refuse_what_the_other_changed() {
    let lab = lab();
    let tree = deploy::read_tree(&lab.source).unwrap();
    let installed = deploy::install(&lab.mods, &tree, None, &open).unwrap();
    fs::write(installed.join("extra.lua"), "keep").unwrap();
    assert!(!deploy_py(&lab, "remove", false));
    assert!(
        !deploy_py(&lab, "install", false),
        "never over an existing folder"
    );
    fs::remove_file(installed.join("extra.lua")).unwrap();
    assert!(deploy_py(&lab, "remove", false));
    assert!(deploy_py(&lab, "install", false));
    fs::write(installed.join("Scripts/main.lua"), "modified").unwrap();
    assert!(matches!(
        deploy::ownership(&lab.mods).unwrap(),
        Ownership::Modified(_)
    ));
    assert!(deploy::remove(&lab.mods, &open).is_err());
    assert!(deploy::install(&lab.mods, &tree, None, &open).is_err());
    assert_eq!(
        fs::read_to_string(installed.join("Scripts/main.lua")).unwrap(),
        "modified"
    );
}

const INPUT_PROXY: &str = "
import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('input_proxy', sys.argv[1])
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)
proxy.SUPPORTED_HASH = sys.argv[2]
proxy.running_game = lambda: False
if sys.argv[3] == 'install':
    proxy.install(Path(sys.argv[4]), Path(sys.argv[5]), Path(sys.argv[6]))
else:
    proxy.remove(Path(sys.argv[4]))
";

/// `input_proxy.py` with the fake executable's hash as its pin, the game reported closed.
fn input_proxy(pin: &str, args: &[&Path]) -> bool {
    let output = Command::new("python3")
        .args(["-c", INPUT_PROXY])
        .arg(tools().join("input_proxy.py"))
        .arg(pin)
        .args(args)
        .output()
        .expect("python3 runs");
    if !output.status.success() {
        eprintln!(
            "input_proxy.py: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    output.status.success()
}

#[test]
fn adapter_installs_are_removed_by_the_other_tool() {
    let lab = lab();
    let game = lab.base.join("Win64");
    fs::create_dir(&game).unwrap();
    let exe = game.join(adapter::EXE);
    fs::write(&exe, "test executable").unwrap();
    fs::write(game.join("dwmapi.dll"), "preserve UE4SS").unwrap();
    let pin = safety::sha256_hex(b"test executable");
    let dll = lab.base.join(adapter::DLL);
    fs::write(&dll, adapter::test_dll()).unwrap();
    let install = Path::new("install");
    let remove = Path::new("remove");

    assert!(input_proxy(&pin, &[install, &exe, &dll, &lab.bridge]));
    assert_eq!(adapter::ownership(&game).unwrap(), Ownership::Owned);
    adapter::remove(&exe, &open).unwrap();
    assert_eq!(fs::read_dir(&game).unwrap().count(), 2);

    adapter::install(&exe, &adapter::test_dll(), &lab.bridge, &pin, &open).unwrap();
    let directory = fs::read(game.join(adapter::CONFIG)).unwrap();
    assert!(
        !input_proxy(&pin, &[install, &exe, &dll, &lab.bridge]),
        "never over an existing install"
    );
    fs::write(game.join(adapter::DLL), "changed").unwrap();
    assert!(!input_proxy(&pin, &[remove, &exe]));
    fs::write(game.join(adapter::DLL), adapter::test_dll()).unwrap();
    assert!(
        input_proxy(&"0".repeat(64), &[remove, &exe]),
        "installed for another pin: still ours after a Steam update"
    );
    assert_eq!(fs::read_dir(&game).unwrap().count(), 2);

    assert!(input_proxy(&pin, &[install, &exe, &dll, &lab.bridge]));
    assert_eq!(
        fs::read(game.join(adapter::CONFIG)).unwrap(),
        directory,
        "same directory file"
    );
    fs::write(game.join(adapter::CONFIG), "Z:/elsewhere\n").unwrap();
    assert!(adapter::remove(&exe, &open).is_err());
    assert_eq!(
        fs::read_to_string(game.join(adapter::CONFIG)).unwrap(),
        "Z:/elsewhere\n"
    );
}
