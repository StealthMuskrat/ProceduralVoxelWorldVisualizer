package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR-only exact-ID admission and independent coordinate-field replay. */
public final class SamplerLookupConsumer {
  private static final String[] IDS = {"Field", "field", "\u00e9"};
  @FunctionalInterface private interface Action { void run(); }
  private record Retained(int dimension, int index, long seed, NoiseSampler2D two, NoiseSampler3D three) {
    double sample(int x, int y, int z) { return two != null ? two.sample(x, z) : three.sample(x, y, z); }
  }

  private static ProjectData project() {
    var p = new ProjectData();
    for (int dimension : new int[] {2, 3}) for (int index = 0; index < 4; index++) {
      var n = new Noise(); n.id = index < 3 ? IDS[index] : "only-" + dimension;
      n.dimension = dimension; n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", dimension + index / 4.);
      var expression = ProcessingRegistry.create("EXPRESSION", "coordinates", dimension);
      expression.parameters.put("expression", ProjectIO.JSON.valueToTree(dimension == 2
          ? "source+x+2*z+(projectSeed%97)/128" : "source+x+2*z+4*y+(projectSeed%97)/128"));
      n.processing.add(expression); n.outputBinding = Binding.earlier(expression.uuid);
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    }
    return p;
  }
  private static Retained capture(ProjectData p, CompiledProject c, Noise n, int index, long seed, int route) {
    if (n.dimension == 2) return new Retained(2, index, seed, switch (route) {
      case 0 -> c.noise2D(n.uuid, seed);
      case 1 -> c.noise2D(n.id, seed);
      default -> new ProjectCompiler().compileNoise2D(p, n.uuid, seed);
    }, null);
    return new Retained(3, index, seed, null, switch (route) {
      case 0 -> c.noise3D(n.uuid, seed);
      case 1 -> c.noise3D(n.id, seed);
      default -> new ProjectCompiler().compileNoise3D(p, n.uuid, seed);
    });
  }
  private static double expected(Retained s, int x, int y, int z) {
    return s.dimension + s.index / 4. + x + 2. * z + (s.dimension == 3 ? 4. * y : 0) + (s.seed % 97) / 128.;
  }
  private static void rejected(Action action) {
    try { action.run(); throw new AssertionError("Unknown/wrong-dimension selector was accepted"); }
    catch (IllegalArgumentException expected) { }
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = project(); var c = new CompiledProject(p);
    String authored = ProjectIO.JSON.writeValueAsString(p); long rejections = 0, fields = 0;
    for (int dimension : new int[] {2, 3}) {
      for (String id : new String[] {"missing", "", "FIELD", " Field", "Field ", "e\u0301", null, "only-" + (5 - dimension)}) {
        rejected(() -> { if (dimension == 2) c.noise2D(id, 17); else c.noise3D(id, 17); }); rejections++;
      }
      var opposite = (dimension == 2 ? p.noise3D : p.noise2D).getFirst().uuid;
      for (UUID id : new UUID[] {new UUID(0, 0), null, opposite}) {
        rejected(() -> { if (dimension == 2) c.noise2D(id, 17); else c.noise3D(id, 17); }); rejections++;
        rejected(() -> {
          if (dimension == 2) new ProjectCompiler().compileNoise2D(p, id, 17);
          else new ProjectCompiler().compileNoise3D(p, id, 17);
        }); rejections++;
      }
    }
    var retained = new ArrayList<Retained>();
    for (int dimension : new int[] {2, 3}) for (int index = 0; index < 3; index++) {
      var n = (dimension == 2 ? p.noise2D : p.noise3D).get(index);
      for (long seed : new long[] {-17, 0, 103}) for (int route = 0; route < 3; route++) {
        var s = capture(p, c, n, index, seed, route); retained.add(s);
        for (int x : new int[] {-3, 0, 5}) for (int z : new int[] {-2, 0, 7}) {
          int y = x - z; bits(expected(s, x, y, z), s.sample(x, y, z)); fields++;
        }
      }
    }
    require(authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Lookup/compilation changed caller JSON");
    var uuid2 = p.noise2D.getFirst().uuid; var uuid3 = p.noise3D.getFirst().uuid;
    for (var n : List.of(p.noise2D.getFirst(), p.noise3D.getFirst())) {
      n.id = "Renamed"; n.algorithm.parameters.put("value", 999.);
    }
    var renamed = new CompiledProject(p);
    rejected(() -> c.noise2D("Renamed", 0)); rejections++;
    rejected(() -> c.noise3D("Renamed", 0)); rejections++;
    rejected(() -> renamed.noise2D("Field", 0)); rejections++;
    rejected(() -> renamed.noise3D("Field", 0)); rejections++;
    bits(999., renamed.noise2D(uuid2, 0).sample(0, 0));
    bits(999., renamed.noise2D("Renamed", 0).sample(0, 0));
    bits(999., renamed.noise3D(uuid3, 0).sample(0, 0, 0));
    bits(999., renamed.noise3D("Renamed", 0).sample(0, 0, 0));
    p.noise2D.clear(); p.noise3D.clear(); var empty = new CompiledProject(p);
    rejected(() -> empty.noise2D(uuid2, 0)); rejections++;
    rejected(() -> empty.noise3D(uuid3, 0)); rejections++;
    rejected(() -> empty.noise2D("Field", 0)); rejections++;
    rejected(() -> empty.noise3D("Field", 0)); rejections++;
    String cleared = ProjectIO.JSON.writeValueAsString(p); long retainedFields = 0;
    for (var s : retained) { bits(expected(s, -7, 3, 2), s.sample(-7, 3, 2)); retainedFields++; }
    require(rejections == 36 && fields == 486 && retained.size() == 54 && retainedFields == 54
        && cleared.equals(ProjectIO.JSON.writeValueAsString(p)), "Lookup replay dimensions/source ownership changed");
    Files.writeString(out.resolve("sampler-lookup-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "typedRejections", rejections, "fields", fields, "retainedSamplers", retained.size(),
        "retainedFields", retainedFields, "renamedControls", 4, "dimensions", List.of(2, 3),
        "routes", List.of("UUID", "visible", "compiler"), "seeds", List.of(-17, 0, 103))));
    System.out.println("SAMPLER_LOOKUP_CONSUMER_PASS");
  }
  private static void bits(double expected, double actual) {
    require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Lookup field raw bits differ");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
