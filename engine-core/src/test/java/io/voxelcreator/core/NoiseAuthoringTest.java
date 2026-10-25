package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseAuthoringTest {
  static ProjectData project() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 1.);
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2.);
    n.sharedParameters.add(parameter);
    var add = ProcessingRegistry.create("ADD", "add");
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    add.inputs.put("b", binding);
    var multiply = ProcessingRegistry.create("MULTIPLY", "multiply");
    multiply.inputs.put("a", Binding.earlier(add.uuid));
    multiply.inputs.put("b", Binding.constant(4));
    n.processing.addAll(List.of(add, multiply));
    n.outputBinding = Binding.earlier(multiply.uuid);
    var pin = new Pin();
    pin.targetUuid = parameter.uuid;
    pin.label = "Gain";
    n.pinnedControls.add(pin);
    p.noise2D.add(n);
    return p;
  }

  static double sample(ProjectData p, Noise n) {
    var snapshot = ProjectIO.copy(p);
    snapshot.noise2D.set(0, NoiseAuthoring.copy(n));
    return new NoiseCompiler(snapshot, 0).compile(n.uuid).sample(-17, 0, 23);
  }

  static NoiseAuthoring.Plan plan(
      ProjectData p, Noise n, Action action, UUID id, Resolution resolution, UUID replacement) {
    return NoiseAuthoring.plan(p, n, new Request(action, id, resolution, replacement));
  }

  @Test
  void fixtureHasIndependentTwelveExpectation() {
    var p = project();
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    assertEquals(12, sample(p, p.noise2D.getFirst()));
  }

  @Test
  void invalidReorderDoesNotMutateDraftOrProject() {
    var p = project();
    var n = NoiseAuthoring.copy(p.noise2D.getFirst());
    var before = ProjectIO.JSON.valueToTree(n);
    var committed = ProjectIO.JSON.valueToTree(p);
    var plan = plan(p, n, Action.DOWN, n.processing.getFirst().uuid, Resolution.REJECT, null);
    assertFalse(plan.valid());
    assertThrows(IllegalStateException.class, () -> plan.applyTo(n));
    assertEquals(before, ProjectIO.JSON.valueToTree(n));
    assertEquals(committed, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void independentReorderPreservesIdentityAndExactValues() {
    var p = project();
    var n = p.noise2D.getFirst();
    var constant = ProcessingRegistry.create("CONSTANT", "unused");
    n.processing.add(constant);
    var plan = plan(p, n, Action.UP, constant.uuid, Resolution.REJECT, null);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    assertEquals(constant.uuid, n.processing.get(1).uuid);
    assertEquals(12, sample(p, n));
  }

  @Test
  void duplicateHasFreshUuidSafeGlobalNameAndPreservesExternalInputs() {
    var p = project();
    var n = p.noise2D.getFirst();
    var original = n.processing.getFirst();
    var transform = new Item();
    transform.id = "add-copy";
    transform.kind = "TRANSLATE";
    transform.resultType = "Coordinates2D";
    n.coordinateTransforms.add(transform);
    var plan = plan(p, n, Action.DUPLICATE, original.uuid, Resolution.REJECT, null);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    var duplicate = n.processing.get(1);
    assertNotEquals(original.uuid, duplicate.uuid);
    assertEquals("add-copy-2", duplicate.id);
    assertEquals(original.inputs.get("b").parameterUuid, duplicate.inputs.get("b").parameterUuid);
    assertEquals(12, sample(p, n));
  }

  @Test
  void duplicateRemapsCurvePointIdentitiesButRetainsOutsideReferences() {
    var p = project();
    var n = p.noise2D.getFirst();
    var curve = ProcessingRegistry.create("CURVE", "curve");
    n.processing.add(curve);
    var plan = plan(p, n, Action.DUPLICATE, curve.uuid, Resolution.REJECT, null);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    var duplicate = n.processing.getLast();
    assertNotEquals(
        curve.parameters.get("curve").path("uuid"), duplicate.parameters.get("curve").path("uuid"));
    assertNotEquals(
        curve.parameters.get("curve").path("points").get(0).path("uuid"),
        duplicate.parameters.get("curve").path("points").get(0).path("uuid"));
    assertEquals("Source Result", duplicate.inputs.get("a").sourceKind);
  }

  @Test
  void selectedOutputDeletionRequiresExplicitResolution() {
    var p = project();
    var n = p.noise2D.getFirst();
    UUID output = n.processing.getLast().uuid;
    assertFalse(plan(p, n, Action.DELETE, output, Resolution.REJECT, null).valid());
    var resolved = plan(p, n, Action.DELETE, output, Resolution.SOURCE, null);
    assertTrue(resolved.valid());
    resolved.applyTo(n);
    assertEquals("Source Result", n.outputBinding.sourceKind);
    assertEquals(1, sample(p, n));
  }

  @Test
  void deletingAnInputRedirectsAllUsesToRawSourceWithoutBakingValues() {
    var p = project();
    var n = p.noise2D.getFirst();
    UUID input = n.processing.getFirst().uuid;
    var resolved = plan(p, n, Action.DELETE, input, Resolution.SOURCE, null);
    assertTrue(resolved.valid(), resolved.diagnostics().toString());
    resolved.applyTo(n);
    assertEquals(1, n.processing.size());
    assertEquals("Source Result", n.processing.getFirst().inputs.get("a").sourceKind);
    assertNull(n.processing.getFirst().inputs.get("a").itemUuid);
    assertEquals(4, sample(p, n));
    n.algorithm.parameters.put("value", 3.);
    assertEquals(12, sample(p, n));
  }

  @Test
  void replacementMustRemainEarlierEnabledAndSameType() {
    var p = project();
    var n = p.noise2D.getFirst();
    var later = ProcessingRegistry.create("CONSTANT", "later");
    n.processing.add(later);
    UUID add = n.processing.getFirst().uuid;
    assertFalse(plan(p, n, Action.DELETE, add, Resolution.REPLACE, later.uuid).valid());
    later.enabled = false;
    assertFalse(plan(p, n, Action.DELETE, add, Resolution.REPLACE, later.uuid).valid());
    later.enabled = true;
    later.resultType = "Boolean";
    later.parameters.put("value", ProjectIO.JSON.valueToTree(false));
    assertFalse(plan(p, n, Action.DELETE, add, Resolution.REPLACE, later.uuid).valid());
  }

  @Test
  void explicitEarlierReplacementPreservesReferenceAndHasKnownArithmetic() {
    var p = project();
    var n = p.noise2D.getFirst();
    var earlier = ProcessingRegistry.create("CONSTANT", "seven");
    earlier.parameters.put("value", ProjectIO.JSON.valueToTree(7));
    n.processing.addFirst(earlier);
    var plan =
        plan(p, n, Action.DELETE, n.processing.get(1).uuid, Resolution.REPLACE, earlier.uuid);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    assertEquals(earlier.uuid, n.processing.getLast().inputs.get("a").itemUuid);
    assertEquals(28, sample(p, n));
  }

  @Test
  void disableRedirectsDependenciesAndKeepsDefinition() {
    var p = project();
    var n = p.noise2D.getFirst();
    UUID add = n.processing.getFirst().uuid;
    assertFalse(plan(p, n, Action.DISABLE, add, Resolution.REJECT, null).valid());
    var plan = plan(p, n, Action.DISABLE, add, Resolution.SOURCE, null);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertFalse(n.processing.getFirst().enabled);
    assertEquals(2, n.processing.size());
    assertEquals(4, sample(p, n));
    var enable = plan(p, n, Action.ENABLE, add, Resolution.REJECT, null);
    assertTrue(enable.valid());
    enable.applyTo(n);
    assertTrue(n.processing.getFirst().enabled);
    assertEquals(4, sample(p, n));
  }

  @Test
  void invalidDisabledExpressionStaysSerializableButCannotEnable() {
    var p = project();
    var n = p.noise2D.getFirst();
    var expr = ProcessingRegistry.create("EXPRESSION", "draft");
    expr.enabled = false;
    expr.parameters.put("expression", ProjectIO.JSON.valueToTree("broken +"));
    n.processing.add(expr);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    assertFalse(plan(p, n, Action.ENABLE, expr.uuid, Resolution.REJECT, null).valid());
    assertFalse(n.processing.getLast().enabled);
  }

  @Test
  void deletingAnInvalidSelfReferencingItemCanRepairTheDraft() {
    var p = project();
    var n = p.noise2D.getFirst();
    var bad = ProcessingRegistry.create("ADD", "bad");
    bad.inputs.put("a", Binding.earlier(bad.uuid));
    n.processing.add(bad);
    var plan = plan(p, n, Action.DELETE, bad.uuid, Resolution.REJECT, null);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    assertEquals(2, n.processing.size());
    assertEquals(12, sample(p, n));
  }

  @Test
  void coordinateStageCannotResolveToThePostSourceField() {
    var p = project();
    var n = p.noise2D.getFirst();
    var a = new Item();
    a.id = "first";
    a.kind = "TRANSLATE";
    a.resultType = "Coordinates2D";
    var b = new Item();
    b.id = "second";
    b.kind = "TRANSLATE";
    b.resultType = "Coordinates2D";
    var ref = Binding.earlier(a.uuid);
    ref.type = "Coordinates2D";
    b.inputs.put("coordinates", ref);
    n.coordinateTransforms.addAll(List.of(a, b));
    assertFalse(plan(p, n, Action.DELETE, a.uuid, Resolution.SOURCE, null).valid());
    assertFalse(plan(p, n, Action.DOWN, a.uuid, Resolution.REJECT, null).valid());
  }

  @Test
  void parameterDeleteRejectsUntilInlineIsExplicitAndRemovesOnlyItsPins() {
    var p = project();
    var n = p.noise2D.getFirst();
    var parameter = n.sharedParameters.getFirst();
    var itemPin = new Pin();
    itemPin.targetUuid = n.processing.getFirst().uuid;
    itemPin.label = "item";
    n.pinnedControls.add(itemPin);
    assertFalse(
        plan(p, n, Action.DELETE_PARAMETER, parameter.uuid, Resolution.REJECT, null).valid());
    var plan =
        plan(p, n, Action.DELETE_PARAMETER, parameter.uuid, Resolution.INLINE_PARAMETER, null);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertTrue(n.sharedParameters.isEmpty());
    assertEquals(1, n.pinnedControls.size());
    assertEquals("Constant", n.processing.getFirst().inputs.get("b").sourceKind);
    assertNull(n.processing.getFirst().inputs.get("b").parameterUuid);
    assertEquals(12, sample(p, n));
  }

  @Test
  void parameterReplacementPreservesLiveSharingAndRetargetsPins() {
    var p = project();
    var n = p.noise2D.getFirst();
    var first = n.sharedParameters.getFirst();
    var replacement = new Parameter();
    replacement.id = "replacement";
    replacement.value = ProjectIO.JSON.valueToTree(6);
    n.sharedParameters.add(replacement);
    var plan =
        plan(p, n, Action.DELETE_PARAMETER, first.uuid, Resolution.REPLACE, replacement.uuid);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertEquals(replacement.uuid, n.processing.getFirst().inputs.get("b").parameterUuid);
    assertEquals(replacement.uuid, n.pinnedControls.getFirst().targetUuid);
    assertEquals(28, sample(p, n));
    n.sharedParameters.getFirst().value = ProjectIO.JSON.valueToTree(9);
    assertEquals(40, sample(p, n));
  }

  @Test
  void parameterReplacementRejectsSelfAndTypeMismatch() {
    var p = project();
    var n = p.noise2D.getFirst();
    var param = n.sharedParameters.getFirst();
    assertFalse(
        plan(p, n, Action.DELETE_PARAMETER, param.uuid, Resolution.REPLACE, param.uuid).valid());
    var other = new Parameter();
    other.id = "boolean";
    other.type = "Boolean";
    other.value = ProjectIO.JSON.valueToTree(false);
    n.sharedParameters.add(other);
    assertFalse(
        plan(p, n, Action.DELETE_PARAMETER, param.uuid, Resolution.REPLACE, other.uuid).valid());
  }

  @Test
  void parameterDuplicateDoesNotRedirectItsUsersOrPins() {
    var p = project();
    var n = p.noise2D.getFirst();
    var original = n.sharedParameters.getFirst();
    var plan = plan(p, n, Action.DUPLICATE_PARAMETER, original.uuid, Resolution.REJECT, null);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertEquals(2, n.sharedParameters.size());
    assertNotEquals(original.uuid, n.sharedParameters.getLast().uuid);
    assertEquals("gain-copy", n.sharedParameters.getLast().id);
    assertEquals(original.uuid, n.processing.getFirst().inputs.get("b").parameterUuid);
    assertEquals(original.uuid, n.pinnedControls.getFirst().targetUuid);
  }

  @Test
  void plansOwnTheirCandidateAndRejectStalePublication() {
    var p = project();
    var n = p.noise2D.getFirst();
    var plan = plan(p, n, Action.DUPLICATE, n.processing.getFirst().uuid, Resolution.REJECT, null);
    assertTrue(plan.valid());
    plan.candidate().processing.clear();
    assertEquals(3, plan.candidate().processing.size());
    n.id = "changed";
    var before = ProjectIO.JSON.valueToTree(n);
    assertThrows(IllegalStateException.class, () -> plan.applyTo(n));
    assertEquals(before, ProjectIO.JSON.valueToTree(n));
  }

  @Test
  void privatePlanAndParentCommitUseOneUndoActionAndLeaveSettingsIntact() {
    var p = project();
    var document = new DocumentSession();
    document.setValidator(NoiseCompiler::validate);
    document.edit(q -> ResourceGraph.assign(q, ProjectIO.copy(p)));
    var n = NoiseAuthoring.copy(document.data().noise2D.getFirst());
    var plan =
        plan(
            document.data(),
            n,
            Action.DELETE,
            n.processing.getFirst().uuid,
            Resolution.SOURCE,
            null);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertEquals(12, sample(document.data(), document.data().noise2D.getFirst()));
    assertTrue(document.edit(q -> q.noise2D.set(0, NoiseAuthoring.copy(n))).isEmpty());
    assertEquals(4, sample(document.data(), document.data().noise2D.getFirst()));
    document.undo();
    assertEquals(12, sample(document.data(), document.data().noise2D.getFirst()));
    document.redo();
    assertEquals(4, sample(document.data(), document.data().noise2D.getFirst()));
  }

  @Test
  void explicitTypeChangesProduceSixCorrectConstantShapesAndClearReferences() {
    var b = Binding.earlier(UUID.randomUUID());
    b.parameterUuid = UUID.randomUUID();
    b.noiseGeneratorUuid = UUID.randomUUID();
    b.ambientKey = "x";
    for (String type : NoiseAuthoring.TYPES) {
      NoiseAuthoring.type(b, type);
      assertEquals(type, b.type);
      assertEquals("Constant", b.sourceKind);
      assertNull(b.itemUuid);
      assertNull(b.parameterUuid);
      assertNull(b.noiseGeneratorUuid);
      assertNull(b.ambientKey);
      if (type.equals("Scalar")) assertTrue(b.value.isNumber());
      else if (type.equals("Boolean")) assertTrue(b.value.isBoolean());
      else assertEquals(type.endsWith("2") || type.endsWith("2D") ? 2 : 3, b.value.size());
    }
  }

  @Test
  void sourceChangesDoNotRetainInactiveReferenceDependencies() {
    var b = Binding.earlier(UUID.randomUUID());
    b.noiseGeneratorUuid = UUID.randomUUID();
    b.parameterUuid = UUID.randomUUID();
    NoiseAuthoring.source(b, "Source Result");
    assertNull(b.itemUuid);
    assertNull(b.noiseGeneratorUuid);
    assertNull(b.parameterUuid);
    assertNull(b.value);
    NoiseAuthoring.source(b, "Constant");
    assertEquals(0, b.value.doubleValue());
  }

  @Test
  void invalidTypeAndSourceEditsRejectBeforeMutation() {
    var b = Binding.constant(3);
    var before = ProjectIO.JSON.valueToTree(b);
    assertThrows(IllegalArgumentException.class, () -> NoiseAuthoring.type(b, "Invalid"));
    assertEquals(before, ProjectIO.JSON.valueToTree(b));
    assertThrows(IllegalArgumentException.class, () -> NoiseAuthoring.source(b, "Invalid"));
    assertEquals(before, ProjectIO.JSON.valueToTree(b));
  }

  @Test
  void deletingPinnedItemExplicitlyRetargetsPresentationWhenReplaced() {
    var p = project();
    var n = p.noise2D.getFirst();
    var earlier = ProcessingRegistry.create("CONSTANT", "seven");
    n.processing.addFirst(earlier);
    var target = n.processing.get(1);
    var pin = new Pin();
    pin.targetUuid = target.uuid;
    pin.label = "presentation";
    pin.group = "group";
    n.pinnedControls.add(pin);
    var plan = plan(p, n, Action.DELETE, target.uuid, Resolution.REPLACE, earlier.uuid);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertEquals(earlier.uuid, n.pinnedControls.getLast().targetUuid);
    assertEquals("presentation", n.pinnedControls.getLast().label);
    assertEquals("group", n.pinnedControls.getLast().group);
  }

  @Test
  void unknownTargetAndMissingResourceRejectWithoutMutation() {
    var p = project();
    var n = p.noise2D.getFirst();
    assertFalse(plan(p, n, Action.DELETE, UUID.randomUUID(), Resolution.SOURCE, null).valid());
    var orphan = NoiseAuthoring.copy(n);
    orphan.uuid = UUID.randomUUID();
    assertFalse(
        plan(p, orphan, Action.UP, orphan.processing.getFirst().uuid, Resolution.REJECT, null)
            .valid());
  }

  @Test
  void sampledPrefixesBecomeSourceFieldsWhileNumericUsesBecomeSourceResults() {
    var p = project();
    var n = p.noise2D.getFirst();
    var add = n.processing.getFirst();
    var sample = ProcessingRegistry.create("OFFSET_SAMPLE", "sample");
    sample.inputs.put("field", Binding.earlierField(add.uuid));
    n.processing.add(sample);
    n.outputBinding = Binding.earlier(sample.uuid);
    assertEquals(3, sample(p, n));
    var plan = plan(p, n, Action.DELETE, add.uuid, Resolution.SOURCE, null);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    plan.applyTo(n);
    assertEquals("Source Result", n.processing.getFirst().inputs.get("a").sourceKind);
    assertEquals("Source Field", n.processing.getLast().inputs.get("field").sourceKind);
    assertNull(n.processing.getLast().inputs.get("field").itemUuid);
    assertEquals(1, sample(p, n));
  }

  @Test
  void parameterInlineUpdatesEveryAuthoredUseAsOneCandidate() {
    var p = project();
    var n = p.noise2D.getFirst();
    var parameter = n.sharedParameters.getFirst();
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    n.processing.getLast().inputs.put("b", binding);
    assertEquals(6, sample(p, n));
    var plan =
        plan(p, n, Action.DELETE_PARAMETER, parameter.uuid, Resolution.INLINE_PARAMETER, null);
    assertTrue(plan.valid());
    plan.applyTo(n);
    assertEquals("Constant", n.processing.getFirst().inputs.get("b").sourceKind);
    assertEquals("Constant", n.processing.getLast().inputs.get("b").sourceKind);
    assertEquals(6, sample(p, n));
  }
}
