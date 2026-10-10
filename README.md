<p align="center"><img src="installer/assets/logo.svg" alt="Stellar Bridge logo" width="128"></p>

<h1 align="center">Stellar Blade × Minecraft</h1>

<p align="center"><strong>Play Stellar Blade's EVE inside Minecraft Java.</strong></p>

Stellar Blade × Minecraft lets you walk, fight and build in a Minecraft world as EVE. Stellar Blade runs alongside under Proton and simulates her: her controls, animations, skills and effects. Minecraft stays the game you play in: it owns the world, your inventory and menus, and draws EVE with her own meshes, from the pose Stellar Blade streams every frame, through Minecraft's own camera.

## Highlights

- **EVE in Minecraft.** Minecraft draws EVE from her live pose at its own frame rate, with her cloth-simulated ponytail, her materials and her shadow in the sun.
- **Stellar Blade's combat.** Sword, drone gun, grenades and Smart Mines; guard, parry, dodge and perfect dodge judged at each hit's impact; lock-on, Beta and Burst gauges, and Stellar Blade's HUD in place of the hearts.
- **Any Minecraft mob.** EVE fights vanilla and mods' mobs through Minecraft's own damage, so each keeps its reactions and loot; in survival, Minecraft's health is hers.
- **The wardrobe.** An **EVE's equipment** screen in Minecraft's pause menu changes her suit, hair, accessories, drone, gear and exospine through Stellar Blade's own menu rules.
- **Multiplayer.** On a Fabric server, each EVE player links their own Stellar Blade, and modded players with an avatar pack see each other's EVE; players without the mod join as usual.
- **A graphical installer.** Stellar Bridge, a Linux window in English and French, installs, updates, repairs, rolls back and uninstalls the bridge, and writes nothing before you accept its list of files.

## Get started

1. Build Stellar Bridge, the installer, from a checkout: no release is published yet ([setup](docs/setup.md#installer)).
2. Close Stellar Blade and Minecraft and run it. It finds your games and installs into a dedicated Prism instance or one you pick.
3. In a private test world, run `/stellar start`, `/stellar import`, `/stellar enter`, then `/stellaravatar play` ([playing](docs/playing.md#start-a-session)).

## Documentation

| Document | Contents |
|---|---|
| [Playing](docs/playing.md) | Sessions, the terrain window, controls, combat and EVE's equipment |
| [Setup](docs/setup.md) | Supported versions, builds, the installer, the manual install, updates and removal |
| [Multiplayer](docs/multiplayer.md) | Servers, what changes with others online, bandwidth and the one-PC test |
| [Architecture](docs/architecture.md) | How the pieces fit, rendering, input, server and client halves, repository layout |
| [Diagnostics](docs/diagnostics.md) | Diagnostic commands, logs, the avatar pack and performance |
| [Network protocol](protocol/README.md) | Wire contracts between Minecraft, Lua, the native adapter and the network |
| [Installer](installer/README.md) | Stellar Bridge's pages, operations and rules |

## Status

Experimental. It runs today on Linux, with one pinned Stellar Blade Steam build under Proton and Minecraft Java 1.21.1 with Fabric; Windows support is planned. Avatar mode needs a private avatar pack built from your own game files. Back up your Stellar Blade saves.

## Credits and licence

The transport adapts [SkyCraft](https://github.com/chasmlol/SkyCraft)'s three-buffer ownership design. [SkyCraft PR 26](https://github.com/chasmlol/SkyCraft/pull/26) provides Linux mapping context; [2010 Rust rewrite mashup](https://github.com/chasmlol/2010-rust-rewrite-mashup) provides additional allocation-reuse and render-product examples. Engine-specific hooks are implemented and checked separately for Stellar Blade.

No proprietary game assets are included. Stellar Blade and Minecraft belong to their owners; this project is not affiliated with either.

[GPL-3.0-or-later](LICENSE) © 2026 MotherSphere

Stellar Blade × Minecraft is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed without any warranty; see the license for details. The logo is original artwork under the same licence. Code adapted from other projects, Fabric API and the fonts the installer embeds keep their own notices in [third-party notices](THIRD-PARTY-NOTICES.md).
