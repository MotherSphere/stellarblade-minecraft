# Third-party notices

This repository is licensed under GPL-3.0-or-later ([LICENSE](LICENSE)). The work below is adapted or included under its own license, which stays with it.

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
