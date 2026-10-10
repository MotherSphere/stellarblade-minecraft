# Third-party notices

This repository is licensed under GPL-3.0-or-later ([LICENSE](LICENSE)). The work below is adapted or included under its own license, which stays with it.

## Fabric API

The installer binary embeds Fabric API 0.116.17+1.21.1 (`fabric-api-0.116.17+1.21.1.jar`, unmodified, SHA-512 `98c47821…57a8`) and installs it into a Minecraft `mods` folder that has none. Fabric API is licensed under the Apache License 2.0; its full text ships inside the JAR as `LICENSE-fabric-api`, is embedded in the installer with it, and is printed by `stellar-bridge-installer --licenses`. The Fabric API JAR has no NOTICE file.

Source: https://github.com/FabricMC/fabric

## Fabric server launcher in the installer

The installer binary embeds Fabric's server launcher for Minecraft 1.21.1 with Fabric Loader 0.19.5 (`fabric-server-launch.jar`, unmodified, SHA-256 `809d99b1…7ed0`) and writes it into a new server folder. It contains Fabric Installer 1.1.2 and mjson 1.4.1, both licensed under the Apache License 2.0. The licence text and the notice are in [installer/vendor/fabric-server/](installer/vendor/fabric-server/) (`LICENSE`, `NOTICE`), are embedded in the installer and are printed by `stellar-bridge-installer --licenses`.

Source: https://github.com/FabricMC/fabric-installer

## Fonts in the installer

The installer embeds the fonts of colony-ui 0.1.6 (GPL-3.0-or-later): JetBrainsMono Nerd Font (JetBrains Mono 2.304 with Nerd Fonts 3.4.0), OpenDyslexic 0.990 and Font Awesome 6 Free 6.7.2, all unmodified and under the SIL Open Font License 1.1. Their licence texts are in [installer/licenses/](installer/licenses/) and are printed by `stellar-bridge-installer --licenses`.

Sources: https://github.com/JetBrains/JetBrainsMono, https://github.com/ryanoasis/nerd-fonts, https://github.com/antijingoist/opendyslexic, https://github.com/FortAwesome/Font-Awesome

## SkyCraft

The raw frame transport adapts SkyCraft's three-buffer ownership and atomic publication design, including its producer/consumer exchange pattern. Reference implementation: `skse/src/Link.cpp`, `fabric/src/main/java/dev/skycraft/link/SkyLink.java` and `fabric/src/client/java/dev/skycraft/client/FrameExporter.java`, commit `bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32`.

Source: https://github.com/chasmlol/SkyCraft

The Linux file-backed mapping approach is also described by [SkyCraft PR26](https://github.com/chasmlol/SkyCraft/pull/26), by asimplestray, commit `8aab05f`. This bridge implements its own Java21/JNA and native ownership lifecycle for Linux Minecraft and Stellar Blade under Proton.

MIT License

Copyright (c) 2026 chasmlol

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
