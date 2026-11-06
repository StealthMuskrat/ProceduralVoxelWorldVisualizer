package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred engine-JAR replay of single-pass malformed validation and owned diagnostics. */
public final class ValidationFailureConsumer {
  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }

  static void run(Path out) throws Exception {
    Files.createDirectories(out); var project = new ProjectData(); var noise = new Noise(); noise.id = "validation-recovery";
    noise.algorithm.key = "CONSTANT"; noise.algorithm.parameters.put("value", .25);
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("source+x+2*z")); noise.processing.add(expression); noise.outputBinding = Binding.earlier(expression.uuid);
    project.noise2D.add(noise); var healthy = project.noise2D; String authored = ProjectIO.JSON.writeValueAsString(project); var before = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>(); long cases = 0, reads = 0, accesses = 0, fields = 0, beforeFields = 0;
    for (boolean compile : new boolean[] {false, true}) for (String mode : List.of("MUTABLE", "RUNTIME", "ERROR", "NULL", "BLANK", "LONG", "ASTRAL", "LIMIT")) {
      var traversals = new AtomicInteger(); var messages = new AtomicInteger();
      var failure = new RuntimeException() {
        public String getMessage() {
          int read = messages.incrementAndGet(); return switch (mode) {
            case "RUNTIME" -> throw new IllegalStateException("reader"); case "ERROR" -> throw new AssertionError("reader");
            case "NULL" -> null; case "BLANK" -> " \t"; case "LONG" -> "雪".repeat(3000);
            case "ASTRAL" -> "😀".repeat(2000); case "LIMIT" -> "😀".repeat(1024); default -> "message-" + read;
          };
        }
        public String toString() { throw new AssertionError("Host toString called"); }
      };
      project.noise2D = new AbstractList<>() {
        public int size() { traversals.incrementAndGet(); throw failure; }
        public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
      };
      try {
        bits(1.25, before.noise2D(noise.uuid, before.defaultSeed()).sample(-3, 2)); beforeFields++;
        List<Diagnostic> issues;
        if (compile) { var result = new ProjectCompiler().compileWorld(project); require(!result.success() && result.compiled() == null, "Malformed candidate compiled"); issues = result.diagnostics(); }
        else issues = Validator.validate(project);
        String detail = switch (mode) {
          case "LONG" -> "雪".repeat(2047) + "…"; case "ASTRAL" -> "😀".repeat(1023) + "…";
          case "LIMIT" -> "😀".repeat(1024); case "MUTABLE" -> "message-1"; default -> "Null or malformed value";
        };
        require(issues.equals(List.of(Diagnostic.error("INVALID_STRUCTURE", "/", detail))) && traversals.get() == 1 && messages.get() == 1,
            "Structural reporting retraversed the candidate or lost its one frozen message");
        immutable(issues); require(failure.getSuppressed().length == 0, "Validation mutated a host failure");
      } finally { project.noise2D = healthy; }
      require(authored.equals(ProjectIO.JSON.writeValueAsString(project)), "Malformed validation changed authored values");
      var repaired = new ProjectCompiler().compileWorld(project); require(repaired.success() && repaired.diagnostics().isEmpty(), "Repair did not restore compilation");
      var sampler = repaired.compiled().noise2D(noise.uuid, repaired.compiled().defaultSeed());
      for (int z = -1; z <= 1; z++) for (int x = -2; x <= 2; x++) { bits(.25 + x + 2 * z, sampler.sample(x, z)); fields++; }
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20)) {
        for (int root : new int[] {2, 4}) try (var world = engine.openWorld(repaired.compiled(), new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          retained.add(world.generateChunk(new ChunkPos(-1, 0, 1)));
        }
      }
      require(traversals.get() == 1 && messages.get() == 1 && authored.equals(ProjectIO.JSON.writeValueAsString(project)), "Repair changed retained failure/authoring ownership");
      cases++; accesses += traversals.get(); reads += messages.get();
    }
    var issue = Diagnostic.error("INVALID_STRUCTURE", "/", "original"); var list = new ArrayList<>(List.of(issue));
    var loaded = new ProjectIO.LoadResult(null, list); var compiled = new ProjectCompiler.CompileResult(null, list); list.clear();
    require(!loaded.success() && !compiled.success() && loaded.diagnostics().equals(List.of(issue)) && compiled.diagnostics().equals(List.of(issue)), "Caller list mutation rewrote a failed result");
    immutable(loaded.diagnostics()); immutable(compiled.diagnostics());
    var empty = new ArrayList<Diagnostic>(); var accepted = new ProjectIO.LoadResult(project, empty); var built = new ProjectCompiler.CompileResult(before, empty); empty.add(issue);
    require(accepted.success() && built.success() && accepted.diagnostics().isEmpty() && built.diagnostics().isEmpty() && accepted.project() == project && built.compiled() == before, "Diagnostic copying changed accepted project/compiled identity");
    immutable(accepted.diagnostics()); immutable(built.diagnostics());
    long cells = 0; for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++) for (int x = 0; x < chunk.size(); x++) {
      require(chunk.getVoxelTypeId(x, y, z) == 0, "Validation failure invalidated a recovered EMPTY snapshot"); cells++;
    }
    require(cases == 16 && accesses == 16 && reads == 16 && fields == 240 && beforeFields == 16 && retained.size() == 32 && cells == 1152, "Unexpected validation-failure replay dimensions");
    Files.writeString(out.resolve("validation-failure-report.json"), ProjectIO.JSON.writeValueAsString(Map.of("cases", cases, "candidateAccesses", accesses,
        "messageReads", reads, "repairedFields", fields, "retainedFields", beforeFields, "resultCopies", 4, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4))));
    System.out.println("VALIDATION_FAILURE_CONSUMER_PASS");
  }
  private static void immutable(List<Diagnostic> diagnostics) {
    try { diagnostics.add(Diagnostic.error("caller", "/", "changed")); throw new AssertionError("Mutable result diagnostics"); } catch (UnsupportedOperationException expected) { }
  }
  private static void bits(double expected, double actual) { require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Independent validation-recovery field changed"); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
