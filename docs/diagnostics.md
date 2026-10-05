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

`sbmc frame` retains the 320 × 180 PNG still; `sbmc framehdpng` retains HD PNG color. `sbmc capture` is an independent color probe. `sbmc capturecleanup` retries failed capture cleanup.

`sbmc exportbmp` compares synchronous color exports. `sbmc exportasync` and `sbmc frameasync` share one asynchronous submission attempt per Lua lifetime and omit completion callbacks. The latter requires Minecraft's file-close watcher already running, then `/stellarpreview load` or `/stellarview load`. Pending generation files are retained until cleanup with both games closed. `sbmc exporttest` is disabled because its callback crashed the pinned loader.

Read `SB/Binaries/Win64/ue4ss/UE4SS.log`, prefix `[StellarCollisionProbe]`, and Minecraft's `logs/latest.log`. Successful object creation or texture assignment is not proof of valid pixels or collision.

## Minecraft views

- `/stellarpreview load` shows a still on a checkerboard; `/stellarpreview off` closes it.
- `/stellarview load` composes a still against world depth. It requires the matching entered terrain session and revision.
- `/stellarview live` consumes the legacy F1 producer. `/stellarview livehd` requests the continuous raw producer without input forwarding.
- `/stellarview status` reports accepted image cadence separately from Minecraft FPS. `/stellarview off` restores the normal camera and stops forwarded controls.

Still captures expire 30 seconds after capture. Legacy live startup times out after five seconds; two seconds without a fresh generation stops an established view. A repeated frame does not renew freshness. Revision changes hide incompatible frames. Unsupported effects, session/world changes or invalid data stop composition.

A high Minecraft FPS counter with slow EVE motion indicates a different capture/transfer cadence; compare accepted frames over the same interval and framebuffer size. A small checkerboard preview does not show the full capture resolution. Hair noise can originate in native capture, not only the Minecraft compositor.

## Reverse terrain and texture probes

The reverse renderer displays Minecraft faces inside Stellar Blade and leaves its surroundings visible. It supports air and full cubes of stone, dirt, grass without snow, white wool and bedrock within the fixed patch. Unsupported blocks stop visual publication. Textures come from the active Minecraft resource manager; no game assets are packaged here. It does not import mobs, distant chunks, arbitrary models or fluids.

After `/stellar start`, F5 and F8, toggle it with `sbmc visuals`. Disable it before capturing EVE into Minecraft. Visual revisions are independent of collision revisions; changing wool to stone can update appearance without changing collision. F11 removes owned visuals and performs the guarded return.

The standalone `sbmc texture` probe requires `minecraft-stone.png` in the private bridge directory, extracted from `assets/minecraft/textures/block/stone.png` in the user's own Minecraft 1.21.1 client JAR. It creates one temporary non-colliding cube ahead of EVE. Its opaque display material does not establish Minecraft lighting compatibility.
