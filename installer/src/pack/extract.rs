//! The EveExtract sidecar (`tools/eve-extract`): `eve-extract --paks <dir> --usmap <file> --oodle <file> --out <dir>
//! <mode> [paths...]`. It mounts the game's containers read-only, writes only under `--out` and prints one
//! tab-separated line per event: `MOUNTED`, `MODS`, `OK <item> <detail>` or `FAIL <item> <reason>` per item, `DONE`
//! last. Exit 0: the run finished, even when some items failed; 2: bad arguments; 3: an input is missing or unusable;
//! 1: it stopped. On 1 to 3 its stderr holds one `eve-extract: <reason>` line.

use anyhow::{Result, bail};
use std::ffi::OsString;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::Duration;

/// What one EveExtract run needs besides its mode and paths.
#[derive(Debug, Clone)]
pub struct Sidecar {
    pub program: PathBuf,
    pub paks: PathBuf,
    pub usmap: PathBuf,
    pub oodle: PathBuf,
    /// Where a single-file build would unpack a native library (`DOTNET_BUNDLE_EXTRACT_BASE_DIR`); made when missing.
    /// EveExtract carries none today. `None` leaves .NET's default (`$HOME/.net`, `%TEMP%\.net`).
    pub bundle: Option<PathBuf>,
}

/// Why a build stopped early on request.
#[derive(Debug)]
pub struct Cancelled;

impl std::fmt::Display for Cancelled {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("cancelled")
    }
}

impl std::error::Error for Cancelled {}

impl Sidecar {
    pub fn arguments(&self, out: &Path, mode: &str, paths: &[String]) -> Vec<OsString> {
        let mut args: Vec<OsString> = Vec::new();
        for (flag, value) in [
            ("--paks", &self.paks),
            ("--usmap", &self.usmap),
            ("--oodle", &self.oodle),
        ] {
            args.push(flag.into());
            args.push(value.into());
        }
        args.push("--out".into());
        args.push(out.into());
        args.push(mode.into());
        args.extend(paths.iter().map(OsString::from));
        args
    }

    /// Runs one mode over `paths`, every line to `line`.
    pub fn run(
        &self,
        out: &Path,
        mode: &str,
        paths: &[String],
        cancel: &AtomicBool,
        line: &mut dyn FnMut(&str),
    ) -> Result<()> {
        let mut command = Command::new(&self.program);
        command.args(self.arguments(out, mode, paths));
        if let Some(bundle) = &self.bundle {
            std::fs::create_dir_all(bundle)?;
            command.env("DOTNET_BUNDLE_EXTRACT_BASE_DIR", bundle);
        }
        let mut reason = String::new();
        let mut each = |text: &str| {
            // Failures and the mount summary go to the log; every line goes on to the caller.
            if ["FAIL\t", "MOUNTED\t", "MODS\t", "DONE\t"]
                .iter()
                .any(|k| text.starts_with(k))
            {
                crate::log::line(&format!("EveExtract {mode}: {}", text.replace('\t', " ")));
            }
            line(text)
        };
        let code = lines(command, cancel, &mut each, &mut |text| {
            if let Some(why) = text.strip_prefix("eve-extract: ") {
                reason = why.to_string();
            }
        })?;
        match code {
            0 => Ok(()),
            3 => bail!("EveExtract {mode}: an input is missing or unusable: {reason}"),
            code => bail!("EveExtract {mode} failed (exit {code}): {reason}"),
        }
    }
}

/// The item of an `OK` or `FAIL` line, `None` for any other line.
pub fn item(line: &str) -> Option<(bool, &str)> {
    let mut fields = line.split('\t');
    let ok = match fields.next()? {
        "OK" => true,
        "FAIL" => false,
        _ => return None,
    };
    Some((ok, fields.next()?))
}

/// Kills the child when dropped: a cancelled or failed build leaves no extractor behind.
struct Guard(Child);

impl Drop for Guard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Runs `command`, hands each stdout line to `line` as it comes, and returns the exit code. Stderr lines go to the log
/// and to `error`. When `cancel` is raised the child is killed and the result is [`Cancelled`].
pub fn lines(
    mut command: Command,
    cancel: &AtomicBool,
    line: &mut dyn FnMut(&str),
    error: &mut dyn FnMut(&str),
) -> Result<i32> {
    crate::platform::quiet(&mut command);
    let mut child = Guard(
        command
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?,
    );
    let (sender, receiver) = mpsc::channel();
    let streams = [
        child
            .0
            .stdout
            .take()
            .map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
        child
            .0
            .stderr
            .take()
            .map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
    ];
    for (index, stream) in streams.into_iter().enumerate() {
        let sender = sender.clone();
        if let Some(stream) = stream {
            std::thread::spawn(move || {
                for text in BufReader::new(stream).lines().map_while(Result::ok) {
                    if sender.send((index, text)).is_err() {
                        break;
                    }
                }
            });
        }
    }
    drop(sender);
    loop {
        if cancel.load(Ordering::Relaxed) {
            bail!(Cancelled);
        }
        match receiver.recv_timeout(Duration::from_millis(100)) {
            Ok((0, text)) => line(&text),
            Ok((_, text)) => {
                crate::log::line(&format!("EveExtract: {text}"));
                error(&text);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    let status = child.0.wait()?;
    Ok(status.code().unwrap_or(-1))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// Not a test: the stand-in child process the driver tests start (this test binary, filtered to this function).
    #[test]
    #[ignore]
    fn child() {
        if let Ok(mode) = std::env::var("SBMC_FAKE_SIDECAR") {
            println!("MOUNTED\t3\t10");
            println!("OK\ta\ta.json");
            println!("FAIL\tb\tno game file");
            eprintln!("eve-extract: a warning");
            if mode == "hang" {
                std::thread::sleep(Duration::from_secs(60));
            }
            std::process::exit(if mode == "missing" { 3 } else { 0 });
        }
    }

    fn command(mode: &str) -> Command {
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--ignored",
                "--exact",
                "--nocapture",
                "pack::extract::tests::child",
            ])
            .env("SBMC_FAKE_SIDECAR", mode);
        command
    }

    #[test]
    fn lines_stream_and_exit_codes_come_back() {
        let mut seen = Vec::new();
        let mut errors = Vec::new();
        let code = lines(
            command("ok"),
            &AtomicBool::new(false),
            &mut |l| seen.push(l.to_string()),
            &mut |l| errors.push(l.to_string()),
        )
        .unwrap();
        assert_eq!(code, 0);
        let items: Vec<_> = seen.iter().filter_map(|l| item(l)).collect();
        assert_eq!(items, [(true, "a"), (false, "b")], "{seen:?}");
        assert_eq!(errors, ["eve-extract: a warning"], "stderr is apart");
        assert_eq!(
            lines(
                command("missing"),
                &AtomicBool::new(false),
                &mut |_| {},
                &mut |_| {}
            )
            .unwrap(),
            3
        );
    }

    #[test]
    fn cancel_kills_the_child() {
        let cancel = Arc::new(AtomicBool::new(false));
        let raise = cancel.clone();
        let started = std::time::Instant::now();
        let error = lines(
            command("hang"),
            &cancel,
            &mut |l| {
                if l.starts_with("FAIL") {
                    raise.store(true, Ordering::Relaxed);
                }
            },
            &mut |_| {},
        )
        .unwrap_err();
        assert!(error.is::<Cancelled>());
        assert!(
            started.elapsed() < Duration::from_secs(30),
            "the child was killed, not awaited"
        );
    }

    #[test]
    fn arguments_follow_the_contract() {
        let sidecar = Sidecar {
            program: "EveExtract".into(),
            paks: "P".into(),
            usmap: "U".into(),
            oodle: "O".into(),
            bundle: None,
        };
        let args = sidecar.arguments(Path::new("W"), "json", &["a".into(), "b".into()]);
        let args: Vec<&str> = args.iter().map(|a| a.to_str().unwrap()).collect();
        assert_eq!(
            args,
            [
                "--paks", "P", "--usmap", "U", "--oodle", "O", "--out", "W", "json", "a", "b"
            ]
        );
    }
}
