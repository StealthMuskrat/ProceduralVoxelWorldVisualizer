package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred public admission checks against serialized values differing from the caller model. */
class CompilationSnapshotTest {
  @JsonSerialize(using = CaptureSerializer.class)
  public static final class CapturedNoises extends ArrayList<Noise> {
    final List<Noise> captured;
    final List<Noise> original;
    int serializations;
    CapturedNoises(Noise source, Noise captured) {
      add(source);
      this.original = List.of(source);
      this.captured = new ArrayList<>(List.of(captured));
    }
  }

  public static final class CaptureSerializer extends StdSerializer<CapturedNoises> {
    public CaptureSerializer() { super(CapturedNoises.class); }
    @Override public void serialize(CapturedNoises value, JsonGenerator output, SerializerProvider provider)
        throws IOException {
      value.serializations++;
      // NoiseCompiler prevalidation copies the caller first. Diverge only at retained capture.
      provider.defaultSerializeValue(value.serializations == 1 ? value.original : value.captured, output);
    }
  }

  private record Fixture(ProjectData source, Noise actual, Noise captured, CapturedNoises list) {}

  private Noise noise(int dimension, double value) {
    var noise = new Noise();
    noise.id = "field";
    noise.dimension = dimension;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private Fixture fixture(int dimension) {
    var source = new ProjectData();
    source.project.seed = 17;
    var actual = noise(dimension, 1);
    var captured = noise(dimension, .25);
    captured.uuid = actual.uuid;
    var list = new CapturedNoises(actual, captured);
    if (dimension == 2) source.noise2D = list;
    else source.noise3D = list;
    return new Fixture(source, actual, captured, list);
  }

  @Test
  void everyPublicEntryRejectsInvalidOwnedIdsAlgorithmsIdentitiesAndBindings() {
    for (int dimension : new int[] {2, 3}) for (int kind = 0; kind < 4; kind++) {
      var f = fixture(dimension);
      String code = switch (kind) {
        case 0 -> { f.captured.id = " noncanonical"; yield "INVALID_ID"; }
        case 1 -> { f.captured.algorithm.key = "UNSUPPORTED"; yield "INVALID_NOISE"; }
        case 2 -> { f.captured.uuid = f.source.project.uuid; yield "DUPLICATE_UUID"; }
        default -> { f.captured.outputBinding = Binding.earlier(UUID.randomUUID()); yield "INVALID_ORDER"; }
      };
      assertTrue(Validator.validate(f.source).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> new CompiledProject(f.source));
      assertEquals(2, f.list.serializations);
      f.list.serializations = 0;
      var result = new ProjectCompiler().compileWorld(f.source);
      assertFalse(result.success());
      assertNull(result.compiled());
      assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals(code)));
      assertThrows(UnsupportedOperationException.class, result.diagnostics()::clear);
      assertEquals(2, f.list.serializations);
      f.list.serializations = 0;
      assertThrows(IllegalArgumentException.class, () -> {
        if (dimension == 2) new ProjectCompiler().compileNoise2D(f.source, f.actual.uuid, 3);
        else new ProjectCompiler().compileNoise3D(f.source, f.actual.uuid, 3);
      });
      assertEquals(2, f.list.serializations);
      assertSame(f.list, dimension == 2 ? f.source.noise2D : f.source.noise3D);
      assertEquals("field", f.actual.id);
      assertEquals("CONSTANT", f.actual.algorithm.key);
      assertEquals(1., f.actual.algorithm.parameters.get("value").doubleValue());
    }
  }

  @Test
  void acceptedSnapshotsUseOwnedValuesOnceAndIgnoreLaterCallerAndCaptureEdits() {
    for (int dimension : new int[] {2, 3}) for (boolean compiler : new boolean[] {false, true}) {
      var f = fixture(dimension);
      var result = compiler ? new ProjectCompiler().compileWorld(f.source) : null;
      var compiled = compiler ? result.compiled() : new CompiledProject(f.source);
      assertNotNull(compiled);
      assertEquals(2, f.list.serializations);
      UUID id = f.actual.uuid;
      NoiseSampler2D two = dimension == 2 ? compiled.noise2D(id, 7) : null;
      NoiseSampler3D three = dimension == 3 ? compiled.noise3D(id, 7) : null;
      f.source.project.seed = -99;
      f.actual.algorithm.parameters.put("value", 9.);
      f.captured.algorithm.parameters.put("value", -.75);
      f.captured.id = "later";
      f.list.clear();
      assertEquals(17, compiled.defaultSeed());
      for (int x : new int[] {-17, 0, 23})
        assertEquals(Double.doubleToRawLongBits(.25), Double.doubleToRawLongBits(
            dimension == 2 ? two.sample(x, -3) : three.sample(x, 5, -3)));
      assertEquals(2, f.list.serializations);
    }
  }

  @Test
  void capturedDisabledIncompleteExpressionsRemainLegalUntilEnabled() {
    var f = fixture(2);
    var item = ProcessingRegistry.create("EXPRESSION", "experiment", 2);
    item.enabled = false;
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+"));
    f.captured.processing.add(item);
    assertEquals(.25, new CompiledProject(f.source).noise2D(f.actual.uuid, 0).sample(3, -5));
    item.enabled = true;
    f.list.serializations = 0;
    var result = new ProjectCompiler().compileWorld(f.source);
    assertFalse(result.success());
    assertFalse(result.diagnostics().isEmpty());
    assertFalse(f.actual.processing.contains(item));
  }

  @Test
  void invalidCallerCannotBeSilentlyRepairedByItsSerializedRepresentation() {
    var f = fixture(2);
    f.actual.id = " invalid";
    assertTrue(Validator.validate(f.source).stream()
        .anyMatch(issue -> issue.code().equals("INVALID_ID")));
    assertThrows(IllegalArgumentException.class, () -> new CompiledProject(f.source));
    var result = new ProjectCompiler().compileWorld(f.source);
    assertFalse(result.success());
    assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("INVALID_ID")));
    assertEquals(" invalid", f.actual.id);
    assertEquals("field", f.captured.id);
  }

  @Test
  void malformedCallerStillRejectsAfterOneTraversalWithoutAttemptingCapture() {
    for (int entry = 0; entry < 3; entry++) {
      var source = new ProjectData();
      var accesses = new AtomicInteger();
      source.noise2D = new AbstractList<>() {
        @Override public int size() { accesses.incrementAndGet(); throw new IllegalStateException("malformed"); }
        @Override public Noise get(int index) { throw new AssertionError("Unexpected element read"); }
      };
      if (entry == 0) assertThrows(IllegalArgumentException.class, () -> new CompiledProject(source));
      else if (entry == 1) {
        var result = new ProjectCompiler().compileWorld(source);
        assertFalse(result.success());
        assertEquals("INVALID_STRUCTURE", result.diagnostics().getFirst().code());
      } else assertThrows(IllegalArgumentException.class,
          () -> new ProjectCompiler().compileNoise2D(source, UUID.randomUUID(), 0));
      assertEquals(1, accesses.get());
    }
  }
}
