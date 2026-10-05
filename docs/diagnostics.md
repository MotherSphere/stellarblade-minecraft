# Diagnostic commands

The gameplay path is [`/stellarview play`](../README.md#start-a-session). The commands below isolate problems; they are not required for each session. F10 belongs to the existing Stellar Blade console. Shift may also trigger native dodge; prefer console equivalents when keeping EVE still.

## Stellar Blade

| Key | Console command | Action |
|---|---|---|
| F5 | `sbmc mirror` | Import the active Minecraft collision patch |
| F8 | `sbmc enter` | Enter the created lab once |
| F6 | `sbmc status` | Log context, position and timing counters |
| F11 | `sbmc restore` | Revoke input, stop captures, return EVE and clean up owned geometry |
| F4 | `sbmc framehd` | One 1280 × 720 paired still: BMP color, PNG mask, HDR depth |
| F2 | `sbmc depth` | Independent depth probe; not paired with another capture |
| F1 | `sbmc live` / `sbmc liveoff` | Legacy 320 × 180 animated diagnostic; capped at 60 seconds or 300 frames |
| F7 | `sbmc create` | Standalone collision fixture: floor, four walls, step and obstacle |
| F9 | `sbmc toggle` | Toggle the standalone fixture's obstacle |
| Shift+F7 | `sbmc texture` | Toggle a non-colliding stone-texture probe |
| Shift+F8 | `sbmc visuals` | Toggle native Minecraft terrain visuals inside Stellar Blade |

`sbmc posetest` records EVE's skeletal mesh components for up to 15 seconds or 900 frames: bone transforms relative to their parents (`SnapshotPose`, with a per-bone fallback), face morph weights and component world transforms. It writes private `eve-pose-probe.txt` (components, bones, method, timing) and `eve-pose-probe.bin` (per frame: frame number, seconds, then each component's world transform followed by its bones and weights, 10 floats per transform). It changes nothing in the scene and is input to the avatar experiment.

`sbmc frame` retains the 320 × 180 PNG still; `sbmc framehdpng` retains HD PNG color. `sbmc capture` is an independent color probe. `sbmc capturecleanup` retries failed capture cleanup.

`sbmc exportbmp` compares synchronous color exports. `sbmc exportasync` and `sbmc frameasync` share one asynchronous submission attempt per Lua lifetime and omit completion callbacks. The latter requires Minecraft's file-close watcher already running, then `/stellarpreview load` or `/stellarview load`. Pending generation files are retained until cleanup with both games closed. `sbmc exporttest` is disabled because its callback crashed the pinned loader.

Read `SB/Binaries/Win64/ue4ss/UE4SS.log`, prefix `[StellarCollisionProbe]`, and Minecraft's `logs/latest.log`. Successful object creation or texture assignment is not proof of valid pixels or collision.

## Minecraft views

- `/stellarpreview load` shows a still on a checkerboard; `/stellarpreview off` closes it.
- `/stellarview load` composes a still against world depth. It requires the matching entered terrain session and revision.
- `/stellarview live` consumes the legacy F1 producer. `/stellarview livehd` requests the continuous raw producer without input forwarding.
- `/stellarview status` reports image delivery separately from Minecraft FPS; see [performance measurements](#performance-measurements). `/stellarview off` restores the normal camera and stops forwarded controls.

Still captures expire 30 seconds after capture. Legacy live startup times out after five seconds; two seconds without a fresh generation stops an established view. A repeated frame does not renew freshness. Revision changes hide incompatible frames. Unsupported effects, session/world changes or invalid data stop composition.

A high Minecraft FPS counter with slow EVE motion indicates a different capture/transfer cadence; compare accepted frames over the same interval and framebuffer size. A small checkerboard preview does not show the full capture resolution. Hair noise can originate in native capture, not only the Minecraft compositor.

## Performance measurements

Each layer reports its own rate. Compare runs only at the same Minecraft framebuffer size, because capture cost grows with captured pixels.

- **Stellar Blade:** while a link is entered, `UE4SS.log` receives one `Cadence:` line per ten native seconds: native FPS, whether capture is live, completed generations and native frames from submission to observed completion. Idle lines give the same session's uncaptured rate.
- **Native adapter:** the private `eve-raw-timing.txt` keeps per-plane rows for the first 12 generations and rewrites whole-run summaries at most once per second. `COST 0`/`COST 2` are color/depth conversion times, `GAP` is the time between a generation's two plane arrivals: the second engine readback plus any image-queue wait, and `PERIOD` is the interval between publications. Values are count, mean and maximum microseconds.
- **Minecraft:** `/stellarview status`, also logged when a view stops, gives displayed frames, frames that arrived from the worker, frames replaced before the next Minecraft render or rejected as stale or mismatched, Minecraft world renders per second while the view is requested, and worker copy and texture-upload times. Upload time is the render-thread call cost, not GPU completion.

GPU contention between the games is outside these counters; sample it externally, for example with `nvidia-smi pmon`.

## Reverse terrain and texture probes

The reverse renderer displays Minecraft faces inside Stellar Blade and leaves its surroundings visible. It supports air and full cubes of stone, dirt, grass without snow, white wool and bedrock within the fixed patch. Unsupported blocks stop visual publication. Textures come from the active Minecraft resource manager; no game assets are packaged here. It does not import mobs, distant chunks, arbitrary models or fluids.

After `/stellar start`, F5 and F8, toggle it with `sbmc visuals`. Disable it before capturing EVE into Minecraft. Visual revisions are independent of collision revisions; changing wool to stone can update appearance without changing collision. F11 removes owned visuals and performs the guarded return.

The standalone `sbmc texture` probe requires `minecraft-stone.png` in the private bridge directory, extracted from `assets/minecraft/textures/block/stone.png` in the user's own Minecraft 1.21.1 client JAR. It creates one temporary non-colliding cube ahead of EVE. Its opaque display material does not establish Minecraft lighting compatibility.
