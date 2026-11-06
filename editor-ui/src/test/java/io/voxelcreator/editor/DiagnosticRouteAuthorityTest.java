package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions: diagnostic pointers contribute hints, never replacement identities. */
class DiagnosticRouteAuthorityTest {
  private final UUID project = UUID.randomUUID(),
      resource = UUID.randomUUID(),
      control = UUID.randomUUID();

  private EditorEvents.Target target(UUID item, String parameter, String pointer) {
    return new EditorEvents.Target(project, resource, item, parameter, pointer, false);
  }

  private EditorEvents.Target diagnostic(String parameter, String pointer) {
    return EditorEvents.Target.diagnostic(
        project,
        new Diagnostic(
            "BAD", Diagnostic.Severity.ERROR, "original", pointer, resource, control, parameter));
  }

  private String route(UUID item, String parameter, String pointer) {
    return DiagnosticNavigation.controlParameter(target(item, parameter, pointer));
  }

  @Test
  void canonicalAndUnrelatedExplicitKeysSurviveAConflictingBindingPointer() {
    String pointer = "/noise2D/0/processing/0/inputs/other/type";
    for (String key :
        List.of(
            "inputs/a/b~c", "parameters/type", "algorithm/parameters/type", "expression", "radius")) {
      var t = diagnostic(key, pointer);
      assertEquals(key, t.parameter());
      assertEquals(pointer, t.pointer());
      assertEquals(resource, t.resource());
      assertEquals(control, t.control());
    }
  }

  @Test
  void legacyPortAndLeafKeysStillDecodeTheExactInputName() {
    String base = "/noise3D/99/coordinateTransforms/999/inputs/a~1b~0c";
    assertEquals("inputs/a/b~c", diagnostic(null, base).parameter());
    assertEquals("inputs/a/b~c", diagnostic("a/b~c", base + "/value/2").parameter());
    for (String leaf :
        List.of(
            "type",
            "sourceKind",
            "value",
            "parameterUuid",
            "itemUuid",
            "noiseGeneratorUuid",
            "ambientKey",
            "projection",
            "slice",
            "constant"))
      assertEquals("inputs/a/b~c", diagnostic(leaf, base + "/" + leaf).parameter());
    assertEquals("unrelated", diagnostic("unrelated", base + "/value").parameter());
  }

  @Test
  void nestedCollectionsAndPathsWithoutALeadingSlashCannotContributeInputHints() {
    var binding = Binding.constant(2);
    for (String pointer :
        List.of(
            "/runtime/processing/0/inputs/a/type",
            "/noise2D/0/parameters/processing/0/inputs/a/type",
            "/noise2D/0/processing/0/parameters/processing/0/inputs/a/type",
            "noise2D/0/processing/0/inputs/a/type",
            "/metadata/noise2D/0/processing/0/inputs/a/type")) {
      assertNull(diagnostic(null, pointer).parameter(), pointer);
      assertEquals("type", diagnostic("type", pointer).parameter(), pointer);
      assertEquals(
          "value",
          DiagnosticNavigation.bindingField(target(control, "inputs/a", pointer), "a", binding),
          pointer);
    }
  }

  @Test
  void matchingFullUuidPathsAndHistoricalIndicesRetainExactLeavesAndComponents() {
    var binding = Binding.constant(2);
    for (String root : List.of("noise2D", "noise3D", "noise"))
      for (String owner : List.of("0", "999", resource.toString().toUpperCase(Locale.ROOT))) {
        String base = "/" + root + "/" + owner;
        assertEquals(
            "algorithm/parameters/frequency",
            route(null, null, base + "/algorithm/parameters/frequency"));
        for (String item : List.of("0", "999", control.toString().toUpperCase(Locale.ROOT))) {
          for (String collection : List.of("processing", "coordinateTransforms", "control")) {
            String path = base + "/" + collection + "/" + item;
            assertEquals("inputs/a/b~c", diagnostic(null, path + "/inputs/a~1b~0c/type").parameter());
            assertEquals(
                "type",
                DiagnosticNavigation.bindingField(
                    target(control, "inputs/a/b~c", path + "/inputs/a~1b~0c/type"),
                    "a/b~c",
                    binding));
            assertEquals(
                "parameters/value/2", route(control, "value", path + "/parameters/value/2"));
          }
          assertEquals(
              "parameters/value/1",
              route(control, null, base + "/sharedParameters/" + item + "/value/1"));
        }
      }
  }

  @Test
  void foreignAndMalformedIdentityTokensCannotContributeKeysOrLeaves() {
    var binding = Binding.constant(2);
    for (String token :
        List.of(
            "+0", "00", "01", "-0", "-1", "2147483648", "1-1-1-1-1", "",
            UUID.randomUUID().toString())) {
      for (String pointer :
          List.of(
              "/noise2D/" + token + "/processing/0/inputs/a/type",
              "/noise2D/0/processing/" + token + "/inputs/a/type")) {
        assertNull(diagnostic(null, pointer).parameter(), pointer);
        assertEquals("type", diagnostic("type", pointer).parameter(), pointer);
        assertEquals(
            "value",
            DiagnosticNavigation.bindingField(target(control, "inputs/a", pointer), "a", binding),
            pointer);
      }
      for (String pointer :
          List.of(
              "/noise2D/" + token + "/processing/0/parameters/value/2",
              "/noise2D/0/control/" + token + "/parameters/value/2",
              "/noise3D/" + token + "/sharedParameters/0/value/2",
              "/noise3D/0/sharedParameters/" + token + "/value/2")) {
        assertNull(route(control, null, pointer), pointer);
        assertEquals("parameters/value", route(control, "value", pointer), pointer);
      }
      String source = "/noise/" + token + "/algorithm/parameters/frequency";
      assertNull(route(null, null, source), source);
      assertEquals("frequency", route(null, "frequency", source), source);
    }
  }

  @Test
  void missingExplicitOwnersCannotUsePointerPositionsAsReplacementIdentities() {
    String pointer = "/noise2D/0/processing/0/inputs/a/type";
    for (var d :
        List.of(
            new Diagnostic("BAD", Diagnostic.Severity.ERROR, "bad", pointer, null, control, null),
            new Diagnostic("BAD", Diagnostic.Severity.ERROR, "bad", pointer, resource, null, null))) {
      var t = EditorEvents.Target.diagnostic(project, d);
      assertNull(t.parameter());
      assertEquals(d.sourceUuid(), t.resource());
      assertEquals(d.itemUuid(), t.control());
    }
    assertNull(
        DiagnosticNavigation.controlParameter(
            new EditorEvents.Target(
                project, null, control, null, "/noise2D/0/processing/0/parameters/value/2", false)));
  }

  @Test
  void bindingFocusRequiresTheExactNamedRouteAndCompleteSupportedLeaf() {
    var binding = Binding.constant(2);
    binding.sourceKind = "Shared Parameter";
    String base = "/noise2D/0/processing/0/inputs/a/";
    for (String suffix :
        List.of(
            "type/nested", "value/2/nested", "value/3", "value/01", "value/-0", "value/",
            "unknown"))
      assertEquals(
          "parameterUuid",
          DiagnosticNavigation.bindingField(target(control, "inputs/a", base + suffix), "a", binding),
          suffix);
    assertEquals(
        "parameterUuid",
        DiagnosticNavigation.bindingField(
            target(control, "inputs/other", base + "type"), "a", binding));
    assertEquals(
        "value/2",
        DiagnosticNavigation.bindingField(target(control, "inputs/a", base + "value/2"), "a", binding));
    binding.sourceKind = "Constant";
    binding.type = "Vector3";
    assertEquals(
        "value/0",
        DiagnosticNavigation.bindingField(
            target(control, "inputs/a", base + "value/2/nested"), "a", binding));
  }

  @Test
  void canonicalControlKeysAndComponentsCannotBeExtendedByPointerHints() {
    String pointer = "/noise2D/0/processing/0/parameters/value/2";
    for (String key :
        List.of("parameters/value", "parameters/value/1", "parameters/a/b~c", "inputs/a"))
      assertEquals(key, route(control, key, pointer));
    assertEquals(
        "algorithm/parameters/frequency",
        route(null, "algorithm/parameters/frequency", "/noise2D/0/algorithm/parameters/other"));
    for (String suffix : List.of("/3", "/01", "/-0", "/2/nested", "/")) {
      assertNull(route(control, null, "/noise2D/0/processing/0/parameters/value" + suffix));
      assertNull(route(control, null, "/noise2D/0/sharedParameters/0/value" + suffix));
      assertNull(route(null, null, "/noise2D/0/algorithm/parameters/frequency" + suffix));
    }
  }

  @Test
  void focusUsesOnlyAnOwnedComponentAndRetainsDraftCancellation() {
    var focus = new PropertyFocus();
    var draft = new Object();
    String foreign = "/noise/" + UUID.randomUUID() + "/control/" + control + "/parameters/value/2";
    focus.request(target(control, "value", foreign), draft);
    assertTrue(focus.matches(control, "parameters/value/0"));
    assertFalse(focus.matches(control, "parameters/value/2"));
    String owned = "/noise/" + resource + "/control/" + control + "/parameters/value/2";
    focus.request(target(control, "value", owned), draft);
    focus.synchronize(project, resource, draft);
    assertFalse(focus.matches(control, "parameters/value/0"));
    assertTrue(focus.matches(control, "parameters/value/2"));
    assertTrue(focus.submit());
    focus.acknowledge("owned-value-2");
    assertFalse(focus.submit());
    focus.request(target(control, "parameters/value", owned), draft);
    assertTrue(focus.matches(control, "parameters/value/0"));
    assertFalse(focus.matches(control, "parameters/value/2"));
    focus.synchronize(project, resource, new Object());
    assertFalse(focus.submit());
  }

  @Test
  void navigationAfterReorderingPreservesRejectedPrivateInputAndDocumentHistory() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "owned";
                var first = ProcessingRegistry.create("ADD", "first");
                var second = ProcessingRegistry.create("ADD", "second");
                n.processing.addAll(List.of(first, second));
                n.outputBinding = Binding.earlier(first.uuid);
                var other = new Noise();
                other.id = "other";
                p.noise2D.addAll(List.of(n, other));
              }));
      var original = state.project().noise2D.getFirst();
      String pointer = "/noise2D/0/processing/0/inputs/b/type";
      var t =
          EditorEvents.Target.diagnostic(
              state.project().project.uuid,
              new Diagnostic(
                  "BAD",
                  Diagnostic.Severity.ERROR,
                  "bad",
                  pointer,
                  original.uuid,
                  original.processing.getFirst().uuid,
                  "inputs/a"));
      assertTrue(
          state.change(
              p -> {
                Collections.reverse(p.noise2D);
                Collections.reverse(p.noise2D.getLast().processing);
              }));
      var n = state.project().noise2D.getLast();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var item = draft.processing.getLast();
      item.inputs.get("a").value = ProjectIO.JSON.valueToTree("broken");
      assertFalse(state.applyDraft());
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      boolean dirty = state.document.dirty();
      assertEquals("inputs/a", t.parameter());
      assertEquals(n.uuid, t.resource());
      assertEquals(item.uuid, t.control());
      assertTrue(state.navigate(t));
      assertSame(draft, state.draft);
      assertEquals("broken", item.inputs.get("a").value.asText());
      assertEquals("value", DiagnosticNavigation.bindingField(t, "a", item.inputs.get("a")));
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
    }
  }
}
