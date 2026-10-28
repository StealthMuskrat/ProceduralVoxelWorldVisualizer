package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SemanticSharingTest {
  private final ProjectData project = new ProjectData();
  private final Noise noise = noise(2, "semantic");

  private Noise noise(int dimension, String id) {
    var n = new Noise();
    n.id = id;
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.0);
    (dimension == 2 ? project.noise2D : project.noise3D).add(n);
    return n;
  }

  private Item item(Noise n, String kind, String id) {
    var item = ProcessingRegistry.create(kind, id, n.dimension);
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    return item;
  }

  private Item binary(String kind, String id, Binding a, Binding b) {
    var item = item(noise, kind, id);
    item.inputs.put("a", a);
    item.inputs.put("b", b);
    return item;
  }

  private static Binding vector(UUID id) {
    var value = Binding.earlier(id);
    value.type = "Vector2";
    return value;
  }

  private static Binding ambient(String name) {
    var b = new Binding();
    b.sourceKind = "Ambient Value";
    b.ambientKey = name;
    return b;
  }

  private static Binding reference(Noise n, String projection, double slice) {
    var b = new Binding();
    b.sourceKind = "Noise Generator Reference";
    b.noiseGeneratorUuid = n.uuid;
    b.projection = projection;
    b.slice = slice;
    return b;
  }

  private Item expression(Noise n, String source) {
    var e = item(n, "EXPRESSION", "expression");
    e.parameters.put("expression", ProjectIO.JSON.valueToTree(source));
    return e;
  }

  private List<Item> diamond() {
    var a = binary("ADD", "a", ambient("x"), Binding.constant(2));
    var b = binary("ADD", "b", ambient("x"), Binding.constant(2));
    var aa = binary("MULTIPLY", "aa", Binding.earlier(a.uuid), Binding.earlier(a.uuid));
    var bb = binary("MULTIPLY", "bb", Binding.earlier(b.uuid), Binding.earlier(b.uuid));
    var sum = binary("ADD", "sum", Binding.earlier(aa.uuid), Binding.earlier(bb.uuid));
    return List.of(a, b, aa, bb, sum);
  }

  @Test
  void equivalentAmbientInputsAndSharedResultsPropagateThroughADiamond() {
    diamond();
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    assertEquals(2, compiler.statistics(noise.uuid).sharedItems());
    for (int x = -20; x <= 20; x++)
      for (int z = -3; z <= 3; z++) assertEquals(2.0 * (x + 2) * (x + 2), field.sample(x, 0, z));
  }

  @Test
  void replayNamesTheFirstActuallyExecutedOccurrenceInEitherOperandOrder() {
    var items = diamond();
    var sum = items.getLast();
    var compiler = new NoiseCompiler(project, 0);
    var trace = compiler.explain(noise.uuid, 3, 0, 0);
    assertEquals(
        List.of(items.get(0).uuid, items.get(2).uuid, sum.uuid),
        trace.events().stream()
            .filter(e -> e.phase().equals("PROCESSING"))
            .map(NoiseTrace.Event::itemUuid)
            .toList());
    sum.inputs.put("a", Binding.earlier(items.get(3).uuid));
    sum.inputs.put("b", Binding.earlier(items.get(2).uuid));
    trace = new NoiseCompiler(project, 0).explain(noise.uuid, 3, 0, 0);
    assertEquals(
        List.of(items.get(1).uuid, items.get(3).uuid, sum.uuid),
        trace.events().stream()
            .filter(e -> e.phase().equals("PROCESSING"))
            .map(NoiseTrace.Event::itemUuid)
            .toList());
    assertEquals(50, trace.value());
  }

  @Test
  void distinctAmbientNamesAndOperandOrderStayDistinct() {
    var a = binary("DIVIDE", "a", ambient("x"), ambient("z"));
    var b = binary("DIVIDE", "b", ambient("z"), ambient("x"));
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    assertEquals(0, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(2, field.sample(2, 0, 4));
    assertEquals(
        b.uuid, assertThrows(EvaluationFailure.class, () -> field.sample(0, 0, 1)).itemUuid);
    noise.outputBinding = Binding.earlier(a.uuid);
    assertEquals(.5, new NoiseCompiler(project, 0).compile(noise.uuid).sample(2, 0, 4));
  }

  @Test
  void rawLiteralSignedZeroNeverAliases() {
    var a = binary("MULTIPLY", "positive", ambient("x"), Binding.constant(0.0));
    var b = binary("MULTIPLY", "negative", ambient("x"), Binding.constant(-0.0));
    var compiler = new NoiseCompiler(project, 0);
    assertEquals(0, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(
        Double.doubleToRawLongBits(-0.0),
        Double.doubleToRawLongBits(compiler.compile(noise.uuid).sample(1, 0, 0)));
    noise.outputBinding = Binding.earlier(a.uuid);
    assertEquals(
        Double.doubleToRawLongBits(0.0),
        Double.doubleToRawLongBits(
            new NoiseCompiler(project, 0).compile(noise.uuid).sample(1, 0, 0)));
    assertNotEquals(a.uuid, b.uuid);
  }

  @Test
  void equalProjectedReferencesShareWhileAnotherProjectionDoesNot() {
    var target = noise(3, "reference");
    expression(target, "x+2*y+4*z");
    var a = binary("ADD", "a", reference(target, "XZ", 3), Binding.constant(1));
    var b = binary("ADD", "b", reference(target, "XZ", 3), Binding.constant(1));
    binary("ADD", "other-projection", reference(target, "XY", 3), Binding.constant(1));
    binary("ADD", "sum", Binding.earlier(a.uuid), Binding.earlier(b.uuid));
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    assertEquals(1, compiler.statistics(noise.uuid).sharedItems());
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) assertEquals(2 * x + 8 * z + 14, field.sample(x, 0, z));
  }

  @Test
  void referenceResourceIdentityKeepsDifferentSeedDomainsSeparate() {
    var first = noise(3, "first");
    expression(first, "noiseGeneratorSeed");
    var second = noise(3, "second");
    expression(second, "noiseGeneratorSeed");
    var a = binary("ADD", "a", reference(first, "XZ", 0), Binding.constant(1));
    var b = binary("ADD", "b", reference(second, "XZ", 0), Binding.constant(1));
    binary("SUBTRACT", "difference", Binding.earlier(a.uuid), Binding.earlier(b.uuid));
    var compiler = new NoiseCompiler(project, 17);
    assertEquals(0, compiler.statistics(noise.uuid).sharedItems());
    double expected =
        ((double) CoordinateHash.seed(17, first.uuid, 0) + 1)
            - ((double) CoordinateHash.seed(17, second.uuid, 0) + 1);
    assertEquals(expected, compiler.compile(noise.uuid).sample(0, 0, 0));
  }

  @Test
  void rawProjectedSliceSignedZeroNeverAliases() {
    var target = noise(3, "slice");
    expression(target, "y");
    var a = binary("ADD", "positive", reference(target, "XZ", 0.0), Binding.constant(-0.0));
    binary("ADD", "negative", reference(target, "XZ", -0.0), Binding.constant(-0.0));
    var compiler = new NoiseCompiler(project, 0);
    assertEquals(0, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(
        Double.doubleToRawLongBits(-0.0),
        Double.doubleToRawLongBits(compiler.compile(noise.uuid).sample(1, 0, 1)));
    noise.outputBinding = Binding.earlier(a.uuid);
    assertEquals(
        Double.doubleToRawLongBits(0.0),
        Double.doubleToRawLongBits(
            new NoiseCompiler(project, 0).compile(noise.uuid).sample(1, 0, 1)));
  }

  @Test
  void failedFirstOccurrenceKeepsItsOwnUuidAndNeverPublishesAPartialSuccess() {
    var a = item(noise, "SQRT", "a");
    a.inputs.put("a", ambient("x"));
    var b = item(noise, "SQRT", "b");
    b.inputs.put("a", ambient("x"));
    var aa = item(noise, "ABS", "aa");
    aa.inputs.put("a", Binding.earlier(a.uuid));
    var bb = item(noise, "ABS", "bb");
    bb.inputs.put("a", Binding.earlier(b.uuid));
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    assertEquals(2, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(2, field.sample(4, 0, 0));
    for (int repeat = 0; repeat < 3; repeat++)
      assertEquals(
          b.uuid, assertThrows(EvaluationFailure.class, () -> field.sample(-1, 0, 0)).itemUuid);
    assertEquals(3, field.sample(9, 0, 0));
    assertEquals(
        b.uuid,
        assertThrows(EvaluationFailure.class, () -> compiler.explain(noise.uuid, -1, 0, 0))
            .itemUuid);
  }

  @Test
  void lazySelectionStillSkipsEveryUnselectedFailure() {
    for (String id : List.of("a", "b")) {
      var bad = item(noise, "SQRT", id);
      bad.inputs.put("a", ambient("x"));
    }
    var select = item(noise, "SELECT", "select");
    var condition = Binding.constant(0);
    condition.type = "Boolean";
    condition.value = ProjectIO.JSON.valueToTree(false);
    select.inputs.put("condition", condition);
    select.inputs.put("true", Binding.earlier(noise.processing.get(1).uuid));
    select.inputs.put("false", Binding.constant(7));
    var compiler = new NoiseCompiler(project, 0);
    assertEquals(1, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(7, compiler.compile(noise.uuid).sample(-1, 0, 0));
    assertEquals(0, compiler.statistics(noise.uuid).sampleWork());
  }

  @Test
  void vectorResultsShareOnlyAfterTheirCompleteTypedValueSucceeds() {
    var a = item(noise, "COMPOSE2", "a");
    a.inputs.put("x", ambient("x"));
    a.inputs.put("z", ambient("z"));
    var b = item(noise, "COMPOSE2", "b");
    b.inputs.put("x", ambient("x"));
    b.inputs.put("z", ambient("z"));
    var aa = item(noise, "LENGTH", "aa");
    aa.inputs.put("a", vector(a.uuid));
    var bb = item(noise, "LENGTH", "bb");
    bb.inputs.put("a", vector(b.uuid));
    binary("ADD", "sum", Binding.earlier(bb.uuid), Binding.earlier(aa.uuid));
    var compiler = new NoiseCompiler(project, 0);
    assertEquals(2, compiler.statistics(noise.uuid).sharedItems());
    assertEquals(10, compiler.compile(noise.uuid).sample(3, 0, 4));
  }

  @Test
  void failingLaterVectorComponentCannotPublishItsEarlierFiniteComponent() {
    var bad = binary("DIVIDE", "bad", Binding.constant(1), ambient("x"));
    var a = item(noise, "COMPOSE2", "a");
    a.inputs.put("x", ambient("x"));
    a.inputs.put("z", Binding.earlier(bad.uuid));
    var b = item(noise, "COMPOSE2", "b");
    b.inputs.put("x", ambient("x"));
    b.inputs.put("z", Binding.earlier(bad.uuid));
    var aa = item(noise, "LENGTH", "aa");
    aa.inputs.put("a", vector(a.uuid));
    var bb = item(noise, "LENGTH", "bb");
    bb.inputs.put("a", vector(b.uuid));
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    assertEquals(2, compiler.statistics(noise.uuid).sharedItems());
    for (int i = 0; i < 3; i++) {
      assertEquals(
          bad.uuid, assertThrows(EvaluationFailure.class, () -> field.sample(0, 0, 0)).itemUuid);
      assertEquals(StrictMath.sqrt(4.25), field.sample(2, 0, 0));
    }
  }

  @Test
  void prefixNeighborContextsDoNotLeakSharedValuesIntoTheCurrentPoint() {
    var sum = diamond().getLast();
    var shifted = item(noise, "OFFSET_SAMPLE", "shifted");
    shifted.inputs.put("field", Binding.earlierField(sum.uuid));
    shifted.parameters.put("x", ProjectIO.JSON.valueToTree(1));
    shifted.parameters.put("z", ProjectIO.JSON.valueToTree(0));
    binary("SUBTRACT", "difference", Binding.earlier(shifted.uuid), Binding.earlier(sum.uuid));
    var field = new NoiseCompiler(project, 0).compile(noise.uuid);
    for (int x = -16; x <= 16; x++) assertEquals(4 * x + 10, field.sample(x, 0, -3));
  }

  @Test
  void expressionsRemainExcludedAndConcurrentSamplesUseTheirOwnEpochs() throws Exception {
    diamond();
    var compiler = new NoiseCompiler(project, 0);
    var field = compiler.compile(noise.uuid);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Double>>();
      for (int x = -64; x < 64; x++) {
        final int value = x;
        jobs.add(() -> field.sample(value, 0, 3));
      }
      var results = workers.invokeAll(jobs);
      for (int i = 0; i < results.size(); i++)
        assertEquals(2.0 * (i - 62) * (i - 62), results.get(i).get());
    }
    noise.processing.clear();
    var a = expression(noise, "x+2");
    var b = expression(noise, "x+2");
    b.id = "second";
    binary("ADD", "sum", Binding.earlier(a.uuid), Binding.earlier(b.uuid));
    var separate = new NoiseCompiler(project, 0);
    assertEquals(0, separate.statistics(noise.uuid).sharedItems());
    assertEquals(10, separate.compile(noise.uuid).sample(3, 0, 0));
    assertEquals(98, field.sample(5, 0, 0));
  }
}
