// EveExtract's arguments and input checks, apart from CUE4Parse so tests/ can link this file alone.
using System.Text.RegularExpressions;

public sealed record Request(string Paks, string Usmap, string Oodle, string Out, string Mode, IReadOnlyList<string> Paths);

/// <summary>An argument or input EveExtract refuses before it reads a container; Code is the exit code.</summary>
public sealed class InputException(int code, string message) : Exception(message)
{
    public int Code { get; } = code;
}

public static class Inputs
{
    public const int Finished = 0, Failed = 1, Usage = 2, Missing = 3;
    public static readonly string[] Modes = ["json", "raw", "avatar", "export"];

    public const string Help = """
        eve-extract --paks <dir> --usmap <file> --oodle <file> --out <dir> <mode> <game path>...

        Reads this PC's own Stellar Blade containers, read-only, and writes only under --out. It never uses the network.
          --paks   SB/Content/Paks: the base containers, then ~mods and LogicMods below it, mounted like the game does
          --usmap  the Mappings .usmap UE4SS made from this game build
          --oodle  the Oodle data library (liboodle-data-shared.so or oodle-data-shared.dll); never downloaded here
          --out    the work folder: mods.txt, json/, raw/, avatar/ (*.sbav and meshes.txt), export/
        Modes: json (property dumps), raw (plain files such as .locres), avatar (SBAVMESH v2 meshes), export (glTF meshes,
        material JSON and PNG textures). One line per item on stdout: see tools/eve-extract/README.md.
        Exit codes: 0 the run finished (items may have failed), 1 it stopped, 2 bad arguments, 3 an input is missing or
        unusable. --licenses prints the licences of this program and of the code it includes.

        """;

    public static Request Parse(IReadOnlyList<string> args)
    {
        var options = new Dictionary<string, string>();
        var rest = new List<string>();
        for (var i = 0; i < args.Count; i++)
        {
            var arg = args[i];
            if (arg is "--paks" or "--usmap" or "--oodle" or "--out")
            {
                if (i + 1 >= args.Count || args[i + 1].Length == 0) throw new InputException(Usage, $"{arg} needs a value");
                if (!options.TryAdd(arg, args[++i])) throw new InputException(Usage, $"{arg} is given twice");
            }
            else if (arg.StartsWith("--", StringComparison.Ordinal)) throw new InputException(Usage, $"unknown option {arg}");
            else rest.Add(arg);
        }
        foreach (var name in new[] { "--paks", "--usmap", "--oodle", "--out" })
            if (!options.ContainsKey(name)) throw new InputException(Usage, $"{name} is required");
        if (rest.Count == 0 || !Modes.Contains(rest[0])) throw new InputException(Usage, $"the mode must be one of {string.Join(", ", Modes)}");
        if (rest.Count == 1) throw new InputException(Usage, "no game path given");
        // Items are reported as tab-separated fields, so a path holds no whitespace or control character.
        if (rest.Skip(1).FirstOrDefault(p => p.Any(c => char.IsWhiteSpace(c) || char.IsControl(c))) is { } odd)
            throw new InputException(Usage, $"a game path holds whitespace: '{odd}'");
        return new Request(options["--paks"], options["--usmap"], options["--oodle"], options["--out"], rest[0], rest.Skip(1).ToList());
    }

    /// <summary>The inputs exist and look right; nothing is read beyond a header and a folder listing.</summary>
    public static void Check(Request request)
    {
        if (!Directory.Exists(request.Paks)) throw new InputException(Missing, $"no Paks folder at {request.Paks}");
        if (!Directory.EnumerateFiles(request.Paks).Any(IsContainer))
            throw new InputException(Missing, $"no .utoc or .pak container in {request.Paks}");
        if (!File.Exists(request.Usmap)) throw new InputException(Missing, $"no usmap file at {request.Usmap}");
        if (!IsUsmap(request.Usmap)) throw new InputException(Missing, $"{request.Usmap} is not a usmap file");
        if (!File.Exists(request.Oodle)) throw new InputException(Missing, $"no Oodle library at {request.Oodle}");
        if (File.Exists(request.Out)) throw new InputException(Usage, $"--out {request.Out} is a file");
    }

    /// <summary>A .usmap starts with the magic 0x30C4, little-endian.</summary>
    public static bool IsUsmap(string path)
    {
        using var file = File.OpenRead(path);
        Span<byte> magic = stackalloc byte[2];
        return file.ReadAtLeast(magic, 2, throwOnEndOfStream: false) == 2 && magic[0] == 0xC4 && magic[1] == 0x30;
    }

    public static bool IsContainer(string path) =>
        path.EndsWith(".pak", StringComparison.OrdinalIgnoreCase) || path.EndsWith(".utoc", StringComparison.OrdinalIgnoreCase);

    /// <summary>The containers below the Paks root (~mods, LogicMods), each as its files without extension, in UE
    /// priority order: _P adds 100, _N_P 100 * (N + 1); ties by path. The root's own containers are the base game.</summary>
    public static IEnumerable<IGrouping<string, string>> ModContainers(string paks)
    {
        // Compared by folder, not by a '/' in the relative path: on Windows that path uses '\'.
        var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(paks));
        return Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
            .Where(f => IsContainer(f) && Path.GetDirectoryName(f) != root)
            .GroupBy(f => Path.ChangeExtension(f, null))
            .OrderBy(g => Priority(g.Key)).ThenBy(g => g.Key, StringComparer.Ordinal);
    }

    public static long Priority(string stem)
    {
        var match = Regex.Match(Path.GetFileName(stem), @"(?:_(\d+))?_P$", RegexOptions.IgnoreCase);
        if (!match.Success) return 0;
        return match.Groups[1].Success && long.TryParse(match.Groups[1].Value, out var n) && n >= 1 ? 100 * (n + 1) : 100;
    }

    /// <summary>root/name, refused when name would leave root (an absolute name, or '..').</summary>
    public static string Inside(string root, string name)
    {
        var top = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)) + Path.DirectorySeparatorChar;
        var full = Path.GetFullPath(Path.Combine(top, name));
        if (!full.StartsWith(top, StringComparison.Ordinal) || full.Length == top.Length)
            throw new InvalidDataException($"'{name}' would be written outside --out");
        return full;
    }

    /// <summary>One stdout field: tabs and line breaks become spaces.</summary>
    public static string Field(string? value) => Regex.Replace(value ?? "", @"[\t\r\n]+", " ");
}
