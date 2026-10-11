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

Two more fonts come with the window toolkit, unmodified: iced 0.14 embeds Iced-Icons, part of iced (MIT, Copyright 2019 Héctor Ramón, Iced contributors): the iced logo with check, caret and arrow icons, put together with Fontello ("Copyright (C) 2020 by original authors @ fontello.com"; its check and caret are Font Awesome's, under the SIL Open Font License 1.1), and the Linux build embeds Cantarell Regular 0.05 through sctk-adwaita for its window titles ("Copyright (c) 2009-2011, Understanding Limited, Copyright (c) 2010-2011, Jakub Steiner", SIL Open Font License 1.1). The OFL 1.1 text is the one `--licenses` prints for the fonts above. Each font is a separate work that the program loads as data; the OFL lets it be bundled with any software, and its own licence stays with it.

Sources: https://github.com/iced-rs/iced, https://gitlab.gnome.org/GNOME/cantarell-fonts

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

## Rust crates in the installer

`stellar-bridge-installer` ([installer/](installer/)) is built from the Rust standard library (MIT OR Apache-2.0) and the crates below, at the versions [installer/Cargo.lock](installer/Cargo.lock) pins, for Linux and Windows on x86_64 (a crate only one OS uses is listed too). Procedural macros and build scripts leave no code in the program and are not listed. None of these crates carries a NOTICE file. The Apache License 2.0 is printed by `stellar-bridge-installer --licenses` (as the Fabric Installer licence) and is at https://www.apache.org/licenses/LICENSE-2.0. Each crate's source is at https://crates.io/crates/NAME/VERSION.

- Under the Apache License 2.0, which the installer takes wherever a crate offers it: ab_glyph 0.2.32, ab_glyph_rasterizer 0.1.10, adler2 2.0.1, ahash 0.8.12, anyhow 1.0.104, arrayvec 0.7.8, as-raw-xcb-connection 1.0.1, ash 0.38.0+1.3.281, async-broadcast 0.7.2, async-channel 2.5.0, async-executor 1.14.0, async-io 2.6.0, async-lock 3.4.2, async-process 2.5.0, async-signal 0.2.14, async-task 4.7.1, atomic-waker 1.1.2, base64 0.23.1, bit-set 0.8.0, bit-vec 0.8.0, bitflags 2.13.2, block-buffer 0.12.1, blocking 1.7.0, bytemuck 1.25.2, cfg-if 1.0.5, clipboard_wayland 0.2.2, codespan-reporting 0.12.0, concurrent-queue 2.5.0, const-oid 0.10.2, cosmic-text 0.15.0, cpufeatures 0.3.1, crc32fast 1.5.2, crossbeam-deque 0.8.8, crossbeam-epoch 0.9.21, crossbeam-utils 0.8.23, cryoglyph 0.1.0, crypto-common 0.2.2, ctor 0.10.1, cursor-icon 1.2.0, digest 0.11.3, dirs 6.0.0, dirs-sys 0.5.0, downcast-rs 1.2.1, dpi 0.1.2, either 1.19.0, enumflags2 0.7.12, equivalent 1.0.2, errno 0.3.14, etagere 0.2.15, euclid 0.22.14, event-listener 5.4.2, event-listener-strategy 0.5.4, fastrand 2.5.0, fdeflate 0.3.7, flate2 1.1.10, font-types 0.10.1, font-types 0.12.6, futures 0.3.34, futures-channel 0.3.34, futures-core 0.3.34, futures-executor 0.3.34, futures-io 0.3.34, futures-lite 2.6.1, futures-sink 0.3.34, futures-task 0.3.34, futures-util 0.3.34, gethostname 1.1.0, getrandom 0.2.17, getrandom 0.3.4, glam 0.25.0, glow 0.16.0, glutin_wgl_sys 0.6.1, gpu-alloc 0.6.2, gpu-alloc-types 0.3.1, gpu-allocator 0.27.0, gpu-descriptor 0.3.2, gpu-descriptor-types 0.2.0, guillotiere 0.6.2, half 2.7.1, hashbrown 0.15.5, hashbrown 0.16.1, hashbrown 0.17.1, hex 0.4.3, http 1.5.0, httparse 1.10.1, hybrid-array 0.4.15, image 0.25.10, indexmap 2.14.2, itoa 1.0.18, khronos-egl 6.0.0, kurbo 0.10.4, libc 0.2.190, linebender_resource_handle 0.1.1, linux-raw-sys 0.12.1, linux-raw-sys 0.4.15, litrs 1.0.0, lock_api 0.4.14, log 0.4.34, memmap2 0.9.11, miniz_oxide 0.8.9, miniz_oxide 0.9.1, moxcms 0.8.1, mundy 0.2.3, naga 27.0.3, ntapi 0.4.3, num-traits 0.2.19, once_cell 1.21.4, ordered-stream 0.2.0, owned_ttf_parser 0.25.1, parking 2.2.1, parking_lot 0.12.5, parking_lot_core 0.9.12, percent-encoding 2.3.2, pin-project-lite 0.2.17, piper 0.2.5, png 0.18.1, polling 3.11.0, presser 0.3.1, proc-macro-crate 3.5.0, proc-macro2 1.0.107, profiling 1.0.18, pxfm 0.1.30, quote 1.0.47, range-alloc 0.1.5, rangemap 1.8.0, raw-window-handle 0.6.2, rayon 1.12.0, rayon-core 1.13.0, read-fonts 0.35.0, read-fonts 0.41.0, renderdoc-sys 1.1.0, roxmltree 0.20.0, rustc-hash 1.1.0, rustc-hash 2.1.3, rustix 0.38.44, rustix 1.1.5, rustls 0.23.45, rustls-pki-types 1.15.1, scoped-tls 1.0.1, scopeguard 1.2.0, self_cell 1.3.0, serde 1.0.229, serde_core 1.0.229, serde_json 1.0.151, sha2 0.11.0, signal-hook-registry 1.4.8, skrifa 0.37.0, skrifa 0.44.0, smallvec 1.16.2, smol_str 0.2.2, softbuffer 0.4.8, spirv 0.3.0+sdk-1.3.268.0, static_assertions 1.1.0, svg_fmt 0.4.5, swash 0.2.10, syn 2.0.119, syn 3.0.6, sys-locale 0.3.2, thiserror 1.0.69, thiserror 2.0.21, tiny-xlib 0.2.5, tinyvec 1.13.3, toml_datetime 1.1.2+spec-1.1.0, toml_edit 0.25.17+spec-1.1.0, toml_parser 1.1.5+spec-1.1.0, ttf-parser 0.25.1, typed-path 0.12.3, typenum 1.20.1, unicode-bidi 0.3.18, unicode-ident 1.0.26, unicode-linebreak 0.1.5, unicode-script 0.5.8, unicode-segmentation 1.13.3, unicode-width 0.2.2, ureq 3.4.2, ureq-proto 0.6.4, utf8-zero 0.8.1, uuid 1.27.0, web-time 1.1.0, wgpu 27.0.1, wgpu-core 27.0.3, wgpu-core-deps-windows-linux-android 27.0.0, wgpu-hal 27.0.4, wgpu-types 27.0.1, winapi 0.3.9, windows 0.58.0, windows 0.62.2, windows-collections 0.3.2, windows-core 0.58.0, windows-core 0.62.2, windows-future 0.3.2, windows-link 0.2.1, windows-numerics 0.3.1, windows-result 0.2.0, windows-result 0.4.1, windows-strings 0.1.0, windows-strings 0.5.1, windows-sys 0.52.0, windows-sys 0.61.2, windows-targets 0.52.6, windows-threading 0.2.1, windows_x86_64_msvc 0.52.6, winit 0.30.13, x11rb 0.13.2, x11rb-protocol 0.13.2, xkeysym 0.2.1, yazi 0.2.1, zeno 0.3.3, zerocopy 0.8.62, zeroize 1.9.1. dpi 0.1.2 also carries MIT-licensed code (listed with the MIT crates), and unicode-ident 1.0.26 Unicode data under the Unicode License v3 (below).
- ring 0.17.14: ISC for its own code (Copyright 2015-2025 Brian Smith) and the Apache License 2.0 for the parts from BoringSSL and fiat-crypto. Its versions since 0.17.10 hold no code under the OpenSSL or SSLeay licences.
- Under the MIT licence below, each with its copyright notice:
  - bytes 1.12.1: Copyright (c) 2018 Carl Lerche
  - calloop 0.13.0, calloop 0.14.5, smithay-client-toolkit 0.19.2, smithay-client-toolkit 0.20.0: Copyright (c) 2018 Victor Berger
  - calloop-wayland-source 0.3.0, calloop-wayland-source 0.4.1, wayland-csd-frame 0.3.0, xkbcommon-dl 0.4.2: Copyright (c) 2023 Kirill Chibisov
  - clipboard_x11 0.4.3: Copyright (c) 2019 quininer@live.com, Héctor Ramón, window_clipboard_x11 contributors
  - core_maths 0.1.1: Copyright (c) 2024 Robert Bastian
  - deflate64 0.1.12: Copyright (c) .NET Foundation and Contributors; Copyright (c) anatawa12 2023
  - dlib 0.5.3: Copyright (c) 2015 Victor Berger
  - dpi 0.1.2 (its code from libm) and libm 0.2.16: Copyright (c) 2018 Jorge Aparicio; portions from musl libc, Copyright © 2005-2020 Rich Felker, et al., whose math code is Copyright © 1993,2004 Sun Microsystems, © 2003-2011 David Schultz, © 2003-2009 Steven G. Kargl, © 2003-2009 Bruce D. Evans, © 2008 Stephen L. Moshier or © 2017-2018 Arm Limited, under permissive terms; CORE-MATH routines are MIT
  - endi 1.1.1: Copyright (c) Zeeshan Ali Khan
  - fontconfig-parser 0.5.8: Copyright (c) 2021 Riey
  - fontdb 0.23.0: Copyright (c) 2020 Yevhenii Reizner
  - harfrust 0.3.2: Copyright (c) 2020 Yevhenii Reizner; Copyright (c) HarfBuzz developers
  - iced 0.14.0, iced_core 0.14.0, iced_debug 0.14.0, iced_futures 0.14.0, iced_graphics 0.14.0, iced_program 0.14.0, iced_renderer 0.14.0, iced_runtime 0.14.0, iced_tiny_skia 0.14.1, iced_wgpu 0.14.0, iced_widget 0.14.2, iced_winit 0.14.1: Copyright 2019 Héctor Ramón, Iced contributors
  - lilt 0.8.2: Copyright (c) 2024 ejjonny
  - lru 0.16.4: Copyright (c) 2016 Jerome Froelich
  - ordered-float 5.5.0: Copyright (c) 2015 Jonathan Reem
  - quick-xml 0.41.0: Copyright (c) 2016 Johann Tuffe
  - sctk-adwaita 0.10.1: Copyright (c) 2022 Bartłomiej Maryńczak
  - simd-adler32 0.3.10: Copyright (c) 2021 Marvin Countryman
  - sipper 0.1.0: Copyright 2025 Héctor Ramón
  - slab 0.4.12: Copyright (c) 2019 Carl Lerche
  - smithay-clipboard 0.7.3: Copyright (c) 2018 Lucas Timmins & Victor Berger
  - strict-num 0.1.1: Copyright (c) 2022 Yevhenii Reizner
  - sysinfo 0.39.6: Copyright (c) 2015 Guillaume Gomez
  - tokio 1.53.2: Copyright (c) Tokio Contributors
  - tracing 0.1.44, tracing-core 0.1.36: Copyright (c) 2019 Tokio Contributors
  - wayland-backend 0.3.17, wayland-client 0.31.15, wayland-cursor 0.31.14, wayland-protocols 0.32.13, wayland-protocols-experimental 20250721.0.1, wayland-protocols-misc 0.3.12, wayland-protocols-plasma 0.3.12, wayland-protocols-wlr 0.3.12, wayland-sys 0.31.11: Copyright (c) 2015 Elinor Berger
  - window_clipboard 0.5.1: Copyright 2019 Héctor Ramón, window_clipboard contributors
  - winnow 1.0.4: Copyright (c) the winnow contributors
  - x11-dl 2.21.0: Copyright (c) the x11-dl authors (daggerbot, Erle Pereira)
  - xcursor 0.3.11: Copyright (c) 2020 Samuele Esposito
  - zbus 5.19.0, zbus_names 4.3.4, zvariant 5.15.0: Copyright (c) 2024 Zeeshan Ali Khan & zbus contributors
  - zcheapstr 1.1.0: Copyright (c) 2026 Zeeshan Ali Khan & zcheapstr contributors
  - zip 9.0.1: Copyright (c) 2014 Mathijs van de Nes
  - zmij 1.0.23: Copyright (c) David Tolnay
  - zvariant_utils 4.2.0: Copyright (c) Zeeshan Ali Khan and the zbus contributors
- Under the ISC licence below: libloading 0.8.9 (Copyright © 2015, Simonas Kazlauskas); rustls-webpki 0.103.15 (Copyright 2015 Brian Smith); untrusted 0.9.0 (Copyright 2015-2016 Brian Smith); and ring's own code.
- Under the BSD-3-Clause licence below: subtle 2.6.1 (Copyright (c) 2016-2017 Isis Agora Lovecruft, Henry de Valence; Copyright (c) 2016-2024 Isis Agora Lovecruft); tiny-skia 0.11.4 and tiny-skia-path 0.11.4 (Copyright (c) 2011 Google Inc.; Copyright (c) 2020 Yevhenii Reizner).
- Under the BSD-2-Clause licence below: arrayref 0.3.9 (Copyright (c) 2015 David Roundy).
- Under the zlib licence, which asks nothing of a binary: foldhash 0.1.5, foldhash 0.2.0, slotmap 1.1.1, zlib-rs 0.6.8.
- Under the Boost Software License 1.0, which asks nothing of a binary: clipboard-win 5.4.1, error-code 3.4.0.
- In the public domain: hexf-parse 0.2.1 (CC0 1.0); byteorder-lite 0.1.0, memchr 2.8.3 (the Unlicense, which they offer beside MIT).
- Under the Mozilla Public License 2.0, without the "Incompatible With Secondary Licenses" notice: option-ext 0.2.0. Its source is at https://crates.io/crates/option-ext/0.2.0 (https://github.com/soc/option-ext) and the licence at https://mozilla.org/MPL/2.0/.
- Under the Community Data License Agreement Permissive 2.0 below: webpki-roots 1.0.9, the root certificates ureq trusts.
- Under GPL-3.0-or-later, as this program: colony-ui 0.1.6.

The MIT licence of these crates is the text printed in the EveExtract section above, and the BSD-2-Clause licence of arrayref the one printed there for Blake3, each with the copyright notice given here.

The ISC licence of libloading, rustls-webpki, untrusted and ring:

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

The BSD-3-Clause licence of subtle, tiny-skia and tiny-skia-path:

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The Unicode License v3 of the Unicode data in unicode-ident:

UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2023 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

The Community Data License Agreement Permissive 2.0 of webpki-roots:

Community Data License Agreement - Permissive - Version 2.0

This is the Community Data License Agreement - Permissive, Version
2.0 (the "agreement"). Data Provider(s) and Data Recipient(s) agree
as follows:

1. Provision of the Data

1.1. A Data Recipient may use, modify, and share the Data made
available by Data Provider(s) under this agreement if that Data
Recipient follows the terms of this agreement.

1.2. This agreement does not impose any restriction on a Data
Recipient's use, modification, or sharing of any portions of the
Data that are in the public domain or that may be used, modified,
or shared under any other legal exception or limitation.

2. Conditions for Sharing Data

2.1. A Data Recipient may share Data, with or without modifications, so
long as the Data Recipient makes available the text of this agreement
with the shared Data.

3. No Restrictions on Results

3.1. This agreement does not impose any restriction or obligations
with respect to the use, modification, or sharing of Results.

4. No Warranty; Limitation of Liability

4.1. All Data Recipients receive the Data subject to the following
terms:

THE DATA IS PROVIDED ON AN "AS IS" BASIS, WITHOUT REPRESENTATIONS,
WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED
INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE,
NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.

NO DATA PROVIDER SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING
WITHOUT LIMITATION LOST PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE DATA OR RESULTS,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

5. Definitions

5.1. "Data" means the material received by a Data Recipient under
this agreement.

5.2. "Data Provider" means any person who is the source of Data
provided under this agreement and in reliance on a Data Recipient's
agreement to its terms.

5.3. "Data Recipient" means any person who receives Data directly
or indirectly from a Data Provider and agrees to the terms of this
agreement.

5.4. "Results" means any outcome obtained by computational analysis
of Data, including for example machine learning models and models'
insights.

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
