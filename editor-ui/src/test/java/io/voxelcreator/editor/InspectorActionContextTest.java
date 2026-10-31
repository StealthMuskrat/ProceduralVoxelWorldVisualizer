package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.storage.BlockPos;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred exact footer input/request ownership without native state or background work. */
class InspectorActionContextTest {
  private InspectorActionContext capture(
      EditorState state,
      InspectorCoordinates coordinates,
      Map<String, String> displayed,
      TraceSnapshot trace,
      InspectorRequests.Request request,
      boolean page) {
    return InspectorActionContext.capture(
        state,
        coordinates,
        displayed,
        new InspectorPanel.Status(trace, request, "progress", "hint", "error"),
        page);
  }

  @Test
  void snapshotsOwnBothRawAndDisplayedTextAndIgnoreMapInsertionOrder() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var coordinates = new InspectorCoordinates();
      var displayed = new LinkedHashMap<>(Map.of("X", "0", "Y", "0", "Z", "0"));
      var first = capture(state, coordinates, displayed, null, null, false);
      var reverse = new LinkedHashMap<String, String>();
      reverse.put("Z", "0");
      reverse.put("Y", "0");
      reverse.put("X", "0");
      assertTrue(first.same(capture(state, coordinates, reverse, null, null, false)));
      displayed.put("X", "9");
      assertEquals("0", first.displayed().get("X"));
      assertFalse(first.same(capture(state, coordinates, displayed, null, null, false)));
      assertThrows(UnsupportedOperationException.class, () -> first.raw().put("X", "1"));
      assertThrows(UnsupportedOperationException.class, () -> first.displayed().put("X", "1"));
    }
  }

  @Test
  void invalidAcceptedTextAndUnacceptedNativeTextNeverBecomeFallbackCoordinates() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var coordinates = new InspectorCoordinates();
      coordinates.edit("X", "-");
      var raw = Map.of("X", "-", "Y", "0", "Z", "0");
      var first = capture(state, coordinates, raw, null, null, false);
      assertEquals("-", first.raw().get("X"));
      assertThrows(IllegalArgumentException.class, coordinates::coordinate);
      assertFalse(
          first.same(
              capture(
                  state, coordinates, Map.of("X", "2", "Y", "0", "Z", "0"), null, null, false)));
      assertEquals("-", coordinates.text("X"));
      coordinates.edit("X", "2");
      coordinates.edit("X", "-");
      assertFalse(first.same(capture(state, coordinates, raw, null, null, false)));
    }
  }

  @Test
  void equalValuedRequestReplacementAndSnapshotReplacementCannotBorrowTheFormerReview() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var coordinates = new InspectorCoordinates();
      var raw = Map.of("X", "0", "Y", "0", "Z", "0");
      var request = new InspectorRequests().begin(3, true);
      var clone =
          new InspectorRequests.Request(
              request.serial(), request.revision(), request.viewVersion(), request.viewBound());
      assertEquals(request, clone);
      var result = new GenerationTrace(new BlockPos(0, 0, 0), 0, List.of());
      var trace = new TraceSnapshot(state.project(), state.revision, result);
      var first = capture(state, coordinates, raw, trace, request, false);
      assertFalse(first.same(capture(state, coordinates, raw, trace, clone, false)));
      assertFalse(
          first.same(
              capture(
                  state,
                  coordinates,
                  raw,
                  new TraceSnapshot(state.project(), state.revision, result),
                  request,
                  false)));
      assertFalse(first.same(capture(state, coordinates, raw, trace, request, true)));
      var status = new InspectorPanel.Status(trace, request, "", "", "");
      assertTrue(status.pending());
      assertTrue(status.pick());
      assertFalse(new InspectorPanel.Status(trace, null, "", "", "").pending());
    }
  }

  @Test
  void sameRawResetRevisionAndSameUuidNewLifetimeEachRequireAnotherGesture() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var coordinates = new InspectorCoordinates();
      var raw = Map.of("X", "0", "Y", "0", "Z", "0");
      var first = capture(state, coordinates, raw, null, null, false);
      coordinates.set(new BlockPos(0, 0, 0));
      assertFalse(first.same(capture(state, coordinates, raw, null, null, false)));
      var reset = capture(state, coordinates, raw, null, null, false);
      state.revision++;
      assertFalse(reset.same(capture(state, coordinates, raw, null, null, false)));
      var revised = capture(state, coordinates, raw, null, null, false);
      var id = state.project().project.uuid;
      state.newSession();
      state.project().project.uuid = id;
      var next = capture(state, coordinates, raw, null, null, false);
      assertNotSame(revised.lifetime(), next.lifetime());
      assertFalse(revised.same(next));
    }
  }
}
