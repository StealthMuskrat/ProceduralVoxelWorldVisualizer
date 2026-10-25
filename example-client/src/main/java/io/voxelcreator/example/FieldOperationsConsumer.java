package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Independent mathematical oracle compiled against the engine artifact alone. */
public final class FieldOperationsConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    require(loaded.success(), loaded.diagnostics().toString());
    var p = loaded.project();
    var expected = args.length > 1 ? Double.parseDouble(args[1]) : 37.5;
    equal(expected, new CompiledProject(p).noise2D("fields2", 0).sample(2, 3));
    var observations = new LinkedHashMap<String, Double>();
    for (int dimension : new int[] {2, 3}) {
      var n = (dimension == 2 ? p.noise2D : p.noise3D).getFirst();
      for (var item : List.copyOf(n.processing)) {
        if (!ProcessingExtensions.DETAILS.containsKey(item.kind)) continue;
        if (item.kind.equals("GRADIENT")) {
          var length = ProcessingRegistry.create("LENGTH", "gradient-length", dimension);
          var b = Binding.earlier(item.uuid);
          b.type = dimension == 2 ? "Vector2" : "Vector3";
          length.inputs.put("a", b);
          n.processing.add(length);
          n.outputBinding = Binding.earlier(length.uuid);
          double value = new NoiseCompiler(p, 0).compile(n.uuid).sample(2, 1, 3);
          equal(StrictMath.sqrt(dimension == 2 ? 340 : 356), value);
          observations.put(dimension + "/" + item.kind, value);
          n.processing.removeLast();
        } else if (item.kind.equals("LAPLACIAN")) {
          n.outputBinding = Binding.earlier(item.uuid);
          double value = new NoiseCompiler(p, 0).compile(n.uuid).sample(-17, 5, 31);
          equal(dimension == 2 ? 8 : 12, value);
          observations.put(dimension + "/" + item.kind, value);
        } else if (ProcessingExtensions.SHAPES.contains(item.kind)) {
          n.outputBinding = Binding.earlier(item.uuid);
          double value = new NoiseCompiler(p, 0).compile(n.uuid).sample(0, 0, 0);
          equal(
              switch (item.kind) {
                case "PLANE" -> 0;
                case "CAPSULE" -> -4;
                case "TORUS" -> 12;
                default -> -16;
              },
              value);
          observations.put(dimension + "/" + item.kind, value);
        }
      }
    }
    if (args.length > 2) {
      var output = Path.of(args[2]);
      Files.createDirectories(output);
      Files.writeString(
          output.resolve("independent-field-values.json"),
          ProjectIO.JSON.writeValueAsString(observations));
      // Export generated contracts for independent Python checks and durable repository
      // synchronization.
      ContractGenerator.write(output.resolve("contracts"));
    }
    System.out.println(
        "FIELD_OPERATIONS_ENGINE_ONLY_PASS independent quadratic derivatives / exact"
            + " signed-distance centers / 2D-3D signatures / saved native edits");
  }

  private static void equal(double a, double b) {
    if (StrictMath.abs(a - b) > 1e-10) throw new AssertionError(a + " != " + b);
  }

  private static void require(boolean b, String m) {
    if (!b) throw new AssertionError(m);
  }
}
