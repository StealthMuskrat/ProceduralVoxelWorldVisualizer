package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred engine-JAR arithmetic, strided batch, trace and concurrent sharing replay. */
public final class SemanticSharingConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    var out = Path.of(args[1]);
    Files.createDirectories(out);
    var compiled = new CompiledProject(p);
    var compiler = new NoiseCompiler(p, p.project.seed);
    var lines = new StringBuilder("phase\tresource\tx\tz\tvalue\tbits\n");
    var sharing = new LinkedHashMap<String, Integer>();
    var executed = new LinkedHashMap<String, List<UUID>>();
    int observations = 0;
    for (var noise : p.noise2D) {
      var sampler = compiled.noise2D(noise.uuid, p.project.seed);
      int expectedSharing = noise.id.equals("diamond") ? 2 : 1;
      int actualSharing = compiler.statistics(noise.uuid).sharedItems();
      if (actualSharing != expectedSharing)
        throw new AssertionError("Sharing statistics " + noise.id);
      sharing.put(noise.id, actualSharing);
      for (int x = -8; x <= 8; x++)
        for (int z = -8; z <= 8; z++) {
          double value = sampler.sample(x, z);
          check(noise.id, x, z, value);
          row(lines, "direct", noise.id, x, z, value);
          observations++;
        }
      var grid = new double[32];
      Arrays.fill(grid, -123456789);
      sampler.sampleGrid(-3, 2, 5, 4, .5, -.25, grid, 2, 7, new CancellationToken());
      var written = new boolean[32];
      for (int z = 0; z < 4; z++)
        for (int x = 0; x < 5; x++) {
          int address = 2 + z * 7 + x;
          written[address] = true;
          double px = -3 + x * .5, pz = 2 - z * .25;
          check(noise.id, px, pz, grid[address]);
          row(lines, "grid", noise.id, px, pz, grid[address]);
          observations++;
        }
      for (int i = 0; i < grid.length; i++)
        if (!written[i] && grid[i] != -123456789) throw new AssertionError("Grid padding");
      var trace = compiler.explain(noise.uuid, 3, 0, -2);
      check(noise.id, 3, -2, trace.value());
      var ids =
          trace.events().stream()
              .filter(e -> e.resourceUuid().equals(noise.uuid) && e.phase().equals("PROCESSING"))
              .map(NoiseTrace.Event::itemUuid)
              .toList();
      var expectedIds =
          noise.id.equals("diamond")
              ? List.of(
                  noise.processing.get(0).uuid,
                  noise.processing.get(2).uuid,
                  noise.processing.get(4).uuid)
              : List.of(noise.processing.get(0).uuid, noise.processing.get(2).uuid);
      if (!ids.equals(expectedIds)) throw new AssertionError("Executed trace identity " + noise.id);
      executed.put(noise.id, ids);
    }
    var sampler = compiled.noise2D("diamond", p.project.seed);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Double>>();
      for (int i = -64; i < 64; i++) {
        final int x = i;
        jobs.add(() -> sampler.sample(x, 3));
      }
      var values = workers.invokeAll(jobs);
      for (int i = 0; i < values.size(); i++) {
        int x = i - 64;
        double value = values.get(i).get();
        check("diamond", x, 3, value);
        row(lines, "concurrent", "diamond", x, 3, value);
        observations++;
      }
    }
    if (observations != 746) throw new AssertionError("Observation count");
    Files.writeString(out.resolve("semantic-sharing-values.tsv"), lines);
    Files.writeString(
        out.resolve("semantic-sharing-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "observations",
                observations,
                "sharing",
                sharing,
                "executed",
                executed,
                "paddingPreserved",
                true)));
    new ProjectIO().save(out.resolve("semantic-sharing-project.json"), p);
    System.out.println(
        "SEMANTIC_SHARING_ENGINE_ONLY_PASS 746 exact observations / ambient diamond / projected"
            + " reference / grids / trace identity / concurrency");
  }

  private static void check(String id, double x, double z, double actual) {
    double expected = id.equals("diamond") ? 2 * (x + 2) * (x + 2) : 2 * x + 8 * z + 14;
    if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected))
      throw new AssertionError(id + " at " + x + "," + z + ": " + actual);
  }

  private static void row(
      StringBuilder out, String phase, String id, double x, double z, double value) {
    out.append(phase)
        .append('\t')
        .append(id)
        .append('\t')
        .append(x)
        .append('\t')
        .append(z)
        .append('\t')
        .append(value)
        .append('\t')
        .append(Long.toUnsignedString(Double.doubleToRawLongBits(value), 16))
        .append('\n');
  }
}
