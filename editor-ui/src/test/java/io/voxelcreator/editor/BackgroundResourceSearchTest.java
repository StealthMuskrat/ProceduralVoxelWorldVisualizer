package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred committed Find workers, coalescing, delivery and failure boundaries. */
class BackgroundResourceSearchTest {
  static final long QUIET = ResourceSearchSession.DEBOUNCE_NANOS;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;
    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("Find queue full");
      tasks.add(task);
    }
    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
      tasks.remove().run();
    }
  }

  static EditorState state(int count) {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      for (int i = 0; i < count; i++) {
        var n = new Noise(); n.id = i == count - 1 ? "needle" : "field-" + i;
        n.algorithm.key = "CONSTANT"; n.algorithm.parameters.clear();
        n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
      }
    }));
    return s;
  }

  static ResourceSearchIndex.Filter text(String query) {
    return new ResourceSearchIndex.Filter(query, "noise2D", "All", "All", null, null, false,
        ResourceSearchIndex.Mode.ID);
  }

  static List<ResourceSearchIndex.Hit> observe(BackgroundResourceSearch find, EditorState s,
      AtomicReference<ResourceSearchIndex.Filter> live, String text, long now, boolean immediate) {
    live.set(text(text));
    return find.observe(s.project(), s.revision, live.get(), now, immediate);
  }

  @Test
  void actualWorkerOwnsItsModelAndCannotPublishBeforeUiDelivery() throws Exception {
    try (var s = state(3); var worker = Executors.newSingleThreadExecutor()) {
      var live = new AtomicReference<>(text("needle"));
      var ui = new LinkedBlockingQueue<Runnable>();
      var builtOn = new AtomicReference<Thread>(); var searchedOn = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      try (var find = new BackgroundResourceSearch(s, worker, ui::add, live::get, p -> {
        assertNotSame(s.project(), p); builtOn.set(Thread.currentThread());
        return new ResourceSearchIndex(p);
      }, (index, filter) -> { searchedOn.set(Thread.currentThread()); return index.search(filter); })) {
        assertTrue(observe(find, s, live, "needle", 0, true).isEmpty());
        var delivery = ui.poll(10, TimeUnit.SECONDS); assertNotNull(delivery);
        assertNotSame(caller, builtOn.get()); assertSame(builtOn.get(), searchedOn.get());
        assertTrue(find.pending()); assertFalse(find.matches(live.get()));
        delivery.run(); delivery.run();
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        var rows = observe(find, s, live, "needle", 1, false);
        assertEquals(List.of("needle"), rows.stream().map(ResourceSearchIndex.Hit::label).toList());
        assertTrue(find.matches(live.get())); assertEquals(1, find.builds());
        assertThrows(UnsupportedOperationException.class, () -> rows.add(rows.getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().references().add(UUID.randomUUID()));
      }
    }
  }

  @Test
  void hundredRequestBurstRunsOnlyOneTrailingWorkerScan() {
    try (var s = state(256)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text(""));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "", 0, true); workers.run(); ui.remove().run();
        assertTrue(find.indexed() >= ResourceSearchSession.DEBOUNCE_ROWS);
        var previous = observe(find, s, live, "", 0, false);
        for (int i = 1; i <= 100; i++) {
          assertSame(previous, observe(find, s, live, "needle" + i, i, false));
          assertTrue(find.pending()); assertEquals(1, find.queries());
        }
        assertSame(previous, observe(find, s, live, "needle100", QUIET + 99, false));
        observe(find, s, live, "needle100", QUIET + 100, false);
        assertEquals(2, find.queries()); assertEquals(1, workers.tasks.size());
        workers.run();
        assertSame(previous, observe(find, s, live, "needle100", QUIET + 101, false));
        ui.remove().run();
        assertTrue(observe(find, s, live, "needle100", QUIET + 102, false).isEmpty());
        assertEquals(1, find.builds()); assertTrue(find.matches(live.get()));
      }
    }
  }

  @Test
  void typingDuringInitialBuildReusesItsIndexButNeverItsOldQueryRows() {
    try (var s = state(256)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text(""));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "", 0, true);
        for (String query : List.of("n", "ne", "needle")) observe(find, s, live, query, 10, false);
        assertEquals(1, find.builds()); assertEquals(1, workers.tasks.size());
        workers.run(); ui.remove().run();
        assertTrue(find.pending()); assertFalse(find.matches(text("")));
        assertTrue(observe(find, s, live, "needle", QUIET + 9, false).isEmpty());
        observe(find, s, live, "needle", QUIET + 10, false);
        workers.run(); ui.remove().run();
        assertEquals(List.of("needle"), observe(find, s, live, "needle", QUIET + 11, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
        assertEquals(1, find.builds()); assertEquals(2, find.queries());
      }
    }
  }

  @Test
  void explicitFilterDuringBuildBypassesTypingDelayWhenIndexArrives() {
    try (var s = state(256)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text(""));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "", 0, true);
        observe(find, s, live, "needle", 10, true);
        workers.run(); ui.remove().run();
        observe(find, s, live, "needle", 10, false);
        assertEquals(2, find.queries()); workers.run(); ui.remove().run();
        assertTrue(find.matches(live.get())); assertEquals(1, find.builds());
      }
    }
  }

  @Test
  void normalizedTypingAndReturningToAcceptedFilterPreserveCache() {
    try (var s = state(256)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text(""));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "", 0, true); workers.run(); ui.remove().run();
        var accepted = observe(find, s, live, "", 0, false);
        observe(find, s, live, "needle", 1, false);
        observe(find, s, live, " NEEDLE ", QUIET, false);
        assertEquals(1, find.queries());
        assertSame(accepted, observe(find, s, live, "", QUIET, false));
        assertFalse(find.pending()); assertEquals(1, find.queries());
      }
    }
  }

  @Test
  void scopeChangesAndEmptyQueriesUseImmediateWorkersWithOneIndex() {
    try (var s = state(256)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("needle"));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "needle", 0, true); workers.run(); ui.remove().run();
        var scope = new ResourceSearchIndex.Filter("needle", "source control", "All", "noise2D",
            null, null, false, ResourceSearchIndex.Mode.ANY_TEXT);
        live.set(scope); find.observe(s.project(), s.revision, scope, 0, false);
        assertEquals(2, find.queries()); workers.run(); ui.remove().run();
        observe(find, s, live, "", 0, false);
        assertEquals(3, find.queries()); workers.run(); ui.remove().run();
        assertEquals(1, find.builds());
      }
    }
  }

  @Test
  void changedLiveTextBeforeAnotherFrameCannotAdoptCompletedRows() {
    try (var s = state(3)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("needle"));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "needle", 0, true); workers.run();
        live.set(text("field-0")); ui.remove().run();
        assertFalse(find.matches(text("needle"))); assertTrue(find.pending());
        assertTrue(observe(find, s, live, "field-0", 0, false).isEmpty());
        workers.run(); ui.remove().run();
        assertEquals(List.of("field-0"), observe(find, s, live, "field-0", 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
        assertEquals(1, find.builds());
      }
    }
  }

  @Test
  void oldQueryCallbacksCannotReplaceACompletedSuccessor() {
    try (var s = state(3)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text(""));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "", 0, true); workers.run(); ui.remove().run();
        observe(find, s, live, "field-0", 0, true); workers.run(); var old = ui.remove();
        observe(find, s, live, "needle", 0, true); old.run();
        assertFalse(find.matches(text("field-0")));
        workers.run(); ui.remove().run(); old.run();
        assertTrue(find.matches(text("needle"))); assertEquals(1, find.builds());
      }
    }
  }

  @Test
  void committedRevisionAndSameUuidOpenReplaceTheIndexOwner(@TempDir Path directory) throws Exception {
    for (boolean reopen : new boolean[] {false, true})
      try (var s = state(3)) {
        var path = directory.resolve("same.json"); s.document.save(path);
        var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
        var live = new AtomicReference<>(text("needle"));
        try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
          observe(find, s, live, "needle", 0, true); workers.run(); var old = ui.remove();
          if (reopen) assertTrue(s.document.open(path).isEmpty());
          else assertTrue(s.change(p -> p.noise2D.getLast().id = "new-needle"));
          old.run(); assertFalse(find.matches(live.get()));
          observe(find, s, live, "needle", 0, false); workers.run(); ui.remove().run();
          var rows = observe(find, s, live, "needle", 0, false);
          assertEquals(List.of(reopen ? "needle" : "new-needle"),
              rows.stream().map(ResourceSearchIndex.Hit::label).toList());
          assertEquals(2, find.builds());
        }
      }
  }

  @Test
  void inPlaceSourceMutationRequiresExplicitRecaptureAndNeverPublishesOldText() {
    try (var s = state(3)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("needle"));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "needle", 0, true); workers.run();
        s.project().noise2D.getLast().id = "new-needle";
        ui.remove().run(); assertTrue(find.failed()); assertFalse(find.matches(live.get()));
        long builds = find.builds();
        for (int i = 0; i < 8; i++) observe(find, s, live, "needle", i, false);
        assertEquals(builds, find.builds());
        find.retry(); observe(find, s, live, "needle", 0, false); workers.run(); ui.remove().run();
        assertEquals(List.of("new-needle"), observe(find, s, live, "needle", 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
      }
    }
  }

  @Test
  void failuresAndCancelledFuturesRequireExplicitRetryWithoutUnchangedFrameLoops() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state(3)) {
        var workers = new Workers(); workers.reject = mode == 0;
        var ui = new ArrayDeque<Runnable>(); var broken = new AtomicBoolean(true);
        var live = new AtomicReference<>(text("needle")); final int failureMode = mode;
        try (var find = new BackgroundResourceSearch(s, workers, action -> {
          if (failureMode == 2 && broken.get()) throw new AssertionError("handoff"); ui.add(action);
        }, live::get, p -> {
          if (failureMode == 1 && broken.get()) throw new AssertionError("index");
          return new ResourceSearchIndex(p);
        }, ResourceSearchIndex::search)) {
          observe(find, s, live, "needle", 0, true);
          if (mode == 1 || mode == 2) workers.run();
          if (!ui.isEmpty()) ui.remove().run();
          if (mode == 3) ((Future<?>) workers.tasks.getFirst()).cancel(false);
          assertTrue(find.failed()); assertFalse(find.matches(live.get()));
          long builds = find.builds();
          for (int i = 0; i < 8; i++) observe(find, s, live, "needle", i, false);
          assertEquals(builds, find.builds());
          broken.set(false); workers.reject = false;
          find.retry(); observe(find, s, live, "needle", 0, false);
          workers.run(); ui.remove().run(); assertTrue(find.matches(live.get()));
        }
      }
  }

  @Test
  void collapseAndCloseRetireQueuedOrUndeliveredWorkAndReleaseRetainedIndex() {
    try (var s = state(3)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("needle"));
      var find = new BackgroundResourceSearch(s, workers, ui::add, live::get);
      observe(find, s, live, "needle", 0, true); workers.run(); var old = ui.remove();
      find.cancelPending(); old.run(); assertFalse(find.matches(live.get()));
      observe(find, s, live, "needle", 0, false); workers.run(); ui.remove().run();
      assertTrue(find.matches(live.get())); assertTrue(find.indexed() > 0);
      find.clear(); assertEquals(0, find.indexed());
      observe(find, s, live, "needle", 0, true); workers.run(); old = ui.remove();
      find.close(); old.run();
      assertTrue(observe(find, s, live, "needle", 0, true).isEmpty());
      assertEquals(0, find.indexed()); assertFalse(find.matches(live.get()));
    }
  }

  @Test
  void searchDoesNotReadOrPublishPrivatePropertiesRawSeedsOrPreferences() {
    try (var s = state(3)) {
      s.select("noise2D", s.project().noise2D.getLast().uuid);
      var draft = (Noise) s.draft; draft.id = "private";
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var project = ProjectIO.JSON.valueToTree(s.project());
      var preferences = ProjectIO.JSON.valueToTree(s.user);
      long revision = s.revision, history = s.document.historyBytes();
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("needle"));
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        observe(find, s, live, "needle", 0, true); workers.run(); ui.remove().run();
        assertEquals(List.of("needle"), observe(find, s, live, "needle", 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
        assertSame(draft, s.draft); assertEquals("private", draft.id);
        assertEquals("-", s.seedInputs.text("seedOffset"));
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(preferences, ProjectIO.JSON.valueToTree(s.user));
        assertEquals(revision, s.revision); assertEquals(history, s.document.historyBytes());
      }
    }
  }

  @Test
  void debounceClockRegressionAndWrapPreserveTheQuietBoundary() {
    for (long start : new long[] {1000, Long.MAX_VALUE - QUIET / 2})
      try (var s = state(256)) {
        var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
        var live = new AtomicReference<>(text(""));
        try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
          observe(find, s, live, "", 0, true); workers.run(); ui.remove().run();
          observe(find, s, live, "needle", start, false);
          observe(find, s, live, "needle", start + QUIET - 1, false);
          assertEquals(1, find.queries());
          observe(find, s, live, "needle", start + QUIET, false);
          assertEquals(2, find.queries()); workers.run(); ui.remove().run();
          observe(find, s, live, "field", 1000, false);
          observe(find, s, live, "field", 999, false);
          observe(find, s, live, "field", 999 + QUIET - 1, false);
          assertEquals(2, find.queries());
          observe(find, s, live, "field", 999 + QUIET, false);
          assertEquals(3, find.queries());
        }
      }
  }

  @Test
  void failedScanRetainsPreviousRowsAndChangingFiltersCanRetryWithoutRebuilding() {
    try (var s = state(3)) {
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var live = new AtomicReference<>(text("")); var broken = new AtomicBoolean(false);
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get,
          ResourceSearchIndex::new, (index, filter) -> {
            if (broken.get()) throw new AssertionError("scan"); return index.search(filter);
          })) {
        observe(find, s, live, "", 0, true); workers.run(); ui.remove().run();
        var before = observe(find, s, live, "", 0, false);
        broken.set(true); observe(find, s, live, "needle", 0, true);
        workers.run(); ui.remove().run(); assertTrue(find.failed());
        assertSame(before, observe(find, s, live, "needle", 0, false));
        assertFalse(find.matches(text("")));
        broken.set(false); observe(find, s, live, "field-0", 0, true);
        workers.run(); ui.remove().run();
        assertEquals(List.of("field-0"), observe(find, s, live, "field-0", 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
        assertEquals(1, find.builds());
      }
    }
  }

  @Test
  void exactReferenceAndContainerFiltersUseOwnedUuidRoutes() {
    try (var s = state(0)) {
      var fixture = ResourceSearchIndexTest.fixture();
      assertTrue(s.change(p -> {
        p.noise2D.addAll(fixture.noise2D); p.folders.addAll(fixture.folders);
        p.worldGenerator.layers.addAll(fixture.worldGenerator.layers);
      }));
      var source = s.project().noise2D.getFirst().uuid;
      var consumer = s.project().noise2D.getLast().uuid;
      var input = new ResourceSearchIndex.Filter("", "input binding", "All", "noise2D", null,
          source, false, ResourceSearchIndex.Mode.ANY_TEXT);
      var live = new AtomicReference<>(input);
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var find = new BackgroundResourceSearch(s, workers, ui::add, live::get)) {
        find.observe(s.project(), s.revision, input, 0, true); workers.run(); ui.remove().run();
        var rows = find.observe(s.project(), s.revision, input, 0, false);
        assertEquals(1, rows.size()); assertEquals(consumer, rows.getFirst().resource());
        assertTrue(rows.getFirst().target().pointer().endsWith("/inputs/linked"));
        var container = new ResourceSearchIndex.Filter("", "noise2D", "All", "noise2D", null,
            source, true, ResourceSearchIndex.Mode.ANY_TEXT);
        live.set(container); find.observe(s.project(), s.revision, container, 0, false);
        workers.run(); ui.remove().run();
        assertEquals(List.of("consumer"), find.observe(s.project(), s.revision, container, 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
      }
    }
  }

}
