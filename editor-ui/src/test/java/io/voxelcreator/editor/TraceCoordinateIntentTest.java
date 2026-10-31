package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceCoordinateIntentTest {
  private static TraceSnapshot snapshot(EditorState state, BlockPos point) {
    return new TraceSnapshot(
        state.document.sessionToken(),
        ProjectIO.copy(state.project()),
        state.revision,
        new GenerationTrace(point, 0, List.of()));
  }

  @Test
  void historicalSameProjectCoordinateNeedsANewCurrentDocumentIntent(@TempDir Path out)
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var source = snapshot(state, new BlockPos(-17, 2, 31));
      var old = TraceCoordinateIntent.capture(state, source);
      var file = out.resolve("same.json");
      state.document.save(file);
      assertTrue(state.document.open(file).isEmpty());
      assertFalse(old.current(state));
      assertTrue(source.reopened(state));
      var next = TraceCoordinateIntent.capture(state, source);
      assertTrue(next.current(state));
      assertSame(source, next.snapshot());
      assertEquals(new BlockPos(-17, 2, 31), next.position());
    }
  }

  @Test
  void directCanonicalReplacementAndAuthoredRevisionBothExpireTheIntent() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var source = snapshot(state, new BlockPos(0, 0, 0));
      var old = TraceCoordinateIntent.capture(state, source);
      var document = state.document.sessionToken();
      long revision = state.revision;
      assertTrue(state.document.edit(p -> p.project.seed = 7).isEmpty());
      assertSame(document, state.document.sessionToken());
      assertEquals(revision, state.revision);
      assertFalse(old.current(state));
      var next = TraceCoordinateIntent.capture(state, source);
      assertTrue(next.current(state));
      assertTrue(state.change(p -> p.project.seed = 8));
      assertFalse(next.current(state));
    }
  }

  @Test
  void saveAndFailedOpenRetainTheIntentButANewProjectCannotBorrowItsCoordinate(@TempDir Path out)
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var source = snapshot(state, new BlockPos(1, 2, 3));
      var intent = TraceCoordinateIntent.capture(state, source);
      state.document.save(out.resolve("save.json"));
      var bad = out.resolve("bad.json");
      Files.writeString(bad, "{}");
      assertFalse(state.document.open(bad).isEmpty());
      assertTrue(intent.current(state));
      state.document.newProject();
      assertFalse(intent.current(state));
      assertFalse(TraceCoordinateIntent.capture(state, source).current(state));
    }
  }

  @Test
  void signedHistoricalBoundsNeverParseOrRepairSeparatelyEnteredCoordinates() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var coordinates = new InspectorCoordinates();
      coordinates.edit("X", "-");
      long version = coordinates.version();
      var point = new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -1);
      var source = snapshot(state, point);
      var details = source.details();
      var intent = TraceCoordinateIntent.capture(state, source);
      assertEquals(point, intent.position());
      assertTrue(intent.current(state));
      assertEquals("-", coordinates.text("X"));
      assertEquals(version, coordinates.version());
      assertEquals(details, source.details());
    }
  }
}
