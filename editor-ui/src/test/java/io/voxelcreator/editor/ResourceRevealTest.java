package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred accepted-navigation reveal ownership and exact iterative expansion routes. */
class ResourceRevealTest {
  private static Folder folder(ProjectData p, String id, UUID parent, String root) {
    var f = new Folder();
    f.id = id;
    f.parentFolderUuid = parent;
    f.rootKind = root;
    p.folders.add(f);
    return f;
  }

  private static ResourceTreeView view(ProjectData p) {
    var result = new ResourceTreeView();
    result.bind(new ResourceTreeIndex(p));
    return result;
  }

  @Test
  void onlyTheExactLatestTicketCanBeConsumedOnce() {
    var reveal = new ResourceReveal();
    var lifetime = new Object();
    var id = UUID.randomUUID();
    reveal.request(lifetime, id);
    var first = reveal.current(lifetime, id);
    reveal.request(lifetime, id);
    var second = reveal.current(lifetime, id);
    assertNotSame(first, second);
    assertFalse(reveal.consume(first, lifetime, id));
    assertSame(second, reveal.current(lifetime, id));
    assertTrue(reveal.consume(second, lifetime, id));
    assertFalse(reveal.consume(second, lifetime, id));
    assertNull(reveal.current(lifetime, id));
  }

  @Test
  void replacementLifetimeRetiresEvenAnIdenticalResourceUuid() {
    var reveal = new ResourceReveal();
    var old = new Object();
    var id = UUID.randomUUID();
    reveal.request(old, id);
    var ticket = reveal.current(old, id);
    assertNull(reveal.current(new Object(), id));
    assertFalse(reveal.consume(ticket, old, id));
  }

  @Test
  void ordinarySelectionChangeCancelsAnUnrenderedReveal() {
    var reveal = new ResourceReveal();
    var lifetime = new Object();
    var id = UUID.randomUUID();
    reveal.request(lifetime, id);
    assertNull(reveal.current(lifetime, UUID.randomUUID()));
    assertNull(reveal.current(lifetime, id));
  }

  @Test
  void supersededDifferentTargetCannotBeClearedByLateCompletion() {
    var reveal = new ResourceReveal();
    var lifetime = new Object();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    reveal.request(lifetime, a);
    var old = reveal.current(lifetime, a);
    reveal.request(lifetime, b);
    var current = reveal.current(lifetime, b);
    assertFalse(reveal.consume(old, new Object(), a));
    assertFalse(reveal.consume(old, lifetime, b));
    assertSame(current, reveal.current(lifetime, b));
  }

  @Test
  void missingTicketsAndNullInputsHaveExplicitBehavior() {
    var reveal = new ResourceReveal();
    var lifetime = new Object();
    var id = UUID.randomUUID();
    assertFalse(reveal.consume(null, lifetime, id));
    assertThrows(NullPointerException.class, () -> reveal.request(null, id));
    assertThrows(NullPointerException.class, () -> reveal.request(lifetime, null));
  }

  @Test
  void revealOpensOnlyAncestorsAndRetainsAuthoredOrderAndProjectValues() {
    var p = new ProjectData();
    var outer = folder(p, "outer", null, "noise2D");
    var inner = folder(p, "inner", outer.uuid, "noise2D");
    var unrelated = folder(p, "unrelated", null, "noise2D");
    folder(p, "hidden", unrelated.uuid, "noise2D");
    var n = new Noise();
    n.id = "target";
    n.parentFolderUuid = inner.uuid;
    p.noise2D.add(n);
    var before = ProjectIO.JSON.valueToTree(p);
    var view = view(p);
    assertEquals("noise2D", view.reveal(n.uuid));
    var rows = view.rows("noise2D", "");
    assertEquals(
        List.of(outer.uuid, inner.uuid, n.uuid, unrelated.uuid),
        rows.stream().map(r -> r.node().uuid()).toList());
    assertTrue(rows.getFirst().open());
    assertTrue(rows.get(1).open());
    assertFalse(rows.getLast().open());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    assertEquals("noise2D", view.reveal(n.uuid));
    assertSame(rows, view.rows("noise2D", ""));
  }

  @Test
  void operationRevealOpensWorldFolderAndCarrierLayerWithoutChangingExecution() {
    var p = new ProjectData();
    var f = folder(p, "world", null, "world");
    var l = new Layer();
    l.id = "layer";
    l.parentFolderUuid = f.uuid;
    var a = new Operation();
    a.id = "a";
    var b = new Operation();
    b.id = "b";
    l.operations.addAll(List.of(a, b));
    p.worldGenerator.layers.add(l);
    var before = ProjectIO.JSON.valueToTree(p);
    var view = view(p);
    view.open(l.uuid, false);
    assertEquals("world", view.reveal(b.uuid));
    var rows = view.rows("world", "");
    assertEquals(List.of(0, 1, 2, 2), rows.stream().map(ResourceTreeIndex.Row::depth).toList());
    assertEquals(
        List.of(f.uuid, l.uuid, a.uuid, b.uuid), rows.stream().map(r -> r.node().uuid()).toList());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void deepReverseAuthoredAncestorRouteDoesNotUseJavaRecursion() {
    var p = new ProjectData();
    UUID parent = null;
    for (int i = 0; i < 4097; i++) parent = folder(p, "folder-" + i, parent, "noise3D").uuid;
    UUID target = parent;
    Collections.reverse(p.folders);
    var view = view(p);
    assertEquals("noise3D", view.reveal(target));
    var rows = view.rows("noise3D", "");
    assertEquals(4097, rows.size());
    assertEquals(target, rows.getLast().node().uuid());
    assertEquals(4096, rows.getLast().depth());
    assertFalse(rows.getLast().open());
  }

  @Test
  void invalidRoutesRejectBeforeChangingAnyExpansionOrCachedRows() {
    for (String mode : List.of("missing", "cycle", "root", "non-container")) {
      var p = new ProjectData();
      var good = folder(p, "good", null, "noise2D");
      folder(p, "child", good.uuid, "noise2D");
      var target = folder(p, "target", null, "noise2D");
      switch (mode) {
        case "missing" -> target.parentFolderUuid = UUID.randomUUID();
        case "cycle" -> {
          var other = folder(p, "other", target.uuid, "noise2D");
          target.parentFolderUuid = other.uuid;
        }
        case "root" -> target.parentFolderUuid = folder(p, "world", null, "world").uuid;
        case "non-container" -> {
          var n = new Noise();
          n.id = "field";
          p.noise2D.add(n);
          target.parentFolderUuid = n.uuid;
        }
        default -> throw new AssertionError();
      }
      var view = view(p);
      var rows = view.rows("noise2D", "");
      assertNull(view.reveal(target.uuid));
      assertSame(rows, view.rows("noise2D", ""));
      assertFalse(
          rows.stream()
              .filter(r -> r.node().uuid().equals(good.uuid))
              .findFirst()
              .orElseThrow()
              .open());
    }
  }

  @Test
  void missingAndVoxelTargetsDoNotExpandTheTree() {
    var p = new ProjectData();
    var f = folder(p, "folder", null, "noise2D");
    var t = new VoxelType();
    t.id = "type";
    p.voxelTypes.add(t);
    var view = view(p);
    var rows = view.rows("noise2D", "");
    assertNull(view.reveal(UUID.randomUUID()));
    assertNull(view.reveal(t.uuid));
    assertSame(rows, view.rows("noise2D", ""));
    assertFalse(rows.getFirst().open());
  }

  @Test
  void acceptedNavigationRequestsRevealWithoutChangingProjectHistoryOrPreferences() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "field";
                p.noise2D.add(n);
              }));
      var id = state.project().noise2D.getFirst().uuid;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      long history = state.document.historyBytes();
      long revision = state.revision;
      state.select("noise2D", id);
      assertNull(state.resourceReveal.current(state.document.sessionToken(), id));
      ((Noise) state.draft).id = "private";
      var draft = state.draft;
      assertTrue(
          state.navigate(
              new EditorEvents.Target(state.project().project.uuid, id, null, null, "", false)));
      assertNotNull(state.resourceReveal.current(state.document.sessionToken(), id));
      assertSame(draft, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void rejectedAndCancelledGuardedNavigationNeverRequestReveal() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                for (String id : List.of("a", "b")) {
                  var n = new Noise();
                  n.id = id;
                  p.noise2D.add(n);
                }
              }));
      var a = state.project().noise2D.getFirst().uuid;
      var b = state.project().noise2D.getLast().uuid;
      state.select("noise2D", a);
      ((Noise) state.draft).id = "private";
      var navigation = new SearchNavigation(state);
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          navigation.request(
              new EditorEvents.Target(state.project().project.uuid, b, null, null, "", false)));
      assertNull(state.resourceReveal.current(state.document.sessionToken(), a));
      assertEquals(
          SearchNavigation.Result.CANCELLED, navigation.decide(SearchNavigation.Decision.CANCEL));
      assertNull(state.resourceReveal.current(state.document.sessionToken(), a));
      assertFalse(
          state.navigate(new EditorEvents.Target(UUID.randomUUID(), b, null, null, "", false)));
      assertNull(state.resourceReveal.current(state.document.sessionToken(), a));
    }
  }
}
