package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InspectorTraceRetryTest {
  private static InspectorRequests.Request request(EditorState state) {
    return new InspectorRequests().begin(state.document.sessionToken(), state.revision, false);
  }

  @Test
  void exactSignedTargetNeverParsesOrRepairsTheEnteredCoordinate() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var input = new InspectorCoordinates();
      input.edit("X", "-");
      long version = input.version();
      var point = new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17);
      var request = request(state);
      var retry = InspectorTraceRetry.capture(state, request, point, version);
      assertEquals(point, retry.position());
      assertEquals(request.serial(), retry.serial());
      assertTrue(retry.current(state, version));
      assertEquals("-", input.text("X"));
      assertEquals(version, input.version());
      assertThrows(IllegalArgumentException.class, input::coordinate);
    }
  }

  @Test
  void saveAndFailedOpenKeepRecoveryButAnInputRoundTripRetiresIt(@TempDir Path out)
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var input = new InspectorCoordinates();
      var retry =
          InspectorTraceRetry.capture(
              state, request(state), new BlockPos(7, 0, 3), input.version());
      state.document.save(out.resolve("saved.json"));
      var bad = out.resolve("bad.json");
      Files.writeString(bad, "{}");
      assertFalse(state.document.open(bad).isEmpty());
      assertTrue(retry.current(state, input.version()));
      input.edit("X", "2");
      input.edit("X", "0");
      assertFalse(retry.current(state, input.version()));
    }
  }

  @Test
  void canonicalReplacementRevisionAndSameUuidOpenEachExpireRecovery(@TempDir Path out)
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var file = out.resolve("saved.json");
      state.document.save(file);
      var point = new BlockPos(7, 0, 3);
      var old = InspectorTraceRetry.capture(state, request(state), point, 0);
      long revision = state.revision;
      assertTrue(state.document.edit(p -> p.project.seed++).isEmpty());
      assertEquals(revision, state.revision);
      assertFalse(old.current(state, 0));
      var next = InspectorTraceRetry.capture(state, request(state), point, 0);
      assertTrue(state.change(p -> p.project.seed++));
      assertFalse(next.current(state, 0));
      var reopened = InspectorTraceRetry.capture(state, request(state), point, 0);
      var uuid = state.project().project.uuid;
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(uuid, state.project().project.uuid);
      assertFalse(reopened.current(state, 0));
    }
  }

  @Test
  void aPickUnboundOrForeignRequestCannotCreateCoordinateRecovery() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var gate = new InspectorRequests();
      var point = new BlockPos(7, 0, 3);
      for (var request :
          new InspectorRequests.Request[] {
            gate.begin(state.document.sessionToken(), state.revision, true),
            gate.begin(state.revision, false),
            gate.begin(new Object(), state.revision, false),
            gate.begin(state.document.sessionToken(), state.revision + 1, false)
          })
        assertThrows(
            IllegalArgumentException.class,
            () -> InspectorTraceRetry.capture(state, request, point, 0));
      var retry = InspectorTraceRetry.capture(state, request(state), point, 0);
      state.project().project.uuid = java.util.UUID.randomUUID();
      assertFalse(retry.current(state, 0));
    }
  }
}
