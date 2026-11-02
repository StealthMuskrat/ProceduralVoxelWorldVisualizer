package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred browser-command integration without a native context. */
class ResourceMoveTransactionTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "field";
              p.noise2D.add(noise);
              for (String id : List.of("source", "target")) {
                var layer = new Layer();
                layer.id = id;
                p.worldGenerator.layers.add(layer);
              }
              for (String id : List.of("first", "middle", "last")) {
                var op = new Operation();
                op.id = id;
                op.noiseGeneratorUuid = noise.uuid;
                p.worldGenerator.layers.getFirst().operations.add(op);
              }
              var anchor = new Operation();
              anchor.id = "anchor";
              anchor.noiseGeneratorUuid = noise.uuid;
              p.worldGenerator.layers.getLast().operations.add(anchor);
            }));
    return state;
  }

  @Test
  void ownedMoveResolvesCurrentOrderAndCommitsOneUndoWithoutChangingPreferences() {
    try (var state = state()) {
      var source = state.project().worldGenerator.layers.getFirst();
      var target = state.project().worldGenerator.layers.getLast();
      var first = source.operations.getFirst().uuid;
      var last = source.operations.getLast().uuid;
      var anchor = target.operations.getFirst().uuid;
      var requested = new ArrayList<>(List.of(last, first));
      var command = ResourceBrowserEdits.move(requested, "operation", target.uuid);
      requested.clear();
      assertTrue(
          state.change(p -> Collections.swap(p.worldGenerator.layers.getFirst().operations, 0, 2)));
      var before = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      var token = state.document.sessionToken();
      long revision = state.revision;
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          new ResourceEditSession(state).request("Move", command));
      assertEquals(
          List.of(anchor, last, first),
          state.project().worldGenerator.layers.getLast().operations.stream()
              .map(o -> o.uuid)
              .toList());
      assertEquals(revision + 1, state.revision);
      assertSame(token, state.document.sessionToken());
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      var after = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void placementDropBeforeAnAnchorRetainsSourceOrderAsOneTransaction() {
    try (var state = state()) {
      var source = state.project().worldGenerator.layers.getFirst();
      var target = state.project().worldGenerator.layers.getLast();
      var first = source.operations.getFirst().uuid;
      var last = source.operations.getLast().uuid;
      var anchor = target.operations.getFirst().uuid;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var command = ResourceBrowserEdits.before(List.of(last, first), anchor);
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          new ResourceEditSession(state).request("Drop", command));
      assertEquals(
          List.of(first, last, anchor),
          state.project().worldGenerator.layers.getLast().operations.stream()
              .map(o -> o.uuid)
              .toList());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void staleCutRejectsWithoutPublishingOrConsumingHistory() {
    try (var state = state()) {
      var source = state.project().worldGenerator.layers.getFirst();
      var target = state.project().worldGenerator.layers.getLast().uuid;
      var first = source.operations.getFirst().uuid;
      var last = source.operations.getLast().uuid;
      var cut = new ResourceClipboard(state.project(), List.of(first, last), true);
      assertTrue(
          state.change(
              p ->
                  p.worldGenerator
                      .layers
                      .getFirst()
                      .operations
                      .removeIf(o -> o.uuid.equals(last))));
      var active = state.project();
      var before = ProjectIO.JSON.valueToTree(active);
      long history = state.document.historyBytes(), revision = state.revision;
      var command = ResourceBrowserEdits.paste(cut, "operation", target);
      assertNotEquals(
          ResourceEditSession.Result.APPLIED,
          new ResourceEditSession(state).request("Paste", command));
      assertSame(active, state.project());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }
}
