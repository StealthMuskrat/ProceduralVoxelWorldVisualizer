package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred iterative structural dependency validation, including disabled experimental items. */
class NoiseDependencyCycleTest {
  private static Noise noise(ProjectData project, String id) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .25);
    project.noise2D.add(noise);
    return noise;
  }

  private static Item reference(Noise source, UUID target, String input, boolean enabled) {
    var item = new Item();
    item.id = "reference-" + source.processing.size();
    item.kind = "EXPRESSION";
    item.enabled = enabled;
    if (enabled) item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
    var binding = new Binding();
    binding.sourceKind = "Noise Generator Reference";
    binding.noiseGeneratorUuid = target;
    item.inputs.put(input, binding);
    source.processing.add(item);
    return item;
  }

  private static ProjectData chain(int size) {
    var project = new ProjectData();
    for (int i = 0; i < size; i++) noise(project, "field-" + i);
    for (int i = 0; i < size - 1; i++)
      reference(project.noise2D.get(i), project.noise2D.get(i + 1).uuid, "uuid", false);
    return project;
  }

  private static List<Diagnostic> cycles(ProjectData project) {
    return Validator.validate(project).stream()
        .filter(d -> d.code().equals("NOISE_CYCLE"))
        .toList();
  }

  @Test
  void aLongDisabledReferenceChainIsStructurallyValidWithoutRecursiveStack() {
    var project = chain(8192);
    var before = ProjectIO.JSON.valueToTree(project);
    assertTrue(Validator.validate(project).isEmpty());
    assertEquals(before, ProjectIO.JSON.valueToTree(project));
  }

  @Test
  void deepCycleReportsOneCompleteRouteAtItsActualClosingInput() {
    var project = chain(2048);
    var closing =
        reference(project.noise2D.getLast(), project.noise2D.getFirst().uuid, "uuid", false);
    var issues = cycles(project);
    assertEquals(1, issues.size());
    var issue = issues.getFirst();
    assertEquals("/noise2D/2047/processing/0/inputs/uuid/noiseGeneratorUuid", issue.jsonPointer());
    assertEquals(project.noise2D.getLast().uuid, issue.sourceUuid());
    assertEquals(closing.uuid, issue.itemUuid());
    assertEquals("uuid", issue.parameterKey());
    var route = new ArrayList<String>();
    project.noise2D.forEach(n -> route.add(n.uuid.toString()));
    route.add(project.noise2D.getFirst().uuid.toString());
    assertEquals("Noise reference cycle " + String.join(" -> ", route), issue.message());
  }

  @Test
  void disconnectedCyclesAreEachReachedOnceInDocumentOrder() {
    var project = chain(2);
    reference(project.noise2D.getLast(), project.noise2D.getFirst().uuid, "a", false);
    var other = noise(project, "other");
    reference(other, other.uuid, "self", false);
    var issues = cycles(project);
    assertEquals(2, issues.size());
    assertEquals(project.noise2D.get(1).uuid, issues.getFirst().sourceUuid());
    assertEquals(other.uuid, issues.getLast().sourceUuid());
  }

  @Test
  void sharedDiamondDependenciesAreNotMistakenForActiveCycles() {
    var project = new ProjectData();
    var root = noise(project, "root");
    var left = noise(project, "left");
    var right = noise(project, "right");
    var leaf = noise(project, "leaf");
    reference(root, left.uuid, "left", false);
    reference(root, right.uuid, "right", false);
    reference(left, leaf.uuid, "leaf", false);
    reference(right, leaf.uuid, "leaf", false);
    assertTrue(Validator.validate(project).isEmpty());
  }

  @Test
  void routeStartsAtTheCycleInsteadOfAnUnrelatedTraversalPrefix() {
    var project = chain(3);
    reference(project.noise2D.getLast(), project.noise2D.get(1).uuid, "back", false);
    var issue = cycles(project).getFirst();
    assertFalse(issue.message().contains(project.noise2D.getFirst().uuid.toString()));
    assertEquals(
        "Noise reference cycle "
            + project.noise2D.get(1).uuid
            + " -> "
            + project.noise2D.getLast().uuid
            + " -> "
            + project.noise2D.get(1).uuid,
        issue.message());
  }

  @Test
  void disabledAndEnabledItemsBothRetainStructuralAcyclicity() {
    for (boolean enabled : List.of(false, true)) {
      var project = new ProjectData();
      var n = noise(project, "self");
      reference(n, n.uuid, "self", enabled);
      assertEquals(1, cycles(project).size());
    }
  }

  @Test
  void parameterReferencesCarryExactItemAndParameterAuthority() {
    var project = new ProjectData();
    var n = noise(project, "self");
    var item = reference(n, n.uuid, "unused", false);
    item.inputs.clear();
    item.parameters.put("noiseGeneratorUuid", ProjectIO.JSON.valueToTree(n.uuid));
    var issue = cycles(project).getFirst();
    assertEquals("/noise2D/0/processing/0/parameters/noiseGeneratorUuid", issue.jsonPointer());
    assertEquals(item.uuid, issue.itemUuid());
    assertEquals("noiseGeneratorUuid", issue.parameterKey());
    assertEquals(
        n.uuid.toString(), ProjectIO.JSON.valueToTree(project).at(issue.jsonPointer()).asText());
  }

  @Test
  void coordinateStageAndCrossDimensionEdgesParticipateInTheSameGraph() {
    var project = chain(2);
    var second = project.noise2D.removeLast();
    second.dimension = 3;
    project.noise3D.add(second);
    var item = reference(second, project.noise2D.getFirst().uuid, "sample", false);
    second.processing.clear();
    item.kind = "TRANSLATE";
    second.coordinateTransforms.add(item);
    var issue = cycles(project).getFirst();
    assertEquals(
        "/noise3D/0/coordinateTransforms/0/inputs/sample/noiseGeneratorUuid", issue.jsonPointer());
    assertEquals(second.uuid, issue.sourceUuid());
    assertEquals(item.uuid, issue.itemUuid());
  }

  @Test
  void escapedBindingNamesRemainExactAndStableAfterCandidateReorder() {
    var project = new ProjectData();
    var n = noise(project, "field");
    var item = reference(n, n.uuid, "uuid/a~b", false);
    var issue = cycles(project).getFirst();
    assertEquals(
        "/noise2D/0/processing/0/inputs/uuid~1a~0b/noiseGeneratorUuid", issue.jsonPointer());
    assertEquals("uuid/a~b", issue.parameterKey());
    assertEquals(
        n.uuid.toString(), ProjectIO.JSON.valueToTree(project).at(issue.jsonPointer()).asText());
    n.processing.addFirst(new Item());
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals(item.uuid, issue.itemUuid());
  }

  @Test
  void inputMapInsertionOrderDoesNotChangeCycleDiagnosticOrdering() {
    var project = new ProjectData();
    var n = noise(project, "field");
    var item = reference(n, n.uuid, "z", false);
    item.inputs.put("a", item.inputs.get("z"));
    var original = cycles(project);
    var binding = item.inputs.remove("z");
    item.inputs.put("z", binding);
    assertEquals(original, cycles(project));
    assertEquals(List.of("a", "z"), original.stream().map(Diagnostic::parameterKey).toList());
  }

  @Test
  void unresolvedOrMalformedReferencesRetainTheirOwnErrorsWithoutInventingCycles() {
    var project = new ProjectData();
    var n = noise(project, "field");
    var item = reference(n, UUID.randomUUID(), "missing", false);
    item.parameters.put("noiseGeneratorUuid", ProjectIO.JSON.valueToTree("not-a-uuid"));
    var issues = Validator.validate(project);
    assertTrue(issues.stream().anyMatch(d -> d.code().equals("INVALID_REFERENCE")));
    assertTrue(issues.stream().noneMatch(d -> d.code().equals("NOISE_CYCLE")));
  }

  @Test
  void aNullBindingKeepsItsPreciseMissingInputDiagnostic() {
    var project = new ProjectData();
    var n = noise(project, "field");
    var item = reference(n, n.uuid, "missing", false);
    item.inputs.put("missing", null);
    var issues = Validator.validate(project);
    assertTrue(
        issues.stream()
            .anyMatch(
                d ->
                    d.code().equals("MISSING_INPUT")
                        && d.jsonPointer().equals("/noise2D/0/processing/0/inputs/missing")));
    assertFalse(issues.stream().anyMatch(d -> d.code().equals("INVALID_STRUCTURE")));
  }
}
