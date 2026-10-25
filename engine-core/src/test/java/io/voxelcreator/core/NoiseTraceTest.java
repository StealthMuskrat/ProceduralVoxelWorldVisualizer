package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NoiseTraceTest {
  private final ProjectData p = new ProjectData();

  private Noise noise(int dimension) {
    var n = new Noise();
    n.dimension = dimension;
    n.id = "noise" + dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 2.);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return n;
  }

  private Item add(Noise n, String kind) {
    var i = ProcessingRegistry.create(kind, "item" + n.processing.size(), n.dimension);
    n.processing.add(i);
    if (i.resultType.equals("Scalar")) n.outputBinding = Binding.earlier(i.uuid);
    return i;
  }

  private Item expression(Noise n, String text) {
    var i = add(n, "EXPRESSION");
    i.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    return i;
  }

  @Test
  void exactCanonicalValueAndDependencyOrderAreObserved() {
    var n = noise(2);
    var e = expression(n, "source+2*x+z");
    var m = add(n, "MULTIPLY");
    m.inputs.put("a", Binding.earlier(e.uuid));
    m.inputs.put("b", Binding.constant(2));
    var c = new NoiseCompiler(p, 7);
    var t = c.explain(n.uuid, 2, 0, 3);
    assertEquals(18, t.value());
    assertEquals(c.compile(n.uuid).sample(2, 0, 3), t.value());
    assertEquals(
        List.of("SOURCE", "PROCESSING", "PROCESSING", "OUTPUT"),
        t.events().stream().map(NoiseTrace.Event::phase).toList());
    assertEquals(List.of(9.), t.events().get(1).values());
    assertEquals(m.uuid, t.events().getLast().itemUuid());
  }

  @Test
  void transformsKeepActiveComponentOrderAndActualSampleLocation() {
    var n = noise(2);
    var t = new Item();
    t.id = "move";
    t.kind = "TRANSLATE";
    t.resultType = "Coordinates2D";
    t.parameters.put("x", ProjectIO.JSON.valueToTree(1));
    n.coordinateTransforms.add(t);
    expression(n, "source+x");
    var trace = new NoiseCompiler(p, 0).explain(n.uuid, 2, 0, 3);
    assertEquals(5, trace.value());
    assertEquals(List.of(3., 3.), trace.events().getFirst().values());
    assertEquals(2, trace.events().getFirst().x());
    assertEquals(3, trace.events().get(1).x());
  }

  @Test
  void threeDimensionalValuesAreOwnedAndTyped() {
    var n = noise(3);
    var vector = add(n, "COMPOSE3");
    vector.inputs.put("x", Binding.constant(2));
    vector.inputs.put("y", Binding.constant(3));
    vector.inputs.put("z", Binding.constant(6));
    var length = add(n, "LENGTH");
    var b = Binding.earlier(vector.uuid);
    b.type = "Vector3";
    length.inputs.put("a", b);
    var trace = new NoiseCompiler(p, 0).explain(n.uuid, 0, 0, 0);
    assertEquals(7, trace.value());
    assertEquals("FOLDED", trace.events().getFirst().phase());
    assertEquals(List.of(7.), trace.events().getFirst().values());
    assertThrows(UnsupportedOperationException.class, () -> trace.events().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> trace.events().getFirst().values().clear());
  }

  @Test
  void disabledAndUnselectedNumericFailuresAreNotInventedAsExecutions() {
    var n = noise(2);
    var bad = expression(n, "1/0");
    var select = add(n, "SELECT");
    select.inputs.get("condition").value = ProjectIO.JSON.valueToTree(false);
    select.inputs.put("true", Binding.earlier(bad.uuid));
    select.inputs.put("false", Binding.constant(7));
    var trace = new NoiseCompiler(p, 0).explain(n.uuid, 0, 0, 0);
    assertEquals(7, trace.value());
    assertTrue(trace.events().stream().noneMatch(e -> bad.uuid.equals(e.itemUuid())));
    bad.enabled = false;
    select.inputs.put("true", Binding.constant(3));
    assertEquals(7, new NoiseCompiler(p, 0).explain(n.uuid, 0, 0, 0).value());
  }

  @Test
  void failedReplayDoesNotChangeAnExistingSamplerOrPublishSuccess() {
    var n = noise(2);
    expression(n, "x==0 ? 3 : 1/0");
    var c = new NoiseCompiler(p, 0);
    var f = c.compile(n.uuid);
    assertThrows(EvaluationFailure.class, () -> c.explain(n.uuid, 1, 0, 0));
    assertEquals(3, f.sample(0, 0, 0));
    assertEquals(3, c.explain(n.uuid, 0, 0, 0).value());
  }

  @Test
  void everyRetentionLimitPreservesTheCompleteNumericalResult() {
    var n = noise(2);
    expression(n, "source+x");
    var c = new NoiseCompiler(p, 0);
    for (int limit = 0; limit <= 4; limit++) {
      var t = c.explain(n.uuid, 5, 0, 0, limit);
      assertEquals(7, t.value());
      assertEquals(Math.min(3, limit), t.events().size());
      assertEquals(Math.max(0, 3 - limit), t.omitted());
    }
    assertThrows(IllegalArgumentException.class, () -> c.explain(n.uuid, 0, 0, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> c.explain(n.uuid, 0, 0, 0, 4097));
  }

  @Test
  void sampledPrefixesRetainTheirNeighborCoordinates() {
    var n = noise(2);
    var e = expression(n, "x*x");
    var d = add(n, "DERIVATIVE");
    d.inputs.put("field", Binding.earlierField(e.uuid));
    var t = new NoiseCompiler(p, 0).explain(n.uuid, 2, 0, 3);
    assertEquals(4, t.value());
    var xs =
        t.events().stream()
            .filter(v -> e.uuid.equals(v.itemUuid()))
            .map(NoiseTrace.Event::x)
            .toList();
    assertEquals(List.of(3., 1.), xs);
  }

  @Test
  void referencesKeepTheirOwnResourceAndOutputIdentity() {
    var target = noise(3);
    var e = expression(target, "x+y+z");
    var caller = noise(2);
    caller.id = "caller";
    var ref = add(caller, "NOISE_REFERENCE");
    var b = Binding.noiseField(target.uuid);
    b.slice = 7;
    ref.inputs.put("field", b);
    var t = new NoiseCompiler(p, 0).explain(caller.uuid, 2, 0, 3);
    assertEquals(12, t.value());
    assertTrue(
        t.events().stream()
            .anyMatch(v -> v.resourceUuid().equals(target.uuid) && e.uuid.equals(v.itemUuid())));
    assertEquals(caller.uuid, t.events().getLast().resourceUuid());
  }

  @Test
  void sourceDefinitionsAndPreviousTracesRemainImmutable() {
    var n = noise(2);
    var e = expression(n, "source+x");
    var c = new NoiseCompiler(p, 0);
    var first = c.explain(n.uuid, 5, 0, 0);
    e.id = "changed";
    e.parameters.put("expression", ProjectIO.JSON.valueToTree("0"));
    assertEquals(7, c.explain(n.uuid, 5, 0, 0).value());
    assertEquals("item0", first.events().get(1).id());
  }

  @Test
  void ordinaryAndObservedEvaluationPreserveSignedZeroAndBoundaryBits() {
    var n = noise(2);
    expression(n, "x");
    var c = new NoiseCompiler(p, 0);
    var f = c.compile(n.uuid);
    for (double x : new double[] {-0., 0., -32, 31.5, 1e100})
      assertEquals(
          Double.doubleToRawLongBits(f.sample(x, 0, 3)),
          Double.doubleToRawLongBits(c.explain(n.uuid, x, 0, 3).value()));
  }

  @Test
  void concurrentExplanationsDoNotMixCoordinatesOrRetention() throws Exception {
    var n = noise(2);
    expression(n, "source+x+z");
    var c = new NoiseCompiler(p, 0);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<?>>();
      for (int w = 0; w < 4; w++) {
        int x = w;
        futures.add(
            pool.submit(
                () -> {
                  var t = c.explain(n.uuid, x, 0, 10, x);
                  assertEquals(12 + x, t.value());
                  assertTrue(t.events().stream().allMatch(e -> e.x() == x));
                  assertEquals(Math.min(3, x), t.events().size());
                }));
      }
      for (var future : futures) future.get();
    }
  }

  @Test
  void eventConstructorCopiesCallerOwnedLists() {
    var values = new ArrayList<>(List.of(1.));
    var event =
        new NoiseTrace.Event(
            UUID.randomUUID(), null, "id", "SOURCE", "CONSTANT", "Scalar", 0, 0, 0, values);
    var events = new ArrayList<>(List.of(event));
    var trace = new NoiseTrace(event.resourceUuid(), 0, 0, 0, 1, events, 0);
    values.clear();
    events.clear();
    assertEquals(List.of(1.), trace.events().getFirst().values());
  }
}
