package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Deferred public engine-JAR diagnostic-context authority and immutable capture replay. */
public final class DiagnosticContextConsumer {
  private record Context(UUID source, UUID item, String parameter) {}
  private record Case(Diagnostic input, Context expected, boolean parameter) {}
  private static Diagnostic issue(String path, UUID source, UUID item, String parameter) {
    return new Diagnostic("ORIGINAL", Diagnostic.Severity.WARNING, "original text", path, source, item, parameter);
  }
  private static Case row(String path, UUID source, UUID item, String parameter, UUID owner, UUID control, String key) {
    return new Case(issue(path, source, item, parameter), new Context(owner, control, key), true);
  }
  private static Item expression(String id, long identity, String text) {
    var item = ProcessingRegistry.create("EXPRESSION", id); item.uuid = new UUID(0, identity);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(text)); return item;
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = new ProjectData(); p.project.uuid = new UUID(0, 0x10000); p.worldGenerator.uuid = new UUID(0, 0x10001);
    var a = new Noise(); a.id = "a"; a.uuid = new UUID(0, 0xabc); a.algorithm.key = "CONSTANT"; a.algorithm.parameters.put("value", .25);
    var b = new Noise(); b.id = "b"; b.uuid = new UUID(0, 0xdef); b.algorithm.key = "CONSTANT"; b.algorithm.parameters.put("value", -.5);
    var ai = expression("primary", 0xabc1, "source+x+2*z"); var alternate = expression("alternate", 0xabc2, "source-x");
    var bi = expression("primary", 0xdef1, "source-x+z"); var other = expression("alternate", 0xdef2, "source+x");
    a.processing.addAll(List.of(ai, alternate)); b.processing.addAll(List.of(bi, other));
    a.outputBinding = Binding.earlier(ai.uuid); b.outputBinding = Binding.earlier(bi.uuid); p.noise2D.addAll(List.of(a, b));
    var group = new Membership(); group.id = "group"; group.uuid = new UUID(0, 0x123); p.voxelGroups.add(group);
    String authored = ProjectIO.JSON.writeValueAsString(p), pa = "/noise2D/0/processing/0/parameters/expression", pb = "/noise2D/1/processing/0/parameters/expression";
    String missing = "/noise2D/99/processing/0/parameters/expression"; UUID absent = new UUID(0, 0x999);
    var cases = List.of(
        row(pa, null, null, null, a.uuid, ai.uuid, "expression"), row(pa, a.uuid, null, null, a.uuid, ai.uuid, "expression"),
        row(pa, null, ai.uuid, null, a.uuid, ai.uuid, "expression"), row(pa, a.uuid, ai.uuid, null, a.uuid, ai.uuid, "expression"),
        row(pa, null, null, "owned", a.uuid, ai.uuid, "owned"),
        row("/noise2D/0/processing/1/inputs/a~1b~0c/constant", a.uuid, null, null, a.uuid, alternate.uuid, "a/b~c"),
        row("/noise/" + b.uuid.toString().toUpperCase(Locale.ROOT) + "/processing/" + bi.uuid.toString().toUpperCase(Locale.ROOT) + "/parameters/expression", null, null, null, b.uuid, bi.uuid, "expression"),
        row("/membership/" + group.uuid, null, null, null, group.uuid, null, null),
        row(pb, a.uuid, null, null, a.uuid, null, null), row(pb, null, ai.uuid, null, null, ai.uuid, null),
        row(pb, a.uuid, ai.uuid, null, a.uuid, ai.uuid, null), row(pb, a.uuid, ai.uuid, "owned", a.uuid, ai.uuid, "owned"),
        row(missing, a.uuid, null, null, a.uuid, null, null), row(missing, null, ai.uuid, null, null, ai.uuid, null),
        row(pa, a.uuid, bi.uuid, null, a.uuid, bi.uuid, null), row(pa, b.uuid, ai.uuid, null, b.uuid, ai.uuid, null),
        row("/noise/" + absent, null, null, null, null, null, null), row("/membership/" + absent, null, null, null, null, null, null),
        new Case(issue("/noise/0-0-0-0-abc", null, null, null), new Context(null, null, null), false),
        row("/noise2D/+0/processing/0/parameters/expression", null, null, null, null, null, "expression"),
        row("/noise2D/00/processing/0/parameters/expression", null, null, null, null, null, "expression"),
        row("/noise2D/01/processing/0/parameters/expression", null, null, null, null, null, "expression"),
        row("/noise2D/0/processing/+0/parameters/expression", null, null, null, a.uuid, null, "expression"),
        row("/noise2D/0/processing/00/parameters/expression", null, null, null, a.uuid, null, "expression"));
    for (var c : cases) check(p, c);
    var compiled = new ArrayList<CompiledProject>(); compiled.add(new CompiledProject(p));
    var captured = DiagnosticLocations.enrich(p, List.of(issue(pa, a.uuid, null, null))).getFirst();
    Collections.reverse(p.noise2D);
    check(p, row(pa, a.uuid, null, null, a.uuid, null, null)); check(p, row(pa, null, ai.uuid, null, null, ai.uuid, null));
    require(captured.equals(DiagnosticLocations.enrich(p, List.of(captured)).getFirst()), "Captured context changed after resource reorder"); compiled.add(new CompiledProject(p));
    Collections.reverse(p.noise2D); Collections.reverse(a.processing);
    check(p, row(pa, null, ai.uuid, null, null, ai.uuid, null));
    require(captured.equals(DiagnosticLocations.enrich(p, List.of(captured)).getFirst()), "Captured context changed after control reorder"); compiled.add(new CompiledProject(p));
    Collections.reverse(a.processing); require(authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Context replay changed authored values after restoring order");
    long snapshots = snapshotCases(), fields = 0, cells = 0; var retained = new ArrayList<VoxelChunk>();
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20)) {
      for (var snapshot : compiled) {
        var first = snapshot.noise2D(a.uuid, snapshot.defaultSeed()); var second = snapshot.noise2D(b.uuid, snapshot.defaultSeed());
        for (int z = -1; z <= 1; z++) for (int x = -2; x <= 2; x++) { bits(.25 + x + 2 * z, first.sample(x, z)); bits(-.5 - x + z, second.sample(x, z)); fields += 2; }
        for (int root : new int[] {2, 4}) try (var world = engine.openWorld(snapshot, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) { retained.add(world.generateChunk(new ChunkPos(-1, 0, 1))); }
      }
    }
    for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++) for (int x = 0; x < chunk.size(); x++) { require(chunk.getVoxelTypeId(x, y, z) == 0, "Diagnostic inference invalidated a retained snapshot"); cells++; }
    require(cases.size() == 24 && snapshots == 2 && fields == 90 && retained.size() == 6 && cells == 216, "Unexpected diagnostic-context replay dimensions");
    require(authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Diagnostic replay mutated authoring data");
    Files.writeString(out.resolve("diagnostic-context-report.json"), ProjectIO.JSON.writeValueAsString(Map.of("contextCases", cases.size(), "reorderChecks", 5,
        "snapshotCases", snapshots, "fields", fields, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4))));
    System.out.println("DIAGNOSTIC_CONTEXT_CONSUMER_PASS");
  }
  private static void check(ProjectData project, Case c) {
    var result = DiagnosticLocations.enrich(project, List.of(c.input)).getFirst();
    require(result.code().equals(c.input.code()) && result.severity() == c.input.severity() && result.message().equals(c.input.message()) && result.jsonPointer().equals(c.input.jsonPointer()), "Enrichment changed diagnostic payload");
    require(Objects.equals(c.expected.source, result.sourceUuid()) && Objects.equals(c.expected.item, result.itemUuid()) && (!c.parameter || Objects.equals(c.expected.parameter, result.parameterKey())), "Pointer inference crossed explicit diagnostic authority");
  }
  private static long snapshotCases() {
    for (boolean reject : new boolean[] {false, true}) {
      var original = issue("/", null, null, null); var source = new ArrayList<>(List.of(original)); var changed = new AtomicBoolean(); var reads = new AtomicInteger(); var p = new ProjectData();
      p.noise2D = new AbstractList<>() {
        public int size() { reads.incrementAndGet(); if (changed.compareAndSet(false, true)) { source.clear(); source.add(Diagnostic.error("LATE", "/", "replacement")); } if (reject) throw new IllegalStateException("serialization"); return 0; }
        public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
      };
      var result = DiagnosticLocations.enrich(p, source); require(reads.get() > 0 && source.getFirst().code().equals("LATE") && result.equals(List.of(original)), "Candidate traversal replaced captured diagnostics");
      try { result.clear(); throw new AssertionError("Mutable diagnostic result"); } catch (UnsupportedOperationException expected) { }
    }
    return 2;
  }
  private static void bits(double expected, double actual) { require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Independent diagnostic-replay field changed"); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
