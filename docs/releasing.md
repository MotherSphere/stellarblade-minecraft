# Releases and CI

Releases go through [Release Please](https://github.com/googleapis/release-please): conventional commits, a generated release pull request with its changelog, then a merge that tags the release and builds and publishes it. Versions, tags, `CHANGELOG.md` and releases are never made by hand.

## From commits to a release

1. **Conventional commits on `main`.** Every commit that reaches the default branch, directly or through a merged pull request, uses a conventional subject (`feat:`, `fix:`, `docs:`...).
2. **The release pull request.** After each push, `.github/workflows/release-please.yml` opens or updates one pull request, `chore(main): release X.Y.Z`. It adds the new `CHANGELOG.md` section (each line links its commit) and sets the version in `minecraft/build.gradle`, `installer/Cargo.toml` and `installer/Cargo.lock`. Review it like any other pull request; leave the versions to it.
3. **Merge.** Merging it makes Release Please create the tag `vX.Y.Z` and the release as a draft. The workflow builds every asset from the tag through `.github/workflows/ci.yml` (all checks included), attaches them with `SHA256SUMS`, checks that each one is attached, and only then publishes it.

| Commit type | Changelog section | Version bump below 1.0 |
|---|---|---|
| `feat` | Features | minor (0.1.0 to 0.2.0) |
| `fix`, `perf`, `refactor`, `docs` | Fixes, Performance, Internals, Documentation | patch (0.1.0 to 0.1.1) |
| `!` after the type, or a `BREAKING CHANGE:` footer | ⚠ BREAKING CHANGES | minor while below 1.0 |
| `chore`, `test`, `ci`, `build`, `style` | hidden | none on their own |

A `Release-As: X.Y.Z` footer in a commit body forces the next version (for 1.0.0, for instance). The first release is v0.1.0, the version the files already carry (`.release-please-manifest.json` starts at `0.0.0` with `initial-version` 0.1.0), and its changelog covers the whole history. Merge commits keep each commit of a merged branch as its own changelog line; check the first release pull request's changelog to confirm it.

The project has a single version: the JAR, the installer and the release share it. The Lua mod keeps its own `Loaded revision N`, and the DLL carries no version resource. Inside the installer, the payload's release name is `<version>+<commit>`, which `stellar-bridge-installer --version` prints.

## Assets

| Asset | For |
|---|---|
| `stellar-bridge-installer-linux-x86_64` | Linux: Stellar Bridge, the installer, with the payload embedded (the Lua mod, the DLL, the JAR and Fabric API) and the Linux EveExtract, about 82 MB: everything except Oodle and the usmap the game makes. Needs glibc 2.35 or newer (Ubuntu 22.04, Debian 12 and newer). Make it executable with `chmod +x`. |
| `stellar-bridge-installer-windows-x86_64.exe` | Windows 11 x64 (Windows 10 22H2 as a courtesy): the same installer with the same payload and the Windows EveExtract, about 77 MB, for EVE players, viewers and server folders. It runs as the user who starts it, never elevated. It is not signed, so SmartScreen may warn before the first start. |
| `stellar-bridge-X.Y.Z.jar` | Every platform: the Fabric mod, for clients, servers and viewers. Fabric API 0.116.17+1.21.1 is a separate download. |
| `xinput1_3.dll` | Windows x64: the native adapter Stellar Blade loads, for a [manual install](setup.md#install). |
| `StellarCollisionProbe-X.Y.Z.zip` | The UE4SS Lua mod as one `StellarCollisionProbe/` folder. It stays inactive until `bridge_config.lua` exists; the installer or `tools/deploy.py` writes it (see [setup](setup.md#install)). |
| `eve-extract-linux-x64`, `eve-extract-win-x64.exe` | EveExtract, the avatar pack builder's sidecar ([tools/eve-extract](../tools/eve-extract/README.md)): self-contained .NET 10 programs that read the player's own game files for the installer's pack build. They carry no game content and no Oodle. Each installer already carries its OS's copy; these are for running EveExtract by hand. Make the Linux one executable with `chmod +x`. |
| `SHA256SUMS` | The SHA-256 of every other asset. |

Both installers embed one payload, staged once by the Linux job, and hold the same bytes as the separate JAR and DLL. Both are built with `SBMC_EVE_EXTRACT` naming the Linux job's `build/eve-extract` (the two sidecars, their `SHA256SUMS` and their licence texts in `NOTICES.txt`): each embeds its own OS's sidecar with that file's SHA-256 and the licence texts, which its `--licenses` prints, unpacks it into its builder cache at the first pack build and checks the digest. Oodle is the only file the installer downloads, after the player agrees. Server hosts and viewers on any OS can also take the JAR by hand. Playing as EVE on Windows is untested in game so far: it waits for a grouped test on a real Windows PC ([installer](../installer/README.md)).

The release DLL is built on GitHub's Windows runner with Visual Studio's ClangCL toolset, the compiler family of the local clang-cl/xwin build. `/Brepro` makes each toolchain reproducible, but the two toolchains do not produce the same bytes, so check a new toolchain's DLL in game before relying on it. The runner image label is pinned (`windows-2025`), so a move to a new image and its Visual Studio arrives as a visible commit; GitHub's weekly updates of that image can still bring minor toolset updates.

Check downloads with:

```sh
sha256sum -c --ignore-missing SHA256SUMS
```

The assets are not signed. The Project-Colony organisation key is limited to Project-Colony repositories, and this repository belongs to a user account. When a repository secret `STELLAR_SIGNING_KEY_PEM` holds an Ed25519 private key in PEM form, the publish job adds `SHA256SUMS.sig`; check it against the matching public key with `openssl pkeyutl -verify -pubin -inkey public.pem -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig`. No secret is needed otherwise, and none is stored in the repository.

## CI

`.github/workflows/ci.yml` runs on every pull request and every push to `main`:

- **Windows DLL** (`windows-2025`): builds `xinput1_3.dll` with `cmake -A x64 -T ClangCL` and runs the four portable native tests as Windows programs.
- **Windows bridge tests** (`windows-2025`, not for release builds): `python tools/check.py --only minecraft` (the Java suites on Windows' file system; capture-mode suites report `SKIP`) and `--only windows` (both Windows trees, every native check, the fake host and the overlay host, run directly); then the pose mapping between Java and `stellar_pose_stream_fixture.exe`, and `BridgeRuntimeTest`'s contended run beside `stellar_share_stress.exe`, a C reader and writer that shares files as Lua does.
- **Linux checks and installer** (`ubuntu-22.04`, with the CUE4Parse submodule and the .NET 10 SDK): runs `python3 tools/check.py` (Lua 5.4, Python, the host native tests, the Fabric build and the Java-Lua exchange, EveExtract's input checks with both sidecars published and the Linux one smoke-tested, then the installer with that DLL, the JAR and the Linux sidecar embedded), checks the sidecars against their `SHA256SUMS` and uploads them. There the `windows` component reports `SKIP windows`: it needs the clang-cl/xwin toolchain and Wine or Proton, and the Windows bridge job runs it on Windows instead. It then stages the payload with `tools/payload.py` (the Lua mod, the DLL, the JAR and Fabric API of this commit), uploads it for the next job, and runs `--version` on the installer, which loads the payload, checks every file of it and names the embedded sidecar's SHA-256, which must be the one in `SHA256SUMS`.
- **Windows installer** (`windows-2025`, after the Linux job): smoke-tests the Windows sidecar on Windows itself (help, licences, exit codes 2 and 3), then runs `cargo clippy`, `cargo test` and a `cargo check` with the declared minimum Rust (1.95) with that same payload and the Windows sidecar embedded, so the Windows tests install the real pieces on fake trees and check the sidecar's digest, then builds the exe and runs the same `--version` smoke test.

For a release, the same workflow first checks the tag against the version files, then builds both installers in release mode with the payload and each one's sidecar, runs `--version` on each and stages the assets (the two sidecars included); the publish job merges the Linux and Windows artifacts.

Actions are pinned to full commit SHAs. Each job gets only the token permissions it needs: read for builds, write for the release-please and publish jobs only.

## Repository settings and recovery

- Release Please opens its pull request with the workflow's `GITHUB_TOKEN`. Turn on Settings > Actions > General > Workflow permissions > **Allow GitHub Actions to create and approve pull requests**.
- GitHub runs no workflow for a pull request opened with `GITHUB_TOKEN`, so the release pull request shows no CI run. It changes only the versions and the changelog, and the release build runs every check before anything is published. Before merging, close and reopen the release pull request. The reopen is your own event, so `ci.yml` runs on it and catches a stale `Cargo.lock` or a new lint before the tag exists.
- The repository is public: releases are visible to everyone, and GitHub's standard hosted runners cost no Actions minutes there (a pull request or push runs three Windows jobs, a release build two).
- If a build or the publish step fails, re-run the failed jobs. If the run cannot be re-run, open Actions > Release Please > **Run workflow** and give the tag: it rebuilds from the tag and publishes the release if it is still a draft. A published release is never changed by the workflow. A tag whose code cannot build stays a draft. Fix it on `main`, and the next release replaces it.
