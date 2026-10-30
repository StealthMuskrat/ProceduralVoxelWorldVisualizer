package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deferred settings presentation ownership; session validation remains the publication boundary.
 */
class SettingsActionScopeTest {
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";

  @Test
  void privateProjectTextAndPreferencesRetainScopeButCancelReopenCannotReuseIt() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var scope = new SettingsActionScope();
      var session = new ProjectSettingsSession(state);
      long first = scope.synchronize(state.project(), session, state.revision);
      session.edit("seed", "-");
      state.user.appearance.globalScale = 2.5f;
      assertEquals(first, scope.synchronize(state.project(), session, state.revision));
      assertEquals("-", session.text("seed"));
      session.cancel();
      scope.release();
      assertFalse(scope.current(state.project(), session, state.revision));
      assertEquals(first, scope.version());
      var next = new ProjectSettingsSession(state);
      assertTrue(scope.synchronize(state.project(), next, state.revision) > first);
      assertFalse(scope.current(state.project(), session, state.revision));
    }
  }

  @Test
  void revisionAndCommittedReplacementChangeScopeWithoutGrantingAStaleFormPublication() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var scope = new SettingsActionScope();
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      long first = scope.synchronize(state.project(), session, state.revision);
      state.revision++;
      assertFalse(scope.current(state.project(), session, state.revision));
      long second = scope.synchronize(state.project(), session, state.revision);
      assertTrue(second > first);
      assertThrows(IllegalStateException.class, () -> session.apply(state));
      assertTrue(state.change(p -> p.project.seed = 9));
      assertFalse(scope.current(state.project(), session, state.revision));
      assertTrue(scope.synchronize(state.project(), session, state.revision) > second);
      assertThrows(IllegalStateException.class, () -> session.apply(state));
      assertEquals(9, state.project().project.seed);
    }
  }

  @Test
  void privateMetadataEditsRetainScopeWhileRevertAndBuiltInsReplaceItsDraft() {
    var current = AppMetadata.defaults();
    var session = new MetadataEditSession(current);
    var scope = new SettingsActionScope();
    long first = scope.synchronize(current, session.draft());
    var before = ProjectIO.JSON.valueToTree(current);
    session.inputs().override(VALUE, true);
    session.inputs().editAll(VALUE, List.of("0.625"));
    assertEquals(first, scope.synchronize(current, session.draft()));
    assertEquals(before, ProjectIO.JSON.valueToTree(current));
    session.stageBuiltIns();
    assertFalse(scope.current(current, session.draft()));
    long second = scope.synchronize(current, session.draft());
    assertTrue(second > first);
    session.revert(current);
    assertFalse(scope.current(current, session.draft()));
    assertTrue(scope.synchronize(current, session.draft()) > second);
  }

  @Test
  void failedMetadataSaveKeepsScopeAndRawInputButSuccessfulRetryRequiresANewScope(
      @TempDir Path directory) throws Exception {
    var current = AppMetadata.defaults();
    var session = new MetadataEditSession(current);
    var scope = new SettingsActionScope();
    session.inputs().override(VALUE, true);
    session.inputs().editAll(VALUE, List.of("0.625"));
    long first = scope.synchronize(current, session.draft());
    var before = ProjectIO.JSON.valueToTree(current);
    var file = directory.resolve("app-config.json");
    Files.createDirectory(file);
    Files.writeString(file.resolve("keep"), "retained");
    assertThrows(IllegalStateException.class, () -> session.apply(current, file));
    assertEquals("retained", Files.readString(file.resolve("keep")));
    assertEquals(first, scope.synchronize(current, session.draft()));
    assertEquals(List.of("0.625"), session.inputs().text(VALUE));
    assertEquals(before, ProjectIO.JSON.valueToTree(current));
    Files.delete(file.resolve("keep"));
    Files.delete(file);
    var accepted = session.apply(current, file);
    assertFalse(scope.current(accepted, session.draft()));
    assertTrue(scope.synchronize(accepted, session.draft()) > first);
    assertEquals(.625, AppMetadata.read(file).creationDefaults.get(VALUE).asDouble());
    assertEquals(before, ProjectIO.JSON.valueToTree(current));
  }
}
