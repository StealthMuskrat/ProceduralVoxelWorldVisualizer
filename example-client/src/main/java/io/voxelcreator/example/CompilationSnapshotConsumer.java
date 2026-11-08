package io.voxelcreator.example;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR-only replay of validation against the definition actually retained. */
public final class CompilationSnapshotConsumer {
  @JsonSerialize(using = CaptureSerializer.class)
  public static final class CapturedNoises extends ArrayList<Noise> {
    final List<Noise> original, captured;
    int serializations;
    CapturedNoises(Noise original, Noise captured) {
      add(original);
      this.original = List.of(original);
      this.captured = List.of(captured);
    }
  }

  public static final class CaptureSerializer extends StdSerializer<CapturedNoises> {
    public CaptureSerializer() { super(CapturedNoises.class); }
    @Override public void serialize(CapturedNoises value, JsonGenerator output, SerializerProvider provider)
        throws IOException {
      value.serializations++;
      // The first copy belongs to live prevalidation; the second is the retained candidate.
      provider.defaultSerializeValue(value.serializations == 1 ? value.original : value.captured, output);
    }
  }

  private record Fixture(ProjectData source, Noise actual, Noise captured, CapturedNoises list) {}
  private record Accepted(CompiledProject compiled, NoiseSampler2D two, NoiseSampler3D three) {}

  private static Fixture fixture(int dimension) {
    var source = new ProjectData(); source.project.seed = 17;
    var actual = noise(dimension, 1); var captured = noise(dimension, .25); captured.uuid = actual.uuid;
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinates", dimension);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree(
        dimension == 2 ? "source+x+2*z" : "source+x+2*z+4*y"));
    captured.processing.add(expression); captured.outputBinding = Binding.earlier(expression.uuid);
    var list = new CapturedNoises(actual, captured);
    if (dimension == 2) source.noise2D = list; else source.noise3D = list;
    return new Fixture(source, actual, captured, list);
  }

  private static Noise noise(int dimension, double value) {
    var noise = new Noise(); noise.id = "field"; noise.dimension = dimension;
    noise.algorithm.key = "CONSTANT"; noise.algorithm.parameters.clear(); noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private static Accepted accept(Fixture f, int dimension, String route) {
    if (route.equals("sampler")) return new Accepted(null,
        dimension == 2 ? new ProjectCompiler().compileNoise2D(f.source, f.actual.uuid, 7) : null,
        dimension == 3 ? new ProjectCompiler().compileNoise3D(f.source, f.actual.uuid, 7) : null);
    CompiledProject compiled;
    if (route.equals("world")) {
      var result = new ProjectCompiler().compileWorld(f.source);
      require(result.success() && result.diagnostics().isEmpty(), "Valid retained candidate rejected");
      compiled = result.compiled();
    } else compiled = new CompiledProject(f.source);
    return new Accepted(compiled, dimension == 2 ? compiled.noise2D(f.actual.uuid, 7) : null,
        dimension == 3 ? compiled.noise3D(f.actual.uuid, 7) : null);
  }

  private static long fields(Accepted accepted, int dimension) {
    long count = 0;
    for (int y : dimension == 2 ? new int[] {0} : new int[] {-1, 2})
      for (int z : new int[] {-2, 0, 7}) for (int x : new int[] {-3, 0, 5}) {
        double expected = .25 + x + 2 * z;
        if (dimension == 3) expected += 4 * y;
        double actual = dimension == 2 ? accepted.two.sample(x, z) : accepted.three.sample(x, y, z);
        require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Retained field arithmetic changed");
        count++;
      }
    return count;
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    long rejected = 0, accepted = 0, fields = 0;
    var records = new ArrayList<Map<String, Object>>(); var retained = new ArrayList<VoxelChunk>();
    for (int dimension : new int[] {2, 3}) for (String route : List.of("constructor", "world", "sampler")) {
      for (String kind : List.of("id", "algorithm", "identity", "binding")) {
        var f = fixture(dimension);
        String code = switch (kind) {
          case "id" -> { f.captured.id = " noncanonical"; yield "INVALID_ID"; }
          case "algorithm" -> { f.captured.algorithm.key = "UNSUPPORTED"; yield "INVALID_NOISE"; }
          case "identity" -> { f.captured.uuid = f.source.project.uuid; yield "DUPLICATE_UUID"; }
          default -> { f.captured.outputBinding = Binding.earlier(UUID.randomUUID()); yield "INVALID_ORDER"; }
        };
        require(Validator.validate(f.source).isEmpty(), "Original fixture is invalid");
        if (route.equals("world")) {
          var result = new ProjectCompiler().compileWorld(f.source);
          require(!result.success() && result.compiled() == null
              && result.diagnostics().stream().anyMatch(issue -> issue.code().equals(code)), "Invalid retained candidate admitted or misdiagnosed");
          try { result.diagnostics().clear(); throw new AssertionError("Mutable diagnostics"); }
          catch (UnsupportedOperationException expected) { }
        } else {
          boolean failed = false;
          try { accept(f, dimension, route); } catch (IllegalArgumentException expected) { failed = true; }
          require(failed, "Invalid retained candidate admitted");
        }
        require(f.list.serializations == 2 && f.actual.id.equals("field")
            && f.actual.algorithm.key.equals("CONSTANT") && f.actual.algorithm.parameters.get("value") == 1.
            && f.actual.processing.isEmpty() && (dimension == 2 ? f.source.noise2D : f.source.noise3D) == f.list,
            "Rejection changed caller ownership or recaptured candidate");
        records.add(Map.of("dimension", dimension, "route", route, "kind", kind, "code", code,
            "callerSerializations", f.list.serializations)); rejected++;
      }
      var f = fixture(dimension); var built = accept(f, dimension, route); fields += fields(built, dimension);
      require(f.list.serializations == 2 && f.actual.processing.isEmpty(), "Accepted capture changed caller");
      f.source.project.seed = -99; f.actual.algorithm.parameters.put("value", 9.);
      f.captured.algorithm.parameters.put("value", -.75); f.captured.processing.clear(); f.captured.id = "later";
      f.list.clear(); fields += fields(built, dimension);
      require(f.list.serializations == 2, "Sampling revisited caller collections");
      if (built.compiled != null) {
        require(built.compiled.defaultSeed() == 17, "Snapshot adopted later caller settings");
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20)) {
          for (int root : new int[] {2, 4}) try (var world = engine.openWorld(built.compiled,
              new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            retained.add(world.generateChunk(new ChunkPos(-1, 0, 1)));
          }
        }
      }
      accepted++;
    }
    long cells = 0;
    for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++)
      for (int x = 0; x < chunk.size(); x++) {
        require(chunk.getVoxelTypeId(x, y, z) == 0, "Caller edits invalidated retained EMPTY chunk"); cells++;
      }
    require(rejected == 24 && accepted == 6 && fields == 162 && retained.size() == 8 && cells == 288,
        "Unexpected compilation-snapshot replay dimensions");
    Files.writeString(out.resolve("compilation-snapshot-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "rejected", rejected, "accepted", accepted, "fieldObservations", fields, "retainedChunks", retained.size(),
        "retainedCells", cells, "roots", List.of(2, 4), "cases", records)));
    System.out.println("COMPILATION_SNAPSHOT_CONSUMER_PASS");
  }

  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
