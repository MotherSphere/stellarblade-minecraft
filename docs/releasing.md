# Releases and CI

Releases go through [Release Please](https://github.com/googleapis/release-please): conventional commits, a generated release pull request with its changelog, then a merge that tags the release and builds and publishes it. Versions, tags, `CHANGELOG.md` and releases are never made by hand.

## From commits to a release

1. **Conventional commits on `minecraft-main-renderer`.** Every commit that reaches the default branch, directly or through a merged pull request, uses a conventional subject (`feat:`, `fix:`, `docs:`...).
2. **The release pull request.** After each push, `.github/workflows/release-please.yml` opens or updates one pull request, `chore(minecraft-main-renderer): release X.Y.Z`. It adds the new `CHANGELOG.md` section (each line links its commit) and sets the version in `minecraft/build.gradle`, `installer/Cargo.toml` and `installer/Cargo.lock`. Review it like any other pull request; leave the versions to it.
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
| `stellar-bridge-installer-linux-x86_64` | Linux: Stellar Bridge, the installer, with the payload embedded (the Lua mod, the DLL, the JAR and Fabric API). Needs glibc 2.35 or newer (Ubuntu 22.04, Debian 12 and newer). Make it executable with `chmod +x`. |
| `stellar-bridge-X.Y.Z.jar` | Every platform: the Fabric mod, for clients, servers and viewers. Fabric API 0.116.17+1.21.1 is a separate download. |
| `xinput1_3.dll` | Windows x64: the native adapter Stellar Blade loads, for a [manual install](setup.md#install). |
| `StellarCollisionProbe-X.Y.Z.zip` | The UE4SS Lua mod as one `StellarCollisionProbe/` folder. It stays inactive until `bridge_config.lua` exists; the installer or `tools/deploy.py` writes it (see [setup](setup.md#install)). |
| `SHA256SUMS` | The SHA-256 of every other asset. |

The installer's payload holds the same bytes as the separate JAR and DLL. There is no Windows installer yet: the installer builds only on Unix today, and Windows support comes with phase G3 ([installer](../installer/README.md)). Server hosts and viewers on any OS take the JAR. Playing needs Linux with Proton today; native Windows support is planned.

The release DLL is built on GitHub's Windows runner with Visual Studio's ClangCL toolset, the compiler family of the local clang-cl/xwin build. `/Brepro` makes each toolchain reproducible, but the two toolchains do not produce the same bytes, so check a new toolchain's DLL in game before relying on it. The runner image label is pinned (`windows-2025`), so a move to a new image and its Visual Studio arrives as a visible commit; GitHub's weekly updates of that image can still bring minor toolset updates.

Check downloads with:

```sh
sha256sum -c --ignore-missing SHA256SUMS
```

The assets are not signed. The Project-Colony organisation key is limited to Project-Colony repositories, and this repository belongs to a user account. When a repository secret `STELLAR_SIGNING_KEY_PEM` holds an Ed25519 private key in PEM form, the publish job adds `SHA256SUMS.sig`; check it against the matching public key with `openssl pkeyutl -verify -pubin -inkey public.pem -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig`. No secret is needed otherwise, and none is stored in the repository.

## CI

`.github/workflows/ci.yml` runs on every pull request and every push to `minecraft-main-renderer`:

- **Windows DLL** (`windows-2025`): builds `xinput1_3.dll` with `cmake -A x64 -T ClangCL` and runs the four portable native tests as Windows programs.
- **Linux checks and installer** (`ubuntu-22.04`): runs `python3 tools/check.py` (Lua 5.4, Python, the host native tests, the Fabric build and the Java-Lua exchange, then the installer with that DLL and the JAR embedded). The `windows` component stays local: it needs the clang-cl/xwin toolchain and Wine or Proton, and reports `SKIP windows` in CI.

For a release, the same workflow also checks the tag against the version files, builds the installer in release mode with its payload, runs `--version` against the payload and stages the assets.

Actions are pinned to full commit SHAs. Each job gets only the token permissions it needs: read for builds, write for the release-please and publish jobs only.

## Repository settings and recovery

- Release Please opens its pull request with the workflow's `GITHUB_TOKEN`. Turn on Settings > Actions > General > Workflow permissions > **Allow GitHub Actions to create and approve pull requests**.
- GitHub runs no workflow for a pull request opened with `GITHUB_TOKEN`, so the release pull request shows no CI run. It changes only the versions and the changelog, and the release build runs every check before anything is published. Before merging, close and reopen the release pull request. The reopen is your own event, so `ci.yml` runs on it and catches a stale `Cargo.lock` or a new lint before the tag exists.
- The repository is private: releases are visible to its collaborators only, and Actions minutes count against the account's private quota (Windows minutes count double).
- If a build or the publish step fails, re-run the failed jobs. If the run cannot be re-run, open Actions > Release Please > **Run workflow** and give the tag: it rebuilds from the tag and publishes the release if it is still a draft. A published release is never changed by the workflow. A tag whose code cannot build stays a draft. Fix it on `minecraft-main-renderer`, and the next release replaces it.
