package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred exact-ID admission and independent field expectations for sampler overloads. */
class SamplerLookupTest {
  private static final String[] IDS = {"Field", "field", "\u00e9"};

  private ProjectData project() {
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

  private NoiseSampler3D field(CompiledProject c, Noise n, long seed, boolean visible) {
    if (n.dimension == 2) {
      var two = visible ? c.noise2D(n.id, seed) : c.noise2D(n.uuid, seed);
      return (x, y, z) -> two.sample(x, z);
    }
    return visible ? c.noise3D(n.id, seed) : c.noise3D(n.uuid, seed);
  }

  @Test
  void missingNullAndWrongDimensionSelectorsUseTheSameTypedRejection() {
    var p = project(); var c = new CompiledProject(p); String before = ProjectIO.JSON.valueToTree(p).toString();
    for (int dimension : new int[] {2, 3}) {
      for (String id : new String[] {"missing", "", "FIELD", " Field", "Field ", "e\u0301", null, "only-" + (5 - dimension)})
        assertThrows(IllegalArgumentException.class, () -> {
          if (dimension == 2) c.noise2D(id, 17); else c.noise3D(id, 17);
        });
      UUID other = (dimension == 2 ? p.noise3D : p.noise2D).getFirst().uuid;
      for (UUID id : new UUID[] {new UUID(0, 0), null, other}) {
        assertThrows(IllegalArgumentException.class, () -> {
          if (dimension == 2) c.noise2D(id, 17); else c.noise3D(id, 17);
        });
        assertThrows(IllegalArgumentException.class, () -> {
          if (dimension == 2) new ProjectCompiler().compileNoise2D(p, id, 17);
          else new ProjectCompiler().compileNoise3D(p, id, 17);
        });
      }
    }
    assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
  }

  @Test
  void exactCaseAndUnicodeAliasesResolveDistinctFieldsInTheirOwnDimension() {
    var p = project(); var c = new CompiledProject(p);
    for (int dimension : new int[] {2, 3}) for (int index = 0; index < 3; index++) {
      var n = (dimension == 2 ? p.noise2D : p.noise3D).get(index);
      for (long seed : new long[] {-17, 0, 103}) {
        var uuid = field(c, n, seed, false); var visible = field(c, n, seed, true);
        for (int x : new int[] {-3, 0, 5}) for (int z : new int[] {-2, 0, 7}) {
          int y = x - z;
          double expected = dimension + index / 4. + x + 2. * z + (dimension == 3 ? 4. * y : 0) + (seed % 97) / 128.;
          assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(uuid.sample(x, y, z)));
          assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(visible.sample(x, y, z)));
        }
      }
    }
  }

  @Test
  void retainedAliasesAndSamplersIgnoreCallerRenamesRemovalAndAlgorithmEdits() {
    var p = project(); var c = new CompiledProject(p);
    var uuid2 = p.noise2D.getFirst().uuid; var uuid3 = p.noise3D.getFirst().uuid;
    var two = c.noise2D("Field", 0); var three = c.noise3D("Field", 0);
    p.noise2D.getFirst().id = "Renamed"; p.noise3D.getFirst().id = "Renamed";
    p.noise2D.getFirst().algorithm.parameters.put("value", 999.);
    p.noise3D.getFirst().algorithm.parameters.put("value", 999.);
    var renamed = new CompiledProject(p);
    assertThrows(IllegalArgumentException.class, () -> c.noise2D("Renamed", 0));
    assertThrows(IllegalArgumentException.class, () -> c.noise3D("Renamed", 0));
    assertThrows(IllegalArgumentException.class, () -> renamed.noise2D("Field", 0));
    assertThrows(IllegalArgumentException.class, () -> renamed.noise3D("Field", 0));
    assertEquals(999., renamed.noise2D(uuid2, 0).sample(0, 0));
    assertEquals(999., renamed.noise3D("Renamed", 0).sample(0, 0, 0));
    p.noise2D.clear(); p.noise3D.clear();
    var empty = new CompiledProject(p);
    assertThrows(IllegalArgumentException.class, () -> empty.noise2D(uuid2, 0));
    assertThrows(IllegalArgumentException.class, () -> empty.noise3D(uuid3, 0));
    assertEquals(2., two.sample(0, 0)); assertEquals(3., three.sample(0, 0, 0));
    assertEquals(2., c.noise2D("Field", 0).sample(0, 0)); assertEquals(3., c.noise3D(uuid3, 0).sample(0, 0, 0));
  }
}
