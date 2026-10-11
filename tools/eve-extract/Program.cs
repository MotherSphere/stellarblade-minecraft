// EveExtract: reads the player's own Stellar Blade containers, read-only, and writes what the avatar pack builder needs
// under --out. It never uses the network: Oodle and the usmap are files the caller passes. Arguments, exit codes and
// the stdout line grammar are in README.md.
using System.Text;
using CUE4Parse.Compression;
using CUE4Parse.FileProvider;
using CUE4Parse.MappingsProvider.Usmap;
using CUE4Parse.UE4.Assets.Exports.Animation;
using CUE4Parse.UE4.Assets.Exports.Material;
using CUE4Parse.UE4.Assets.Exports.SkeletalMesh;
using CUE4Parse.UE4.Assets.Exports.Texture;
using CUE4Parse.UE4.Versions;
using CUE4Parse.UE4.VirtualFileSystem;
using CUE4Parse_Conversion;
using CUE4Parse_Conversion.Textures;

Console.OutputEncoding = new UTF8Encoding(false);
Console.Out.NewLine = "\n";
if (args is [] or ["--help"] or ["-h"])
{
    Console.Write(Inputs.Help);
    return args.Length == 0 ? Inputs.Usage : Inputs.Finished;
}
if (args is ["--licenses"])
{
    Console.WriteLine("eve-extract is licensed under GPL-3.0-or-later. It includes CUE4Parse and CUE4Parse-Conversion, with one");
    Console.WriteLine("marked change, the .NET runtime and the packages listed in THIRD-PARTY-NOTICES.md below.");
    foreach (var name in new[] { "THIRD-PARTY-NOTICES.md", "CUE4Parse.NOTICE", "CUE4Parse.LICENSE", "dotnet.LICENSE", "dotnet.THIRD-PARTY-NOTICES" })
    {
        using var stream = typeof(Inputs).Assembly.GetManifestResourceStream(name);
        if (stream is not null) Console.WriteLine($"\n==> {name}\n{new StreamReader(stream).ReadToEnd()}");
    }
    return Inputs.Finished;
}

var output = new object();
var (ok, failed) = (0, 0);
void Line(params string?[] fields) { lock (output) Console.WriteLine(string.Join('\t', fields.Select(Inputs.Field))); }
void Ok(string item, string detail) { Interlocked.Increment(ref ok); Line("OK", item, detail); }
void Fail(string item, string reason) { Interlocked.Increment(ref failed); Line("FAIL", item, reason); }
static string Describe(Exception error) => $"{error.GetType().Name}: {error.Message}";

Request request;
DefaultFileProvider provider;
try
{
    request = Inputs.Parse(args);
    Inputs.Check(request);
    // The managed decoders on every OS: Windows would otherwise decode BC7 with the native Detex, Linux never does.
    TextureDecoder.UseAssetRipperTextureDecoder = true;
    provider = new DefaultFileProvider(request.Paks, SearchOption.TopDirectoryOnly, new VersionContainer(EGame.GAME_StellarBlade),
        StringComparer.OrdinalIgnoreCase);
    try { provider.MappingsContainer = new FileUsmapTypeMappingsProvider(request.Usmap); }
    catch (Exception error) { throw new InputException(Inputs.Missing, $"{request.Usmap} is not a readable usmap: {Describe(error)}"); }
    // Never OodleHelper.Initialize(path): it downloads the library when the file is missing.
    try { OodleHelper.Initialize(new OodleDotNet.Oodle(Path.GetFullPath(request.Oodle))); }
    catch (Exception error) { throw new InputException(Inputs.Missing, $"{request.Oodle} is not a loadable Oodle library: {Describe(error)}"); }
}
catch (InputException error)
{
    Console.Error.WriteLine($"eve-extract: {error.Message}");
    return error.Code;
}

try
{
    Directory.CreateDirectory(request.Out);
    provider.Initialize();
    var mounted = provider.Mount();
    if (mounted == 0) { Console.Error.WriteLine($"eve-extract: no container of {request.Paks} could be mounted"); return Inputs.Missing; }
    // Mounted like the game: the base containers at the Paks root first, then every container below it (~mods, LogicMods),
    // one at a time in UE priority order. Path lookups take the highest order; package imports take the last mounted, so
    // mounting mods last makes both agree.
    var baseVfs = provider.MountedVfs.ToHashSet();
    foreach (var container in Inputs.ModContainers(request.Paks))
    {
        foreach (var file in container.Order(StringComparer.Ordinal)) provider.RegisterVfs(file);
        mounted += provider.Mount();
    }
    provider.PostMount();
    Line("MOUNTED", mounted.ToString(), provider.Files.Count.ToString());
    {
        // Mods report (mods.txt): what the mods add, which base assets they override, and the conflicts (an asset two mods
        // provide: the user's to resolve, the extraction does not guess). Containers left unmounted are listed (encrypted
        // ones need a key, which EveExtract never has).
        var paks = Path.GetFullPath(request.Paks);
        string Rel(string path) => Path.GetRelativePath(paks, path).Replace('\\', '/');
        var mods = provider.MountedVfs.Where(r => !baseVfs.Contains(r)).OrderBy(r => r.ReadOrder).ThenBy(r => r.Path, StringComparer.Ordinal).ToList();
        var unmounted = provider.UnloadedVfs.Where(r => !r.Name.Equals("global.utoc", StringComparison.OrdinalIgnoreCase)).ToList(); // Global data, read apart.
        var modCount = mods.Select(r => Path.ChangeExtension(r.Path, null)).Distinct().Count();
        var report = new List<string> { $"base {baseVfs.Count} containers, mods {modCount}, not mounted {unmounted.Count}, keys needed {provider.RequiredKeys.Count}" };
        report.AddRange(unmounted.Select(r => $"NOT-MOUNTED {Rel(r.Path)}{(r.IsEncrypted ? " encrypted" : "")}").Order(StringComparer.Ordinal));
        report.AddRange(mods.Select(r => $"MOD {Rel(r.Path)} order {r.ReadOrder} files {r.FileCount}"));
        foreach (var path in mods.SelectMany(r => r.Files.Keys).Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.OrdinalIgnoreCase))
        {
            var owners = mods.Where(r => r.Files.ContainsKey(path)).Select(r => Rel(Path.ChangeExtension(r.Path, null))).Distinct().ToList();
            if (owners.Count > 1) report.Add($"CONFLICT {path} {string.Join(" ", owners)}");
            else if (baseVfs.Any(r => r.Files.ContainsKey(path))) report.Add($"OVERRIDE {path} {owners[0]}");
        }
        File.WriteAllLines(Inputs.Inside(request.Out, "mods.txt"), report);
        Line("MODS", baseVfs.Count.ToString(), modCount.ToString(), unmounted.Count.ToString(), provider.RequiredKeys.Count.ToString(),
            report.Count(l => l.StartsWith("OVERRIDE ")).ToString(), report.Count(l => l.StartsWith("CONFLICT ")).ToString());
    }

    switch (request.Mode)
    {
        case "export":
        {
            // glTF meshes with skeleton and morph targets, material parameter JSON and PNG textures. A mesh also exports
            // its materials and their textures: each exported object gets its own line as it completes.
            var session = new ExportSession();
            foreach (var path in request.Paths)
            {
                try
                {
                    if (!provider.TryLoadPackage(path, out var package)) { Fail(path, "no game file"); continue; }
                    var export = package.GetExports().FirstOrDefault(e => e is USkeletalMesh or USkeleton or UTexture or UMaterialInterface);
                    if (export is null) Fail(path, "no mesh, skeleton, texture or material");
                    else session.Add(export);
                }
                catch (Exception error) { Fail(path, Describe(error)); }
            }
            var directory = Inputs.Inside(request.Out, "export");
            var options = new CUE4Parse_Conversion.Options.ExportOptions(CUE4Parse_Conversion.Options.EMeshFormat.Gltf2,
                materialDepth: EMaterialDepth.AllLayersNoRef);
            session.RunAsync(directory, options, new Reporter(progress =>
            {
                if (progress.LastResult is not { } result) return;
                var files = result.DiskFilePaths ?? [];
                if (files.FirstOrDefault(f => !Path.GetFullPath(f).StartsWith(directory + Path.DirectorySeparatorChar, StringComparison.Ordinal)) is { } outside)
                    Fail(result.ObjectPath, $"written outside --out: {outside}");
                else if (result.Success) Ok(result.ObjectPath, string.Join(", ", files.Select(Path.GetFileName)));
                else Fail(result.ObjectPath, result.Error is { } error ? Describe(error) : "not exported");
            })).GetAwaiter().GetResult();
            break;
        }
        case "avatar":
        {
            // SBAVMESH v2, Unreal space (cm, Z up): bones with reference local transforms, LOD0 vertices with the four
            // strongest influences, indices, material sections, then LOD0 morph target deltas in mesh.MorphTargets order
            // (vertex, position delta, normal delta). Matches eve-pose-probe transforms.
            var directory = Inputs.Inside(request.Out, "avatar");
            Directory.CreateDirectory(directory);
            foreach (var path in request.Paths)
            {
                try
                {
                    if (!provider.TryLoadPackage(path, out var package)) { Fail(path, "no game file"); continue; }
                    if (package.GetExports().OfType<USkeletalMesh>().FirstOrDefault() is not { } mesh) { Fail(path, "no skeletal mesh"); continue; }
                    var target = Inputs.Inside(directory, mesh.Name + ".sbav");
                    var dto = new CUE4Parse_Conversion.Dto.SkeletalMeshDto(mesh, CUE4Parse_Conversion.Options.EMeshQuality.Highest, exportMorphTarget: false);
                    var lod = dto.LODs[0];
                    using var buffer = new MemoryStream(); // Written whole, so a failed mesh leaves no file.
                    using var w = new BinaryWriter(buffer);
                    void Name(string value) { var bytes = Encoding.UTF8.GetBytes(value); w.Write((ushort)bytes.Length); w.Write(bytes); }
                    w.Write("SBAVMESH"u8.ToArray()); w.Write(2u);
                    w.Write((uint)dto.Bones.Length);
                    foreach (var bone in dto.Bones)
                    {
                        Name(bone.Name); w.Write(bone.ParentIndex);
                        var t = bone.Transform;
                        foreach (var v in new[] { t.Rotation.X, t.Rotation.Y, t.Rotation.Z, t.Rotation.W, t.Translation.X, t.Translation.Y, t.Translation.Z, t.Scale3D.X, t.Scale3D.Y, t.Scale3D.Z }) w.Write(v);
                    }
                    w.Write((uint)lod.Vertices.Length);
                    foreach (var v in lod.Vertices)
                    {
                        w.Write(v.Position.X); w.Write(v.Position.Y); w.Write(v.Position.Z);
                        w.Write(v.Normal.X); w.Write(v.Normal.Y); w.Write(v.Normal.Z);
                        w.Write(v.Uv.U); w.Write(v.Uv.V);
                        var influences = v.Influences.OrderByDescending(i => i.Weight).Take(4).ToArray();
                        var total = influences.Sum(i => i.Weight);
                        for (var i = 0; i < 4; i++) w.Write(i < influences.Length ? influences[i].Bone : (ushort)0);
                        for (var i = 0; i < 4; i++) w.Write(i < influences.Length && total > 0 ? influences[i].Weight / total : 0f);
                    }
                    w.Write((uint)lod.Indices.Length);
                    foreach (var index in lod.Indices) w.Write(index);
                    var sections = lod.Sections.Where(section => section.IsValid).ToArray();
                    w.Write((uint)sections.Length);
                    foreach (var section in sections)
                    {
                        Name(dto.Materials.ElementAtOrDefault(section.MaterialIndex).SlotName ?? "None");
                        w.Write((uint)section.FirstIndex); w.Write((uint)section.NumFaces);
                    }
                    var morphs = mesh.MorphTargets ?? [];
                    w.Write((uint)morphs.Length);
                    var deltas = 0;
                    foreach (var reference in morphs)
                    {
                        var morph = reference.Load<UMorphTarget>();
                        var model = morph?.MorphLODModels is { Length: > 0 } models ? models[0] : null;
                        Name(morph?.Name ?? reference.Name);
                        var list = model?.Vertices ?? [];
                        w.Write((uint)list.Length);
                        foreach (var delta in list)
                        {
                            if (delta.SourceIdx >= lod.Vertices.Length) throw new InvalidDataException($"{mesh.Name}: morph vertex out of range");
                            w.Write(delta.SourceIdx);
                            w.Write(delta.PositionDelta.X); w.Write(delta.PositionDelta.Y); w.Write(delta.PositionDelta.Z);
                            w.Write(delta.TangentZDelta.X); w.Write(delta.TangentZDelta.Y); w.Write(delta.TangentZDelta.Z);
                        }
                        deltas += list.Length;
                    }
                    w.Flush();
                    File.WriteAllBytes(target, buffer.ToArray());
                    // Where each mesh and its section materials came from, for the pack manifest and the material bakes.
                    var container = provider.Files.TryGetValue(path.EndsWith(".uasset") ? path : path + ".uasset", out var entry) ? (entry as VfsEntry)?.Vfs.Name ?? "-" : "-";
                    File.AppendAllLines(Path.Combine(directory, "meshes.txt"), sections.Select(section => $"MATERIAL {mesh.Name}.sbav "
                        + $"{dto.Materials.ElementAtOrDefault(section.MaterialIndex).SlotName ?? "None"} {dto.Materials.ElementAtOrDefault(section.MaterialIndex).Material?.ResolvedObject?.GetPathName() ?? "-"}")
                        .Prepend($"MESH {mesh.Name}.sbav {path} {container}"));
                    Ok(path, $"{mesh.Name}.sbav: {dto.Bones.Length} bones, {lod.Vertices.Length} vertices, {lod.Indices.Length / 3} triangles, "
                        + $"{sections.Length} sections, {morphs.Length} morphs ({deltas} deltas)");
                }
                catch (Exception error) { Fail(path, Describe(error)); }
            }
            break;
        }
        case "raw":
        {
            // Plain files (configs, .locres) as stored in the containers.
            var directory = Inputs.Inside(request.Out, "raw");
            Directory.CreateDirectory(directory);
            foreach (var path in request.Paths)
            {
                try
                {
                    if (!provider.TrySaveAsset(path, out var bytes)) { Fail(path, "no game file"); continue; }
                    File.WriteAllBytes(Inputs.Inside(directory, Path.GetFileName(path)), bytes);
                    Ok(path, Path.GetFileName(path));
                }
                catch (Exception error) { Fail(path, Describe(error)); }
            }
            break;
        }
        case "json":
        {
            // Property dumps of whole packages (exports as CUE4Parse serializes them): tables, skeletons, parameters.
            var directory = Inputs.Inside(request.Out, "json");
            Directory.CreateDirectory(directory);
            foreach (var path in request.Paths)
            {
                try
                {
                    var exports = provider.LoadPackage(path).GetExports();
                    var name = Path.GetFileName(path) + ".json";
                    File.WriteAllText(Inputs.Inside(directory, name), Newtonsoft.Json.JsonConvert.SerializeObject(exports, Newtonsoft.Json.Formatting.Indented));
                    Ok(path, name);
                }
                catch (Exception error) { Fail(path, Describe(error)); }
            }
            break;
        }
    }
    Line("DONE", ok.ToString(), failed.ToString());
    return Inputs.Finished;
}
catch (Exception error)
{
    Console.Error.WriteLine($"eve-extract: stopped: {Describe(error)}");
    return Inputs.Failed;
}

/// <summary>Reports on the exporting thread at once; Progress&lt;T&gt; would post to the thread pool.</summary>
sealed class Reporter(Action<ExportProgress> report) : IProgress<ExportProgress>
{
    public void Report(ExportProgress value) => report(value);
}
