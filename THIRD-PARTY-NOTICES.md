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

## EveExtract, the avatar pack builder's sidecar

`eve-extract-linux-x64` and `eve-extract-win-x64.exe` ([tools/eve-extract/](tools/eve-extract/)) are self-contained .NET 10 programs. Each carries, unmodified unless stated, the code below; `eve-extract --licenses` prints this file, CUE4Parse's NOTICE and licence, and the .NET runtime's licence and third-party notices, all embedded in the program. Both installers embed their OS's EveExtract, and `stellar-bridge-installer --licenses` prints the same texts.

- **CUE4Parse and CUE4Parse-Conversion** (commit `e4ea4ba8ec2b88d08b2066dfb8962863dccb36cc`), Apache License 2.0, Copyright (c) 2026 FabianFG, with the changes of [tools/eve-extract/cue4parse.patch](tools/eve-extract/cue4parse.patch), each marked where it is made: `ExportSession.cs` and `TextureEncoder.cs` are modified, and the two project files build a smaller library. They leave out of EveExtract what GPL-3.0 cannot take or what comes without a licence to pass on: `Utils/CRC32.cs`, DotNetZip's code under the MS-PL, is not compiled, and [tools/eve-extract/CRC32.cs](tools/eve-extract/CRC32.cs) (GPL-3.0-or-later) replaces it; the resources of other games (Ace Combat 7's key, PUBG's and Borderlands 4's name maps, ProSpi's prebuilt decryptor DLL) and the prebuilt Detex, tegra_swizzle and crunch DLLs are not embedded; and PNG files are encoded by ImageSharp, so SkiaSharp's native library, which carries the Adobe DNG SDK, is not published. CUE4Parse's NOTICE, printed whole, lists the components CUE4Parse adapts (MIT, MS-PL, Apache-2.0, BSD-2-Clause, ISC-style and zlib); of them EveExtract carries neither DotNetZip's code nor crunch. Source: https://github.com/FabianFG/CUE4Parse
- **The .NET 10 runtime** (Microsoft.NETCore.App 10.0), MIT, Copyright (c) .NET Foundation and Contributors, with the third-party notices it ships. Source: https://github.com/dotnet/runtime
- **SixLabors.ImageSharp** 3.1.12, Six Labors Split License 1.0, which grants the Apache License 2.0 to open-source and transitive use, Copyright (c) Six Labors. Source: https://github.com/SixLabors/ImageSharp
- **Serilog** 4.3.1 and **Serilog.Sinks.Console** 6.1.1, Apache License 2.0, Copyright (c) Serilog Contributors. Source: https://github.com/serilog/serilog
- **Blake3** 3.0.2, BSD-2-Clause, Copyright (c) Alexandre Mutel. Source: https://github.com/xoofx/Blake3.NET
- Under the MIT licence below, each Copyright (c) its holder:
  - AssetRipper.TextureDecoder 2.6.2 (ds5678); BouncyCastle.Cryptography 2.6.2 (Legion of the Bouncy Castle Inc.); CommunityToolkit.HighPerformance 8.4.2, Microsoft.Bcl.Memory 9.0.0, System.IO.Hashing 10.0.8 and System.Numerics.Tensors 10.0.10 (.NET Foundation and Contributors; Microsoft Corporation); FixedMathSharp 4.0.1 (David Oravsky); Fmod5Sharp 3.1.0 (Sam Byass); GenericReader 2.3.0, OffiUtils 3.2.0, Oodle.NET 2.2.0, OodleSharp 0.0.1 and Zlib-ng.NET 1.2.0 (NotOfficer); IndexRange 1.1.0 (Bradley Grainger); Infrablack.UE4Config 0.7.2.97 (Patrick Michael Hopf); K4os.Compression.LZ4 and K4os.Compression.LZ4.Streams 1.3.8 and K4os.Hash.xxHash 1.0.8 (Milosz Krajewski); LZMA-SDK 22.1.1 (Mihir Mone; the LZMA SDK itself is Igor Pavlov's, in the public domain); MemoryPack 1.21.4 (Cysharp, Inc.); NAudio.Core 2.2.1 (Mark Heath); Newtonsoft.Json 13.0.4 (James Newton-King); OggVorbisEncoder 1.2.2 (Steve Lillis); SharpGLTF.Core, SharpGLTF.Runtime and SharpGLTF.Toolkit 1.0.6 (Vicente Penades); SkiaSharp 2.88.9, its managed library only (Microsoft Corporation); SubstreamSharp 1.0.3 (Connor Haigh); VGAudio 2.2.1 (Alex Barney); ZstdSharp.Port 0.8.8 (Oleg Stepanischev).

Oodle is **not** part of EveExtract or of this repository: the player supplies it, and EveExtract only loads the file it is given (`--oodle`). Game files, the usmap and everything EveExtract writes come from the player's own copy of Stellar Blade and stay on their PC.

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0 (full text printed by `eve-extract --licenses`).

The BSD-2-Clause licence of Blake3 (Copyright (c) Alexandre Mutel):

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The MIT licence of the packages listed above:

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
