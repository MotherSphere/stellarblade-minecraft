// Checks of Inputs.cs on synthetic folders and files made here; no game data. Run on Linux, and as a win-x64 program
// under Wine by tools/check.py, where the mod-container lookup meets Windows separators.
var failures = 0;
void Check(bool condition, string what)
{
    if (condition) return;
    failures++;
    Console.Error.WriteLine($"FAIL {what}");
}
int Code(Action action)
{
    try { action(); return 0; }
    catch (InputException error) { return error.Code; }
}
string[] Args(params string[] extra) => ["--paks", "p", "--usmap", "m.usmap", "--oodle", "o.so", "--out", "w", .. extra];

var root = Directory.CreateTempSubdirectory("eve-extract-tests-").FullName;
try
{
    // Arguments.
    var request = Inputs.Parse(Args("json", "SB/Content/A", "SB/Content/B"));
    Check(request is { Paks: "p", Usmap: "m.usmap", Oodle: "o.so", Out: "w", Mode: "json" } && request.Paths.SequenceEqual(["SB/Content/A", "SB/Content/B"]),
        "a full command parses");
    Check(Inputs.Parse(["avatar", "X", "--out", "w", "--oodle", "o", "--usmap", "m", "--paks", "p"]).Mode == "avatar", "options go anywhere");
    foreach (var (given, what) in new (string[], string)[]
    {
        (["--paks", "p", "--usmap", "m", "--oodle", "o", "json", "X"], "--out missing"),
        (Args("json", "X", "--paks", "q"), "an option twice"),
        (Args("json", "X", "--verbose"), "an unknown option"),
        (Args("inventory", "X"), "a dropped development mode"),
        (Args("types", "X"), "another dropped development mode"),
        (Args("json"), "no game path"),
        (Args(), "no mode"),
        (Args("json", "SB/Content/A B"), "a path with a space"),
        (Args("json", "SB/Content/A\tB"), "a path with a tab"),
        (["--paks", "", "--usmap", "m", "--oodle", "o", "--out", "w", "json", "X"], "an empty value"),
        (["--paks"], "an option without its value"),
    })
        Check(Code(() => Inputs.Parse(given)) == Inputs.Usage, $"usage error: {what}");

    // Inputs: each one missing or wrong gives exit code 3, in the order they are checked.
    var paks = Directory.CreateDirectory(Path.Combine(root, "Paks")).FullName;
    var usmap = Path.Combine(root, "Mappings.usmap");
    var oodle = Path.Combine(root, "liboodle-data-shared.so");
    var output = Path.Combine(root, "work");
    Request With(string? paksDir = null, string? map = null, string? lib = null, string? work = null) =>
        new(paksDir ?? paks, map ?? usmap, lib ?? oodle, work ?? output, "json", ["X"]);
    Check(Code(() => Inputs.Check(With(paksDir: Path.Combine(root, "absent")))) == Inputs.Missing, "no Paks folder");
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "a Paks folder without containers");
    Directory.CreateDirectory(Path.Combine(paks, "~mods"));
    File.WriteAllText(Path.Combine(paks, "~mods", "only_P.pak"), "");
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "mods alone are no game");
    File.WriteAllText(Path.Combine(paks, "pakchunk0-WinGDK.utoc"), "");
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "no usmap");
    File.WriteAllBytes(usmap, [0x50, 0x4B, 0x03]);
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "a usmap with the wrong magic");
    File.WriteAllBytes(usmap, [0xC4]);
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "a truncated usmap");
    File.WriteAllBytes(usmap, [0xC4, 0x30, 0x03, 0x00]);
    Check(Code(() => Inputs.Check(With())) == Inputs.Missing, "no Oodle library");
    File.WriteAllText(oodle, "");
    Check(Code(() => Inputs.Check(With())) == 0, "every input present");
    File.WriteAllText(Path.Combine(root, "a-file"), "");
    Check(Code(() => Inputs.Check(With(work: Path.Combine(root, "a-file")))) == Inputs.Usage, "--out names a file");
    Check(!Directory.Exists(output), "the checks create nothing");

    // Mod containers: everything below the Paks root, in UE priority order; the root's own containers are the base.
    foreach (var name in new[] { "pakchunk0-WinGDK.ucas", "pakchunk1-WinGDK.pak", "~mods/z_mod.pak", "~mods/a_mod_P.pak",
                 "~mods/b_2_P.utoc", "~mods/b_2_P.ucas", "~mods/b_2_P.pak", "~mods/sub/d_0_P.pak", "LogicMods/c.PAK",
                 "LogicMods/readme.txt" })
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.Combine(paks, name))!);
        File.WriteAllText(Path.Combine(paks, name), "");
    }
    foreach (var given in new[] { paks, paks + Path.DirectorySeparatorChar, paks.Replace('\\', '/') })
    {
        var found = Inputs.ModContainers(given).Select(g => (Path.GetRelativePath(paks, g.Key).Replace('\\', '/'),
            string.Join(",", g.Select(Path.GetExtension).Order(StringComparer.Ordinal)))).ToList();
        string[] expected = ["LogicMods/c:.PAK", "~mods/z_mod:.pak", "~mods/a_mod_P:.pak", "~mods/only_P:.pak", "~mods/sub/d_0_P:.pak", "~mods/b_2_P:.pak,.utoc"];
        Check(found.Select(f => $"{f.Item1}:{f.Item2}").SequenceEqual(expected), $"mod containers from '{given}': {string.Join(" ", found)}");
    }
    Check(Inputs.Priority("x_P") == 100 && Inputs.Priority("x_0_P") == 100 && Inputs.Priority("x_1_P") == 200
          && Inputs.Priority("x_9_p") == 1000 && Inputs.Priority("x") == 0 && Inputs.Priority("x_P_1") == 0
          && Inputs.Priority("x_99999999999999999999_P") == 100, "UE priorities");

    // Writes stay under --out.
    Check(Inputs.Inside(output, "avatar/A.sbav") == Path.GetFullPath(Path.Combine(output, "avatar", "A.sbav")), "a name inside --out");
    foreach (var name in new[] { "../escape", "avatar/../../escape", Path.Combine(root, "elsewhere"), "", "." })
    {
        var refused = false;
        try { Inputs.Inside(output, name); } catch (InvalidDataException) { refused = true; }
        Check(refused, $"'{name}' is refused");
    }
    Check(Inputs.Field("a\tb\r\nc") == "a b c" && Inputs.Field(null) == "", "fields hold no tab or line break");
    Check(Inputs.Help.Contains("--oodle") && Inputs.Help.Contains("never downloaded"), "the help names the inputs");
}
finally
{
    Directory.Delete(root, recursive: true);
}
Console.WriteLine(failures == 0 ? "PASS eve-extract inputs" : $"{failures} checks failed");
return failures == 0 ? 0 : 1;
