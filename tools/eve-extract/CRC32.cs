// Part of Stellar Blade x Minecraft, GPL-3.0-or-later. CUE4Parse's Utils/CRC32.cs is DotNetZip's code under the MS-PL,
// which the GPL cannot take, so cue4parse.patch drops it from CUE4Parse's build and compiles this file there instead,
// with the members its only callers (Fortnite's ULevelSaveRecord) use. It is the standard reflected CRC-32
// (polynomial 0xEDB88320, the one of zip and gzip) of System.IO.Hashing, which CUE4Parse already references.
namespace CUE4Parse.Utils;

public class CRC32
{
    private readonly System.IO.Hashing.Crc32 _crc = new();

    /// <summary>Adds count bytes of block, from offset, to the CRC.</summary>
    public void SlurpBlock(byte[] block, int offset, int count) => _crc.Append(block.AsSpan(offset, count));

    /// <summary>The CRC of every block added so far.</summary>
    public int Crc32Result => unchecked((int) _crc.GetCurrentHashAsUInt32());
}
