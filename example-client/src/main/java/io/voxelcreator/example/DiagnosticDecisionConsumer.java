package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay; event receipts remain ordinary JSON without editor dependencies. */
public final class DiagnosticDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (!ProjectIO.JSON
        .readTree(out.resolve("protected-project.json").toFile())
        .equals(ProjectIO.JSON.valueToTree(p)))
      throw new AssertionError("Diagnostic actions changed the complete project");
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Diagnostic actions adopted private Properties");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Diagnostic actions changed source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Diagnostic actions changed EMPTY World");
        }
    }
    if (!ProjectIO.JSON
        .readTree(out.resolve("preferences-before.json").toFile())
        .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Diagnostic actions changed active preferences");
    var report = ProjectIO.JSON.readTree(out.resolve("diagnostic-decisions-native.json").toFile());
    var view = ProjectIO.JSON.readTree(out.resolve("diagnostics-view.json").toFile());
    var entries = view.path("entries");
    var codes = new HashSet<String>();
    for (var e : entries) {
      codes.add(e.path("code").asText());
      if (e.path("code").asText().equals("ALPHA")) {
        if (e.path("id").longValue() != report.path("alpha").longValue()
            || e.path("count").longValue() != 3
            || !e.path("read").asBoolean()
            || !e.path("dismissed").asBoolean()
            || e.path("actionRequired").asBoolean()
            || !e.path("target").path("project").asText().equals(p.project.uuid.toString())
            || !e.path("target")
                .path("resource")
                .asText()
                .equals(p.noise2D.getFirst().uuid.toString()))
          throw new AssertionError("Selected occurrence or UUID source receipt changed");
      }
      if (e.path("code").asText().equals("BETA")
          && (e.path("id").longValue() != report.path("beta").longValue()
              || !e.path("actionRequired").asBoolean()
              || !e.path("read").asBoolean()
              || e.path("dismissed").asBoolean()))
        throw new AssertionError("Bulk reads silently resolved another action");
    }
    if (entries.size() != 9
        || codes.size() != 9
        || !codes.containsAll(
            List.of(
                "ALPHA",
                "BETA",
                "UNRELATED_X",
                "UNRELATED_Y",
                "UNRELATED_Z",
                "PROJECT_VALID",
                "DIAGNOSTIC_COPY_FAILED",
                "DIAGNOSTIC_NAVIGATION_FAILED",
                "DIAGNOSTIC_LOGS_FAILED")))
      throw new AssertionError("Missing retained history or callback failure receipts");
    System.out.println(
        "Diagnostic decisions engine-only PASS; exact project/preferences, retained"
            + " occurrence/source receipts, .125 samples and EMPTY World");
  }
}
