package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred active-save coalescing, failure suppression and exit-barrier definitions. */
class PreferenceSaveSessionTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;
    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("save queue full");
      tasks.add(task);
    }
    void run() { tasks.remove().run(); }
  }
  static void deliver(Workers worker, Queue<Runnable> ui) { worker.run(); ui.remove().run(); }
  Path file() { return directory.resolve("user-config.json"); }

  @Test
  void realWorkerStagesBytesAndOwnerFrameAlonePublishesWithoutReplacingLiveOwners() throws Exception {
    var writer = new AtomicReference<Thread>(); var owner = Thread.currentThread();
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { writer.set(Thread.currentThread()); Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        assertSame(owner, Thread.currentThread());
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
    }));
    var appearance = user.appearance; var input = user.input; var shortcuts = input.shortcuts;
    var workspace = user.workspace; var before = ProjectIO.JSON.valueToTree(user);
    Files.writeString(file(), "previous"); var ui = new LinkedBlockingQueue<Runnable>();
    var notifications = new ArrayList<String>();
    try (var worker = Executors.newSingleThreadExecutor();
        var saves = new PreferenceSaveSession(user, file(), worker, ui::add, notifications::add, text -> {})) {
      saves.request(true); var completion = ui.poll(10, TimeUnit.SECONDS); assertNotNull(completion);
      assertNotSame(owner, writer.get()); completion.run(); completion.run();
      assertEquals("previous", Files.readString(file())); assertTrue(saves.pending());
      saves.frame(); completion.run(); saves.frame();
      assertFalse(saves.pending()); assertEquals(List.of("Preferences saved"), notifications);
      assertSame(appearance, user.appearance); assertSame(input, user.input);
      assertSame(shortcuts, user.input.shortcuts); assertSame(workspace, user.workspace);
      assertEquals(before, ProjectIO.JSON.valueToTree(user));
      assertEquals(before, ProjectIO.JSON.readTree(file().toFile()));
    }
  }

  @Test
  void continuouslyChangedWorkspaceDoesNotRestartAQueuedWorkerEveryFrame() throws Exception {
    var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    Files.writeString(file(), "previous");
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(false); var first = worker.tasks.getFirst();
      for (int i = 0; i < 40; i++) {
        user.workspace.docking = "dock " + i; saves.request(false); saves.frame();
        assertEquals(1, worker.tasks.size()); assertSame(first, worker.tasks.getFirst());
        assertFalse(((Future<?>) first).isCancelled());
      }
      worker.run(); saves.frame();
      assertEquals("previous", Files.readString(file())); assertEquals(1, worker.tasks.size());
      // A predecessor delivery cannot publish into the coalesced successor.
      ui.remove().run(); deliver(worker, ui); saves.frame();
      assertFalse(saves.pending()); assertEquals("dock 39", UserConfig.read(file()).workspace.docking);
    }
  }

  @Test
  void completionReadyChangeNeverPublishesTheOlderFullSnapshot() throws Exception {
    var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    Files.writeString(file(), "previous");
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(true); deliver(worker, ui); assertTrue(saves.probeWork().ready());
      user.visualization.grid = false; user.workspace.width = 1234; saves.frame();
      assertEquals("previous", Files.readString(file())); assertTrue(saves.pending());
      deliver(worker, ui); saves.frame();
      assertFalse(UserConfig.read(file()).visualization.grid); assertEquals(1234, UserConfig.read(file()).workspace.width);
    }
  }

  @Test
  void privatePreferenceFormsAreExcludedAndTheirRawInputRemainsOwned() throws Exception {
    var user = new UserConfig(); var theme = new ThemeEditSession(user);
    theme.edit(ThemePalette.starting("Light"));
    var bindings = new ShortcutEditSession(user.input.shortcuts); bindings.edit("Profiler", "Alt+F9");
    var limits = new PerformanceEditSession(user); limits.edit(PerformancePreferences.Field.CACHE_MIB, "+256");
    var scales = new UiScaleEditSession(user); scales.editGlobal("1.2500");
    var trust = new UpdateTrustSession(user); trust.editEndpoint("https://updates.example.invalid/latest.json");
    trust.editOrigins("https://updates.example.invalid");
    var before = ProjectIO.JSON.valueToTree(user); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(true); deliver(worker, ui); saves.frame();
      assertEquals(before, ProjectIO.JSON.readTree(file().toFile()));
      assertEquals(ThemePalette.starting("Light"), theme.draft());
      assertEquals("Alt+F9", bindings.values().get("Profiler"));
      assertEquals("+256", limits.text().get(PerformancePreferences.Field.CACHE_MIB));
      assertEquals("1.2500", scales.text().global());
      assertEquals("https://updates.example.invalid/latest.json", trust.text().endpoint());
    }
  }

  @Test
  void newerPrivateApplyIsRetainedByCoalescedOrdinarySave() throws Exception {
    var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    var theme = new ThemeEditSession(user); theme.edit(ThemePalette.starting("Light"));
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(true); deliver(worker, ui);
      var request = theme.request(user, theme.draft());
      try (var prepared = request.prepare(file())) { request.publish(prepared, user); }
      byte[] accepted = Files.readAllBytes(file()); saves.frame();
      assertArrayEquals(accepted, Files.readAllBytes(file()));
      deliver(worker, ui); saves.frame();
      assertEquals("Light", UserConfig.read(file()).appearance.theme);
    }
  }

  @Test
  void ordinarySaveChangesNoLiveValuesAndDoesNotInvalidateAnOwnedPrivateApply() throws Exception {
    var user = new UserConfig(); var theme = new ThemeEditSession(user); theme.edit(ThemePalette.starting("Light"));
    var request = theme.request(user, theme.draft()); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var prepared = request.prepare(file());
        var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(false); deliver(worker, ui); saves.frame(); assertTrue(request.current(user));
      request.publish(prepared, user); assertEquals("Light", UserConfig.read(file()).appearance.theme);
    }
  }

  @Test
  void repeatedManualRequestsPromotePeriodicSaveToOneNotificationWithoutMoreWorkers() {
    var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    var notifications = new ArrayList<String>();
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, notifications::add, text -> {})) {
      saves.request(false);
      for (int i = 0; i < 20; i++) saves.request(true);
      assertEquals(1, worker.tasks.size()); deliver(worker, ui); saves.frame(); saves.frame();
      assertEquals(List.of("Preferences saved"), notifications); assertTrue(worker.tasks.isEmpty());
      saves.request(false); deliver(worker, ui); saves.frame(); assertEquals(1, notifications.size());
    }
  }

  @Test
  void queueHandoffAndExternalCancellationFailOnceUntilExplicitSaveRetry() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      var broken = new AtomicBoolean(mode == 1); worker.reject = mode == 0; var errors = new ArrayList<String>();
      Files.writeString(file(), "previous");
      try (var saves = new PreferenceSaveSession(user, file(), worker, action -> {
        if (broken.get()) throw new AssertionError("handoff"); ui.add(action);
      }, text -> {}, errors::add)) {
        saves.request(false);
        if (mode == 1) worker.run();
        if (mode == 2) { ((Future<?>) worker.tasks.remove()).cancel(false); }
        saves.frame(); assertFalse(saves.pending()); assertEquals(1, errors.size());
        for (int i = 0; i < 20; i++) { saves.request(false); saves.frame(); }
        assertTrue(worker.tasks.isEmpty()); assertEquals(1, errors.size());
        assertEquals("previous", Files.readString(file())); worker.reject = false; broken.set(false);
        saves.request(true); deliver(worker, ui); saves.frame(); assertFalse(saves.pending());
        assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file().toFile()));
        worker.reject = true; saves.request(false);
        worker.reject = false; saves.request(true); // Retry before frame() settles the failure.
        assertEquals(1, worker.tasks.size()); deliver(worker, ui); saves.frame();
        assertFalse(saves.pending()); assertEquals(2, errors.size());
      }
    }
  }

  @Test
  void writeAndAtomicReplaceFailurePreserveDestinationAndChangedValuesPermitNextPeriodicTick() throws Exception {
    for (boolean writing : new boolean[] {true, false}) {
      var broken = new AtomicBoolean(true);
      var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          Files.write(temp, bytes); if (writing && broken.get()) throw new IOException("write");
        }
        public void replace(Path temp, Path target) throws IOException {
          if (!writing && broken.get()) throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "provider");
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      }));
      var before = ProjectIO.JSON.valueToTree(user); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      Files.writeString(file(), "previous");
      try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
        saves.request(false); deliver(worker, ui); saves.frame(); assertFalse(saves.pending());
        assertEquals(before, ProjectIO.JSON.valueToTree(user)); assertEquals("previous", Files.readString(file()));
        broken.set(false); saves.request(false); assertTrue(worker.tasks.isEmpty());
        user.workspace.width = 1234; saves.request(false); deliver(worker, ui); saves.frame();
        assertEquals(1234, UserConfig.read(file()).workspace.width);
      }
    }
  }

  @Test
  void invalidWholeConfigurationStaysLiveAndDoesNotLoopBeforeCorrection() throws Exception {
    var user = new UserConfig(); user.workspace.width = 1;
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); Files.writeString(file(), "previous");
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(true); deliver(worker, ui); saves.frame();
      assertEquals(1, user.workspace.width); assertEquals("previous", Files.readString(file()));
      saves.request(false); assertTrue(worker.tasks.isEmpty());
      user.workspace.width = 1440; saves.request(false); deliver(worker, ui); saves.frame();
      assertEquals(1440, UserConfig.read(file()).workspace.width);
    }
  }

  @Test
  void staleCleanupAndThrowingReportCannotStrandTheLatestSave() throws Exception {
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      public void delete(Path temp) throws IOException { Files.delete(temp); throw new AssertionError("cleanup"); }
    }));
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> { throw new AssertionError("report"); })) {
      saves.request(false); deliver(worker, ui); user.visualization.grid = false;
      assertDoesNotThrow(saves::frame); deliver(worker, ui); saves.frame();
      assertFalse(saves.pending()); assertFalse(UserConfig.read(file()).visualization.grid);
    }
  }

  @Test
  void closureRejectsReadyAndQueuedDeliveryWithoutMutatingActiveValues() throws Exception {
    for (boolean ready : new boolean[] {true, false}) {
      var user = new UserConfig(); var before = ProjectIO.JSON.valueToTree(user);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); Files.writeString(file(), "previous");
      try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
        saves.request(true); worker.run(); var completion = ui.remove(); if (ready) completion.run();
        saves.close(); completion.run(); saves.frame(); saves.request(true);
        assertFalse(saves.pending()); assertTrue(worker.tasks.isEmpty());
        assertEquals("previous", Files.readString(file())); assertEquals(before, ProjectIO.JSON.valueToTree(user));
      }
    }
  }

  @Test
  void finalExitSaveRetiresLateWorkerAndPersistsLatestWorkspace() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
    var writes = new AtomicInteger(); var replacements = new AtomicInteger();
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException {
        Files.write(temp, bytes);
        if (writes.incrementAndGet() == 1) {
          entered.countDown(); boolean interrupted = false;
          try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) try {
              if (!release.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new IOException("fixture timeout");
              break;
            } catch (InterruptedException cancelled) { interrupted = true; }
          } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
      }
      public void replace(Path temp, Path target) throws IOException {
        replacements.incrementAndGet(); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      public void delete(Path temp) throws IOException { Files.delete(temp); cleaned.countDown(); }
    }));
    var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor();
        var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(false); assertTrue(entered.await(10, TimeUnit.SECONDS));
      user.workspace.docking = "final layout"; user.workspace.width = 1234; saves.finish();
      assertEquals("final layout", UserConfig.read(file()).workspace.docking);
      release.countDown(); assertTrue(cleaned.await(10, TimeUnit.SECONDS));
      while (!ui.isEmpty()) ui.remove().run(); saves.frame();
      assertEquals(1, replacements.get()); assertEquals(1234, UserConfig.read(file()).workspace.width);
      saves.finish(); assertEquals(1, replacements.get());
    } finally { release.countDown(); }
  }

  @Test
  void failedExitSavePreservesPriorFileAndRetiresEveryEarlierDelivery() throws Exception {
    var broken = new AtomicBoolean(false);
    var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        if (broken.get()) throw new IOException("exit replace");
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
    }));
    var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); Files.writeString(file(), "previous");
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {}, text -> {})) {
      saves.request(false); worker.run(); broken.set(true);
      assertThrows(IllegalStateException.class, saves::finish); ui.remove().run(); saves.frame();
      assertFalse(saves.pending()); assertEquals("previous", Files.readString(file()));
    }
  }

  @Test
  void successCallbackConsumesBeforeThrowingOrStartingItsSuccessor() throws Exception {
    var user = new UserConfig(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
    var notifications = new AtomicInteger(); var reports = new AtomicInteger();
    var holder = new AtomicReference<PreferenceSaveSession>();
    try (var saves = new PreferenceSaveSession(user, file(), worker, ui::add, text -> {
      notifications.incrementAndGet(); user.visualization.grid = false; holder.get().request(false);
      throw new AssertionError("status refresh");
    }, text -> reports.incrementAndGet())) {
      holder.set(saves); saves.request(true); deliver(worker, ui); saves.frame();
      assertTrue(saves.pending()); assertEquals(1, notifications.get()); assertEquals(1, reports.get());
      deliver(worker, ui); saves.frame(); saves.frame();
      assertFalse(saves.pending()); assertEquals(1, notifications.get());
      assertFalse(UserConfig.read(file()).visualization.grid);
    }
  }

  @Test
  void activeSaveCanonicalizesOnlyItsDetachedMapAndConsumesEvenEqualValues() throws Exception {
    var user = new UserConfig(); user.input.shortcuts.put("Profiler", "alt+f9");
    var map = user.input.shortcuts; var request = user.requestSave();
    try (var first = request.prepare(file()); var duplicate = request.prepare(file())) {
      request.publish(first, user); byte[] accepted = Files.readAllBytes(file());
      assertSame(map, user.input.shortcuts); assertEquals("alt+f9", map.get("Profiler"));
      assertEquals("Alt+F9", UserConfig.read(file()).input.shortcuts.get("Profiler"));
      assertFalse(request.current(user));
      assertThrows(IllegalStateException.class, () -> request.publish(duplicate, user));
      assertArrayEquals(accepted, Files.readAllBytes(file()));
    }
  }
}
