package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiagnosticLocationTest {
  @Test
  void rejectedCandidatePathsIdentifyNestedOperationRatherThanItsLayer() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "layer";
    var operation = new Operation();
    operation.id = "operation";
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    var d =
        Diagnostic.error(
            "BAD",
            "/worldGenerator/layers/0/operations/0/overrides/probability",
            "bad probability");
    var enriched = DiagnosticLocations.enrich(p, List.of(d)).getFirst();
    assertEquals(operation.uuid, enriched.sourceUuid());
    assertNull(enriched.itemUuid());
    assertEquals("probability", enriched.parameterKey());
    assertEquals(d.jsonPointer(), enriched.jsonPointer());
  }

  @Test
  void processingInputsAndEscapedParameterKeysResolveBeforeReorder() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "noise";
    p.noise2D.add(n);
    var a = new Item();
    a.id = "a";
    var b = new Item();
    b.id = "b";
    n.processing.addAll(List.of(a, b));
    var issue =
        DiagnosticLocations.enrich(
                p,
                List.of(
                    Diagnostic.error(
                        "BAD", "/noise2D/0/processing/1/inputs/a~1b~0c/constant", "bad")))
            .getFirst();
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals(b.uuid, issue.itemUuid());
    assertEquals("a/b~c", issue.parameterKey());
    Collections.reverse(n.processing);
    assertEquals(b.uuid, issue.itemUuid());
  }

  @Test
  void uuidArrayPathsAndHistoricalNoiseMembershipAliasesResolve() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "noise";
    p.noise2D.add(n);
    var parameter = new Parameter();
    parameter.id = "parameter";
    n.sharedParameters.add(parameter);
    var set = new Membership();
    set.id = "set";
    p.voxelSets.add(set);
    var issues =
        DiagnosticLocations.enrich(
            p,
            List.of(
                Diagnostic.error(
                    "BAD", "/noise2D/0/sharedParameters/" + parameter.uuid + "/value", "bad"),
                Diagnostic.error("CYCLE", "/noise/" + n.uuid, "cycle"),
                Diagnostic.error("BAD", "/membership/" + set.uuid, "bad member")));
    assertEquals(parameter.uuid, issues.get(0).itemUuid());
    assertEquals(n.uuid, issues.get(0).sourceUuid());
    assertEquals(n.uuid, issues.get(1).sourceUuid());
    assertEquals(set.uuid, issues.get(2).sourceUuid());
  }

  @Test
  void explicitCompilerContextIsNeverOverwrittenByPointerFallback() {
    var p = new ProjectData();
    UUID source = UUID.randomUUID(), item = UUID.randomUUID();
    var original =
        new Diagnostic(
            "BAD",
            Diagnostic.Severity.ERROR,
            "bad",
            "/noise2D/99/processing/2",
            source,
            item,
            "expression");
    assertEquals(original, DiagnosticLocations.enrich(p, List.of(original)).getFirst());
    assertEquals(
        "/missing/999",
        DiagnosticLocations.enrich(p, List.of(Diagnostic.error("BAD", "/missing/999", "bad")))
            .getFirst()
            .jsonPointer());
  }

  @Test
  void normalValidatorRetainsUuidContextWithoutMutatingCandidate() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "valid";
    p.noise2D.add(n);
    var item = new Item();
    item.id = "item";
    item.resultType = "Unsupported";
    n.processing.add(item);
    String before = ProjectIO.JSON.valueToTree(p).toString();
    var issue =
        Validator.validate(p).stream()
            .filter(d -> d.code().equals("INVALID_TYPE"))
            .findFirst()
            .orElseThrow();
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals(item.uuid, issue.itemUuid());
    assertEquals("resultType", issue.parameterKey());
    assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
  }

  @Test
  void curvePointLocationKeepsTheOwningNoiseAndPointIdentity() {
    var p = new ProjectData();
    var n = new Noise();
    p.noise2D.add(n);
    var item = new Item();
    n.processing.add(item);
    var curve = new Curve();
    var point = new Point();
    curve.points.clear();
    curve.points.add(point);
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    var issue =
        DiagnosticLocations.enrich(
                p,
                List.of(
                    Diagnostic.error(
                        "BAD", "/noise2D/0/processing/0/parameters/curve/points/0/x", "bad point")))
            .getFirst();
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals(point.uuid, issue.itemUuid());
    assertEquals("curve", issue.parameterKey());
  }
}
