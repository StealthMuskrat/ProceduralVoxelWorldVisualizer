package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferred native-host lifetime and exact footer value ownership, without an ImGui context. */
class TraceActionScopeTest {
  private final Object document = new Object();

  private TraceActionScope.Context context(
      Object owner, String query, int row, int page, boolean pending) {
    return new TraceActionScope.Context(owner, 4, null, 0, row, query, page, true, true, pending);
  }

  @Test
  void consecutiveHostSubmissionsRetainIdsAndSkippedFramesRequireAnotherGesture() {
    var scope = new TraceActionScope();
    var values = context(document, "Outcome", 0, 0, false);
    var first = scope.synchronize(11, 3, values);
    assertEquals(first.version(), scope.synchronize(11, 4, values).version());
    var returned = scope.synchronize(11, 6, values);
    assertNotEquals(first.version(), returned.version());
    assertFalse(scope.current(first, values));
    assertTrue(scope.current(returned, values));
  }

  @Test
  void twoHostsRemainIndependentAndOneCannotKeepTheOtherHiddenPresentationAlive() {
    var scope = new TraceActionScope();
    var values = context(document, "Outcome", 0, 0, false);
    var main = scope.synchronize(11, 3, values);
    var second = scope.synchronize(22, 3, values);
    assertNotEquals(main.version(), second.version());
    assertEquals(main.version(), scope.synchronize(11, 4, values).version());
    assertEquals(second.version(), scope.synchronize(22, 4, values).version());
    scope.synchronize(22, 5, values);
    var returned = scope.synchronize(11, 6, values);
    assertNotEquals(main.version(), returned.version());
    assertEquals(second.version(), scope.synchronize(22, 6, values).version());
    assertTrue(scope.current(second, values));
  }

  @Test
  void rawQueryPageRowPendingAndDocumentChangesEachRetireTheFormerFooter() {
    var scope = new TraceActionScope();
    var values = context(document, "Outcome", 0, 0, false);
    var first = scope.synchronize(11, 3, values);
    for (var replacement :
        java.util.List.of(
            context(document, " Outcome ", 0, 0, false),
            context(document, "Outcome", 1, 0, false),
            context(document, "Outcome", 0, 1, false),
            context(document, "Outcome", 0, 0, true),
            context(new Object(), "Outcome", 0, 0, false),
            new TraceActionScope.Context(document, 5, null, 0, 0, "Outcome", 0, true, true, false),
            new TraceActionScope.Context(document, 4, null, 1, 0, "Outcome", 0, true, true, false),
            new TraceActionScope.Context(
                document, 4, null, 0, 0, "Outcome", 0, false, false, false))) {
      assertFalse(scope.current(first, replacement));
      assertNotEquals(first.version(), scope.synchronize(11, 4, replacement).version());
    }
  }

  @Test
  void nativeFrameCounterRestartCannotReuseAnEarlierContext() {
    var scope = new TraceActionScope();
    var values = context(document, "Outcome", 0, 0, false);
    var first = scope.synchronize(11, 300, values);
    var returned = scope.synchronize(11, 1, values);
    assertNotEquals(first.version(), returned.version());
    assertFalse(scope.current(first, values));
  }
}
