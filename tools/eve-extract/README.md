# EveExtract

EveExtract is the avatar pack builder's sidecar. It reads the player's own Stellar Blade containers, read-only, with [CUE4Parse](https://github.com/FabianFG/CUE4Parse), and writes what the builder bakes into the avatar pack: item tables, `.locres` files, SBAVMESH meshes, glTF exports, material JSON and PNG textures. The installer runs it as a child process and parses its output; players never need to run it by hand.

It never uses the network, writes only under `--out`, and takes Oodle and the usmap as files the caller passes. Nothing it reads or writes belongs in this repository: the game files, the usmap, Oodle and every output stay on the player's PC.

## Build

CUE4Parse is a git submodule pinned at `e4ea4ba8ec2b88d08b2066dfb8962863dccb36cc` (upstream `master` of 2026-10-05). Fetch it once:

```sh
git submodule update --init tools/eve-extract/CUE4Parse
```

Then, with the .NET 10 SDK, publish one self-contained file per OS:

```sh
dotnet publish tools/eve-extract/EveExtract.csproj -c Release -r linux-x64 -o build/eve-extract/linux-x64
dotnet publish tools/eve-extract/EveExtract.csproj -c Release -r win-x64 -o build/eve-extract/win-x64
```

`python tools/check.py --only eve-extract` does both, renames them to the release names `eve-extract-linux-x64` and `eve-extract-win-x64.exe` in `build/eve-extract/`, writes `build/eve-extract/SHA256SUMS` over them and `build/eve-extract/NOTICES.txt` (what `--licenses` prints, which the installers embed and print), runs the input checks in `tests/` and a smoke test of each build on synthetic inputs (with `--wine`, the Windows build and the input checks run under Wine too). The complete `python tools/check.py` runs it when `dotnet` is on `PATH`, else it reports `SKIP eve-extract`.

- **The CUE4Parse patch.** [`cue4parse.patch`](cue4parse.patch) holds EveExtract's changes to CUE4Parse. `ExportSession.ResolveOutputPath`, through which every export file is written, refuses a path that leaves the export folder (a mod's package path or object name holding `..`) before any folder or file is made, and keeps upstream's turning of every `/` into `\` (which on Linux writes files whose names hold backslashes) for Windows only. The rest keeps out of the file what the GPL cannot take or what has no licence to pass on: DotNetZip's `Utils/CRC32.cs` (MS-PL) is replaced by [`CRC32.cs`](CRC32.cs); the resources of other games (Ace Combat 7's key, PUBG's and Borderlands 4's name maps, ProSpi's decryptor DLL) and the prebuilt Detex, tegra_swizzle and crunch DLLs are not embedded; and PNG is encoded by ImageSharp, so SkiaSharp's native library, which carries the Adobe DNG SDK, is not published (JPEG, WebP and TGA output would need it; EveExtract writes PNG only). `EveExtract.csproj` applies it with `git apply` before CUE4Parse builds, once (a tree that already has it is left alone, and one that takes it neither way stops the build). Each changed part carries the notice that section 4(b) of the Apache License 2.0 asks for. The submodule is marked `ignore = dirty`, so the applied patch and CUE4Parse's build folders do not show as changes.
- **No natives.** `CUE4PARSE_SKIP_NATIVE=1` is set on both project references: CUE4Parse-Natives would bring only an optional Oodle link (never used: Oodle is the caller's file) and needs CMake.
- **Single file.** The build is self-contained, compressed and invariant-globalization. It carries no native library, so nothing is unpacked at the start; should one come back, it would be unpacked into `DOTNET_BUNDLE_EXTRACT_BASE_DIR` (which the callers set, to a folder that exists: the Windows host refuses one that does not), else `$HOME/.net` or `%TEMP%\.net`.
- **Licences.** EveExtract is GPL-3.0-or-later. `eve-extract --licenses` prints the repository's [third-party notices](../../THIRD-PARTY-NOTICES.md), CUE4Parse's NOTICE and licence and the .NET runtime's licence and notices, all embedded in the file.

## Run

```
eve-extract --paks <dir> --usmap <file> --oodle <file> --out <dir> <mode> <game path>...
```

| Argument | What |
|---|---|
| `--paks` | `SB/Content/Paks`. The base containers at its root are mounted first, then each container below it (`~mods`, `LogicMods`) one at a time in Unreal's priority order (`_P` adds 100, `_N_P` adds 100 × (N + 1)), so lookups see what the game sees |
| `--usmap` | The usmap UE4SS writes for this game build (see [the Lua mod's dump](../../protocol/README.md#mappings-for-the-pack-builder)). Without it, every package with unversioned properties fails |
| `--oodle` | The Oodle data library: `liboodle-data-shared.so` on Linux, `oodle-data-shared.dll` on Windows. The caller obtains it; EveExtract never downloads it (CUE4Parse's own download helper is never called) |
| `--out` | The work folder, made when the inputs pass. Use a fresh one per build: `avatar/meshes.txt` is appended to |

Modes and what they write under `--out`:

| Mode | Item | Writes |
|---|---|---|
| `json` | a package path | `json/<file name>.json`: the package's exports as CUE4Parse serializes them (tables, skeletons) |
| `raw` | a file path | `raw/<file name>`: the file as stored (`.locres`, configs) |
| `avatar` | a skeletal mesh package | `avatar/<mesh>.sbav` (SBAVMESH v2: bones, LOD0 vertices with four influences, indices, sections, morph deltas) and two kinds of lines appended to `avatar/meshes.txt`: `MESH <mesh>.sbav <game path> <container>`, then `MATERIAL <mesh>.sbav <slot> <material path>` per section |
| `export` | a mesh, skeleton, texture or material package | `export/<game folder>/<name>.<ext>`: glTF meshes with skeleton and morph targets, material JSON and PNG textures; a mesh also exports its materials and their textures |

Every run also writes `mods.txt`: a summary line (`base N containers, mods N, not mounted N, keys needed N`), then `NOT-MOUNTED <container>[ encrypted]`, `MOD <container> order <N> files <N>`, `OVERRIDE <asset> <mod>` (a mod replaces a base asset) and `CONFLICT <asset> <mod> <mod>...` (two mods provide it: the player's to resolve). Container paths are relative to `--paks`, with `/`.

Textures decode through CUE4Parse's managed decoders on both OSes (`UseAssetRipperTextureDecoder`): on Windows, BC7 would otherwise go through the native Detex. Compared with the private Linux exports this builder replaces (CUE4Parse's default decoders), BC4 textures now decode to the red channel only (green and blue are 0, where they used to repeat red) and BC5 normal maps rebuild Z with small rounding differences; read single-channel masks from red. Every other format tested decodes the same within 2 levels.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | The run finished. Items may have failed: count the `FAIL` lines |
| 1 | It stopped (mounting or an unexpected error); the reason is on stderr |
| 2 | Bad arguments: a missing or repeated option, an unknown option or mode (the development modes `inventory`, `probe` and `types` are gone), no game path, or a game path holding whitespace |
| 3 | An input is missing or unusable: no Paks folder, no `.utoc` or `.pak` at its root, no usmap file, one without the usmap magic (`C4 30`) or that CUE4Parse cannot read, no Oodle file, one that does not load, or no container that mounts |

On codes 1 to 3 stderr holds one line, `eve-extract: <reason>`, and nothing is written for codes 2 and 3 (not even `--out`). `--help` prints the usage (exit 0; with no argument at all, exit 2) and `--licenses` the licences.

### Output lines

Standard output is UTF-8, one line per event, ending in LF on every OS. Each line is fields separated by one tab; no field holds a tab or a line break (they become spaces). The first field is the keyword; parsers ignore lines with an unknown keyword.

| Line | When |
|---|---|
| `MOUNTED <containers> <files>` | After mounting: containers mounted, files they hold |
| `MODS <base> <mods> <not-mounted> <keys-needed> <overrides> <conflicts>` | After `mods.txt` is written; the counts of its lines |
| `OK <item> <detail>` | One item done |
| `FAIL <item> <reason>` | One item failed; the run goes on |
| `DONE <ok> <failed>` | Last line of a finished run (exit 0); the numbers of `OK` and `FAIL` lines |

The item is the game path as given, except in `export`, where each exported object reports as it completes, in completion order, as `<package path>.<object name>`: the requested ones and the materials and textures they pull in. A requested path that does not load fails under its own path before the export starts. The detail is the file written: `<file name>.json` (json), `<file name>` (raw), `<mesh>.sbav: <bones> bones, <vertices> vertices, <triangles> triangles, <sections> sections, <morphs> morphs (<deltas> deltas)` (avatar), or the written file names joined by `, ` (export). A reason is `no game file`, `no skeletal mesh`, `no mesh, skeleton, texture or material`, `written outside --out: <path>`, or `<exception type>: <message>`.

An illustrative run of `json` with one path that exists and one that does not:

```
MOUNTED	12	200000
MODS	12	0	0	0	0	0
OK	SB/Content/Local/Data/ItemTable.uasset	ItemTable.uasset.json
FAIL	SB/Content/Missing/Nothing	KeyNotFoundException: There is no game file with the path "SB/Content/Missing/Nothing"
DONE	1	1
```

A driver counts `OK` and `FAIL` lines against the paths it passed (or, in `export`, uses them as progress ticks until `DONE`), streams them as they come, and kills the process to cancel: every file is written whole, and a cancelled `--out` is the caller's to delete.

## In the installer and the release assets

Each installer carries the sidecar of its own OS and commit: `tools/check.py` and CI set `SBMC_EVE_EXTRACT` to the folder holding both files and their `SHA256SUMS` (`<sha256>  <name>` lines, the format of `sha256sum`, one per sidecar), and the installer's build script embeds its target's file with that line's SHA-256. The installer unpacks it into its builder cache and checks the digest before it runs it. Each release also carries `eve-extract-linux-x64` and `eve-extract-win-x64.exe`, listed in its `SHA256SUMS` ([releases](../../docs/releasing.md)), for running it by hand.
