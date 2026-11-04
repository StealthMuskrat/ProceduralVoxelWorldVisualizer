package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred production profile worker and activation definitions. */
class BackgroundMetadataApplyTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;
    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("profile queue full");
      tasks.add(task);
    }
    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
      tasks.remove().run();
    }
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    s.metadata = AppMetadata.defaults();
    return s;
  }

  static Map<String, List<String>> raw(MetadataEditSession edit) {
    var result = new LinkedHashMap<String, List<String>>();
    for (var id : edit.inputs().ids()) result.put(id, edit.inputs().text(id));
    return result;
  }

  static MetadataApplySession writer(EditorState s, MetadataEditSession edit, Executor worker,
      Queue<Runnable> ui) {
    return new MetadataApplySession(s, worker, ui::add, () -> raw(edit), new PreferenceFiles());
  }

  @Test
  void realWorkerPreparesWithoutActivationAndOwnerDecisionPublishesOnlyOnce() throws Exception {
    try (var s = state(); var worker = Executors.newSingleThreadExecutor()) {
      var edit = new MetadataEditSession(s.metadata);
      edit.draft().controls.get("noise").label = "Custom";
      var caller = Thread.currentThread();
      var thread = new AtomicReference<Thread>();
      var ui = new LinkedBlockingQueue<Runnable>();
      var target = directory.resolve("profile.json");
      Files.writeString(target, "previous");
      var files = new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          thread.set(Thread.currentThread()); Files.write(temp, bytes);
        }
        public void replace(Path temp, Path target) throws IOException {
          assertSame(caller, Thread.currentThread());
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      });
      var active = s.metadata;
      try (var apply = new MetadataApplySession(s, worker, ui::add, () -> raw(edit), files)) {
        apply.start(edit, target);
        var delivery = ui.poll(10, TimeUnit.SECONDS);
        assertNotNull(delivery);
        assertNotSame(caller, thread.get());
        assertSame(active, s.metadata);
        assertEquals("previous", Files.readString(target));
        delivery.run(); delivery.run();
        assertTrue(apply.ready());
        assertSame(active, s.metadata);
        assertTrue(apply.accept());
        assertFalse(apply.accept());
        assertEquals("Custom", s.metadata.controls.get("noise").label);
        assertEquals(ProjectIO.JSON.valueToTree(s.metadata), ProjectIO.JSON.readTree(target.toFile()));
        assertFalse(edit.changed());
      }
    }
  }

  @Test
  void activationLeavesDocumentPropertiesRawInputHistoryAndPreferencesUntouched() throws Exception {
    try (var s = state()) {
      var edit = new MetadataEditSession(s.metadata);
      edit.draft().controls.get("noise").label = "Custom";
      var project = ProjectIO.JSON.valueToTree(s.project());
      var user = ProjectIO.JSON.valueToTree(s.user);
      var draft = s.draft;
      long revision = s.revision, history = s.document.historyBytes();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var apply = writer(s, edit, worker, ui)) {
        apply.start(edit, directory.resolve("profile.json"));
        worker.run(); ui.remove().run();
        assertTrue(apply.accept());
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(user, ProjectIO.JSON.valueToTree(s.user));
        assertSame(draft, s.draft);
        assertEquals(revision, s.revision);
        assertEquals(history, s.document.historyBytes());
      }
    }
  }

  @Test
  void readyResultCancellationRetainsDestinationAndPrivateDraft() throws Exception {
    for (boolean close : new boolean[] {false, true})
      try (var s = state()) {
        var edit = new MetadataEditSession(s.metadata);
        edit.draft().controls.get("noise").label = "Private";
        var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
        var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
        try (var apply = writer(s, edit, worker, ui)) {
          apply.start(edit, target); worker.run();
          var delivery = ui.remove(); delivery.run();
          assertTrue(apply.ready());
          if (close) apply.close(); else apply.cancel();
          delivery.run();
          assertFalse(apply.accept());
          assertEquals("previous", Files.readString(target));
          assertEquals("Private", edit.draft().controls.get("noise").label);
          try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
        }
      }
  }

  @Test
  void draftRawDisplayActiveAndSessionReplacementRetirePreparedResults() throws Exception {
    for (int change = 0; change < 7; change++)
      try (var s = state()) {
        var edit = new MetadataEditSession(s.metadata);
        String id = "noise2D.algorithm.CONSTANT.value";
        edit.inputs().override(id, true);
        edit.inputs().editAll(id, List.of("0.75"));
        var display = new AtomicReference<>(raw(edit));
        var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
        var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
        try (var apply = new MetadataApplySession(s, worker, ui::add, display::get, new PreferenceFiles())) {
          apply.start(edit, target); worker.run(); ui.remove().run();
          assertTrue(apply.ready());
          switch (change) {
            case 0 -> edit.draft().controls.get("noise").label = "New private";
            case 1 -> edit.inputs().editAll(id, List.of("+0.75"));
            case 2 -> display.set(Map.of(id, List.of("0.750")));
            case 3 -> s.metadata.controls.get("noise").label = "New active";
            case 4 -> s.metadata = AppMetadata.copy(s.metadata);
            case 5 -> edit.revert(s.metadata);
            case 6 -> edit.stageBuiltIns();
          }
          var active = s.metadata;
          assertFalse(apply.accept());
          assertSame(active, s.metadata);
          assertEquals("previous", Files.readString(target));
          try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
        }
      }
  }

  @Test
  void invalidProfileAndRawInputNeverReplaceDestination() throws Exception {
    for (boolean raw : new boolean[] {false, true})
      try (var s = state()) {
        var edit = new MetadataEditSession(s.metadata);
        if (raw) {
          String id = "noise2D.seedOffset";
          edit.inputs().override(id, true); edit.inputs().editAll(id, List.of("-"));
        } else edit.draft().controls.get("noise").label = "";
        var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
        var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
        try (var apply = writer(s, edit, worker, ui)) {
          apply.start(edit, target);
          if (!worker.tasks.isEmpty()) { worker.run(); ui.remove().run(); }
          assertFalse(apply.running());
          assertFalse(apply.accept());
          assertTrue(apply.message().contains("failed"));
          assertEquals("previous", Files.readString(target));
        }
      }
  }

  @Test
  void schedulerHandoffAndCancelledFutureNeedExplicitRetry() throws Exception {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var edit = new MetadataEditSession(s.metadata);
        var worker = new Workers(); worker.reject = mode == 0;
        var ui = new ArrayDeque<Runnable>(); var broken = new AtomicBoolean(mode == 1);
        var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
        try (var apply = new MetadataApplySession(s, worker, action -> {
          if (broken.get()) throw new AssertionError("handoff"); ui.add(action);
        }, () -> raw(edit), new PreferenceFiles())) {
          apply.start(edit, target);
          if (mode == 1) worker.run();
          if (mode == 2) ((Future<?>) worker.tasks.getFirst()).cancel(false);
          assertFalse(apply.running());
          long version = apply.version();
          for (int i = 0; i < 8; i++) assertFalse(apply.running());
          assertEquals(version, apply.version());
          assertEquals("previous", Files.readString(target));
          broken.set(false); worker.reject = false;
          apply.start(edit, target); worker.run(); ui.remove().run();
          assertTrue(apply.accept());
        }
      }
  }

  @Test
  void writeAndReplacementFailureKeepActiveProfileAndAllowExplicitRetry() throws Exception {
    for (boolean writing : new boolean[] {false, true})
      try (var s = state()) {
        var active = s.metadata;
        var edit = new MetadataEditSession(active); edit.draft().controls.get("noise").label = "Private";
        var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
        var broken = new AtomicBoolean(true);
        var files = new PreferenceFiles(new PreferenceFiles.Storage() {
          public void write(Path temp, byte[] bytes) throws IOException {
            Files.write(temp, bytes); if (writing && broken.get()) throw new AssertionError("write");
          }
          public void replace(Path temp, Path destination) throws IOException {
            if (!writing && broken.get()) throw new IOException("replace");
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          }
        });
        var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
        try (var apply = new MetadataApplySession(s, worker, ui::add, () -> raw(edit), files)) {
          apply.start(edit, target); worker.run(); ui.remove().run();
          assertFalse(apply.accept()); assertSame(active, s.metadata);
          assertEquals("previous", Files.readString(target));
          assertEquals("Private", edit.draft().controls.get("noise").label);
          broken.set(false);
          apply.start(edit, target); worker.run(); ui.remove().run();
          assertTrue(apply.accept());
        }
      }
  }

  @Test
  void cancellationBeforeUiDeliveryCleansStagingAndOldCallbacksCannotAffectRetry() throws Exception {
    try (var s = state()) {
      var edit = new MetadataEditSession(s.metadata);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
      try (var apply = writer(s, edit, worker, ui)) {
        apply.start(edit, target); worker.run(); var old = ui.remove();
        apply.cancel();
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
        apply.start(edit, target); old.run(); old.run();
        assertTrue(apply.running()); assertFalse(apply.ready());
        worker.run(); ui.remove().run(); assertTrue(apply.accept());
      }
    }
  }

  @Test
  void preparedProfileOwnsValuesAndActivationMatchesItsExactWrittenBytes() throws Exception {
    try (var s = state()) {
      var edit = new MetadataEditSession(s.metadata);
      var retained = edit.draft(); retained.controls.get("noise").label = "Private";
      var target = directory.resolve("profile.json");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var apply = writer(s, edit, worker, ui)) {
        apply.start(edit, target); worker.run(); ui.remove().run(); assertTrue(apply.accept());
        retained.controls.get("noise").label = "Old mutable owner";
        edit.draft().controls.get("noise").label = "Next private";
        assertEquals("Private", s.metadata.controls.get("noise").label);
        assertEquals(ProjectIO.JSON.valueToTree(s.metadata), ProjectIO.JSON.readTree(target.toFile()));
        assertTrue(edit.changed());
      }
    }
  }

  @Test
  void inspectingAnotherCanonicalRawFieldDoesNotCancelPreparedApply() {
    try (var s = state()) {
      var edit = new MetadataEditSession(s.metadata);
      String id = "noise2D.algorithm.CONSTANT.value";
      edit.inputs().override(id, true); edit.inputs().editAll(id, List.of("0.750"));
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var apply = writer(s, edit, worker, ui)) {
        apply.start(edit, directory.resolve("profile.json")); worker.run();
        edit.inputs().text("noise2D.seedOffset");
        ui.remove().run();
        assertTrue(apply.ready());
        assertTrue(apply.accept());
        assertEquals(.75, s.metadata.creationDefaults.get(id).doubleValue());
      }
    }
  }

  @Test
  void workerFinishingAfterClosureCleansItsOwnUnpublishedSibling() throws Exception {
    try (var s = state(); var worker = Executors.newSingleThreadExecutor()) {
      var edit = new MetadataEditSession(s.metadata);
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var cleaned = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      var target = directory.resolve("profile.json"); Files.writeString(target, "previous");
      var files = new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          Files.write(temp, bytes); entered.countDown();
          boolean interrupted = false;
          try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
              try {
                if (!release.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS))
                  throw new IOException("Timed out waiting for closure");
                break;
              } catch (InterruptedException cancelled) { interrupted = true; }
            }
          } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        public void replace(Path temp, Path destination) { fail("Retired worker published"); }
        public void delete(Path temp) throws IOException { Files.delete(temp); cleaned.countDown(); }
      });
      try (var apply = new MetadataApplySession(s, worker, ui::add, () -> raw(edit), files)) {
        apply.start(edit, target);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        apply.close(); release.countDown();
        assertTrue(cleaned.await(10, TimeUnit.SECONDS));
        assertEquals("previous", Files.readString(target));
        assertTrue(ui.isEmpty());
      } finally { release.countDown(); }
    }
  }

}
