package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred dialog lifetime, complete displayed-input and bounded retry contracts. */
class BookmarkEditSessionTest {
  @TempDir Path directory;

  private EditorState state() {
    return new EditorState(new UserConfig(), false);
  }

  private Map<String, String> shown(BookmarkEditSession session) {
    var values = new LinkedHashMap<String, String>();
    session.axes().forEach(axis -> values.put(axis, session.text(axis)));
    return values;
  }

  private void bookmark(EditorState state) {
    assertTrue(
        state.change(
            p -> {
              var b = new Bookmark();
              b.name = "original";
              b.x = 1;
              b.y = 2;
              b.z = 3;
              p.bookmarks.world.add(b);
            }));
  }

  @Test
  void privateAddPreservesPropertiesAndPublishesOneBookmarkTransaction() {
    try (var state = state()) {
      assertTrue(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "committed";
                p.noise2D.add(noise);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      draft.id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var session = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(-3, null, -7));
      session.editName("same name");
      session.edit("X", "+17");
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertTrue(session.apply(session.name(), shown(session), null));
      assertTrue(session.closed());
      var added = state.project().bookmarks.noise2D.getFirst();
      assertEquals(session.uuid(), added.uuid);
      assertEquals("same name", added.name);
      assertEquals(17, added.x);
      assertNull(added.y);
      assertEquals(-7, added.z);
      assertSame(draft, state.draft);
      assertEquals("private", draft.id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(session.uuid(), state.project().bookmarks.noise2D.getFirst().uuid);
    }
  }

  @Test
  void everyTabRetainsInvalidCoordinatesAndParsesExactEndpointsTogether() {
    for (var tab : Tab.values())
      try (var state = state()) {
        var session =
            BookmarkEditSession.teleport(
                state, tab, new Coordinates(1, tab.threeDimensional() ? 2 : null, 3));
        var calls = new AtomicInteger();
        for (String raw : List.of("-", "+", "2147483648", "-2147483649", "1e3", " 7", "٧")) {
          session.edit("X", raw);
          assertThrows(
              IllegalArgumentException.class,
              () -> session.apply(null, shown(session), (t, c) -> calls.incrementAndGet()));
          assertEquals(raw, session.text("X"));
          assertEquals(0, calls.get());
        }
        session.edit("X", Integer.toString(Integer.MIN_VALUE));
        session.edit("Z", Integer.toString(Integer.MAX_VALUE));
        assertTrue(
            session.apply(
                null,
                shown(session),
                (t, c) -> {
                  assertEquals(tab, t);
                  assertEquals(Integer.MIN_VALUE, c.x());
                  assertEquals(Integer.MAX_VALUE, c.z());
                  assertEquals(tab.threeDimensional() ? 2 : null, c.y());
                }));
        assertFalse(state.document.dirty());
        assertEquals(0, state.document.historyBytes());
      }
  }

  @Test
  void mismatchedMissingOrOversizedDisplayedAxesNeverNavigateAnEarlierCell() {
    try (var state = state()) {
      var session = BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
      var calls = new AtomicInteger();
      var display = shown(session);
      display.put("X", "+1");
      assertThrows(
          IllegalStateException.class,
          () -> session.apply(null, display, (t, c) -> calls.incrementAndGet()));
      display.put("X", "1");
      display.remove("Y");
      assertThrows(
          IllegalStateException.class,
          () -> session.apply(null, display, (t, c) -> calls.incrementAndGet()));
      display.put("Y", "2");
      display.put("Q", "4");
      assertThrows(
          IllegalStateException.class,
          () -> session.apply(null, display, (t, c) -> calls.incrementAndGet()));
      display.remove("Q");
      var oversized = "1".repeat(CoordinateText.MAX_CHARACTERS + 1);
      assertThrows(IllegalArgumentException.class, () -> session.edit("X", oversized));
      display.put("X", oversized);
      assertThrows(
          IllegalStateException.class,
          () -> session.apply(null, display, (t, c) -> calls.incrementAndGet()));
      assertEquals(0, calls.get());
      assertEquals("1", session.text("X"));
    }
  }

  @Test
  void successfulSameUuidReopenInvalidatesAddRenameAndTeleport() throws Exception {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        bookmark(state);
        var file = directory.resolve("same-" + mode + ".json");
        state.document.save(file);
        var uuid = state.project().project.uuid;
        var session =
            switch (mode) {
              case 0 -> BookmarkEditSession.add(state, Tab.WORLD, new Coordinates(1, 2, 3));
              case 1 ->
                  BookmarkEditSession.rename(
                      state, Tab.WORLD, state.project().bookmarks.world.getFirst().uuid);
              default -> BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
            };
        if (session.named()) session.editName("private");
        assertTrue(state.document.open(file).isEmpty());
        assertEquals(uuid, state.project().project.uuid);
        var before = ProjectIO.JSON.valueToTree(state.project());
        assertFalse(session.current());
        assertThrows(
            IllegalStateException.class,
            () ->
                session.apply(
                    session.named() ? session.name() : null,
                    shown(session),
                    (t, c) -> fail("Stale navigation")));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        if (session.named()) assertEquals("private", session.name());
      }
  }

  @Test
  void unrelatedEditsSelectionRefreshSaveAndHistoryRetainTheDialogLifetime() throws Exception {
    try (var state = state()) {
      bookmark(state);
      var target = state.project().bookmarks.world.getFirst().uuid;
      var rename = BookmarkEditSession.rename(state, Tab.WORLD, target);
      rename.editName("renamed");
      var add = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(7, null, -9));
      add.editName("added");
      assertTrue(state.change(p -> p.project.seed = 17));
      state.refreshDraft();
      state.document.save(directory.resolve("current.json"));
      state.document.undo();
      state.document.redo();
      assertTrue(rename.current());
      assertTrue(add.current());
      assertTrue(rename.apply(rename.name(), Map.of(), null));
      assertTrue(add.current());
      assertTrue(add.apply(add.name(), shown(add), null));
      assertEquals(17, state.project().project.seed);
      assertEquals("renamed", state.project().bookmarks.world.getFirst().name);
      assertEquals("added", state.project().bookmarks.noise2D.getFirst().name);
    }
  }

  @Test
  void renamedUpdatedDeletedOrForeignWorldTargetsRejectWithoutOverwriting() {
    for (int mode = 0; mode < 4; mode++)
      try (var state = state()) {
        bookmark(state);
        var session =
            BookmarkEditSession.rename(
                state, Tab.WORLD, state.project().bookmarks.world.getFirst().uuid);
        session.editName("private");
        switch (mode) {
          case 0 -> state.change(p -> p.bookmarks.world.getFirst().name = "elsewhere");
          case 1 -> state.change(p -> p.bookmarks.world.getFirst().x = 99);
          case 2 -> state.change(p -> p.bookmarks.world.clear());
          case 3 -> state.project().worldGenerator.uuid = UUID.randomUUID();
        }
        var before = ProjectIO.JSON.valueToTree(state.project());
        assertFalse(session.current());
        assertThrows(
            IllegalStateException.class, () -> session.apply(session.name(), Map.of(), null));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals("private", session.name());
      }
  }

  @Test
  void reusedCandidateDiagnosticsAndRuntimeFailureRemainBoundedAndCanRetryTheSameUuid() {
    try (var state = state()) {
      var session = BookmarkEditSession.add(state, Tab.WORLD, new Coordinates(1, 2, 3));
      session.editName("retained");
      var uuid = session.uuid();
      var issues =
          IntStream.range(0, 300)
              .mapToObj(i -> Diagnostic.error("REJECT", "/fixture/" + i, "Rejected"))
              .toList();
      state.document.setValidator(p -> issues);
      var before = ProjectIO.JSON.valueToTree(state.project());
      for (int attempt = 0; attempt < 2; attempt++) {
        assertFalse(session.apply(session.name(), shown(session), null));
        assertEquals(300, session.result().issueCount());
        assertEquals(256, session.result().issues().size());
        assertThrows(UnsupportedOperationException.class, () -> session.result().issues().clear());
      }
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      assertFalse(session.apply(session.name(), shown(session), null));
      assertTrue(session.result().issues().isEmpty());
      assertTrue(session.result().failure().contains("IllegalStateException"));
      assertTrue(session.current());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(0, state.revision);
      assertEquals(0, state.document.historyBytes());
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(session.apply(session.name(), shown(session), null));
      assertEquals(uuid, state.project().bookmarks.world.getFirst().uuid);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void navigationFailureAndReentrancyPreserveInputUntilOneAcceptedCallback() {
    try (var state = state()) {
      var session = BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
      session.edit("X", "999");
      var calls = new AtomicInteger();
      assertThrows(
          IllegalStateException.class,
          () ->
              session.apply(
                  null,
                  shown(session),
                  (t, c) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException();
                  }));
      assertFalse(session.closed());
      assertTrue(session.current());
      assertEquals("999", session.text("X"));
      assertTrue(
          state.events.snapshot().stream()
              .anyMatch(e -> e.code().equals("BOOKMARK_NAVIGATION_FAILED") && e.actionRequired()));
      session.edit("X", "23");
      assertTrue(
          session.apply(
              null,
              shown(session),
              (t, c) -> {
                calls.incrementAndGet();
                assertThrows(
                    IllegalStateException.class,
                    () -> session.apply(null, shown(session), (tab, point) -> fail("Reentered")));
                assertThrows(IllegalStateException.class, () -> session.edit("X", "42"));
                assertEquals(23, c.x());
              }));
      assertEquals(2, calls.get());
      assertTrue(session.closed());
      assertThrows(
          IllegalStateException.class,
          () -> session.apply(null, shown(session), (t, c) -> calls.incrementAndGet()));
      assertFalse(state.document.dirty());
      assertEquals(0, state.revision);
    }
  }

  @Test
  void cancelAndEqualValueRenameConsumeSessionsWithoutCreatingHistory() {
    try (var state = state()) {
      bookmark(state);
      var rename =
          BookmarkEditSession.rename(
              state, Tab.WORLD, state.project().bookmarks.world.getFirst().uuid);
      long bytes = state.document.historyBytes();
      assertTrue(rename.apply(rename.name(), Map.of(), null));
      assertTrue(rename.closed());
      assertEquals(bytes, state.document.historyBytes());
      assertThrows(IllegalStateException.class, () -> rename.apply(rename.name(), Map.of(), null));
      var cancelled = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(1, null, 3));
      cancelled.cancel();
      cancelled.cancel();
      assertTrue(cancelled.closed());
      assertThrows(IllegalStateException.class, () -> cancelled.editName("x"));
      assertThrows(IllegalStateException.class, () -> cancelled.edit("X", "9"));
      assertThrows(IllegalStateException.class, () -> cancelled.apply("", shown(cancelled), null));
      assertTrue(state.project().bookmarks.noise2D.isEmpty());
    }
  }

  @Test
  void nameMismatchBlankAndOversizedEditsNeverUseAnEarlierAcceptedName() {
    try (var state = state()) {
      var session = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(1, null, 3));
      session.editName("private");
      assertThrows(
          IllegalStateException.class, () -> session.apply("different", shown(session), null));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.editName("x".repeat(BookmarkEditSession.MAX_NAME_CHARACTERS + 1)));
      assertEquals("private", session.name());
      session.editName(" ");
      assertThrows(IllegalArgumentException.class, () -> session.apply(" ", shown(session), null));
      assertTrue(state.project().bookmarks.noise2D.isEmpty());
      session.editName("corrected");
      assertTrue(session.apply(session.name(), shown(session), null));
      assertEquals("corrected", state.project().bookmarks.noise2D.getFirst().name);
      var largeName = "x".repeat(BookmarkEditSession.MAX_NAME_CHARACTERS + 1);
      assertTrue(state.change(p -> p.bookmarks.noise2D.getFirst().name = largeName));
      var rename = BookmarkEditSession.rename(state, Tab.NOISE_2D, session.uuid());
      assertEquals(largeName, rename.name());
      rename.editName("shorter");
      assertTrue(rename.apply(rename.name(), Map.of(), null));
      assertEquals("shorter", state.project().bookmarks.noise2D.getFirst().name);
    }
  }
}
