package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred member-only prepared transactions and temporary selection ownership. */
class BackgroundMembershipTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.remove().run();
    }
  }

  static EditorState state(String kind) {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(BackgroundMembershipProbe::fixture));
    var m =
        kind.equals("set") ? s.project().voxelSets.getFirst() : s.project().voxelGroups.getFirst();
    s.select(kind, m.uuid);
    ((Membership) s.draft).id = " private-id ";
    s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    return s;
  }

  static UUID type(EditorState s, int index) {
    return s.project().voxelTypes.get(index).uuid;
  }

  static MembershipEditSession session(EditorState s, Executor worker, Queue<Runnable> ui) {
    var session = new MembershipEditSession(s, (Membership) s.draft, worker, ui::add);
    session.set(type(s, 0), true);
    return session;
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  @Test
  void actualWorkerWaitsForOwnerDeliveryAndOnlyMembersReachOneUndoRedoTransaction()
      throws Exception {
    try (var s = state("set");
        var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var thread = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      s.document.setValidator(
          p -> {
            thread.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var edit = session(s, worker, ui);
      var parent = s.draft;
      var before = ProjectIO.JSON.valueToTree(s.project());
      long seed = s.seedInputs.version(s);
      assertFalse(edit.apply(s));
      var callback = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(caller, thread.get());
      assertTrue(edit.applying());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(List.of(type(s, 1)), ((Membership) parent).voxelTypeUuids);
      callback.run();
      callback.run();
      assertTrue(edit.closed());
      assertSame(parent, s.draft);
      assertEquals(" private-id ", ((Membership) parent).id);
      assertEquals("members", s.project().voxelSets.getFirst().id);
      assertEquals(List.of(type(s, 1), type(s, 0)), ((Membership) parent).voxelTypeUuids);
      assertEquals("-", s.seedInputs.text("seedOffset"));
      assertEquals(seed, s.seedInputs.version(s));
      var after = ProjectIO.JSON.valueToTree(s.project());
      ((Membership) parent).voxelTypeUuids.clear();
      assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void groupAndEmptySelectionsRemainOrderedAndDoNotPublishUnrelatedPrivateFields() {
    for (String kind : List.of("set", "group"))
      for (boolean empty : new boolean[] {false, true})
        try (var s = state(kind)) {
          var worker = new Workers();
          var ui = new ArrayDeque<Runnable>();
          var edit = session(s, worker, ui);
          if (empty) {
            edit.set(type(s, 0), false);
            edit.set(type(s, 1), false);
          } else edit.setVisible(new ResourceTreeIndex(s.project()).kind("voxel", "third"), true);
          var expected = empty ? List.<UUID>of() : List.of(type(s, 1), type(s, 0), type(s, 2));
          assertFalse(edit.apply(s));
          deliver(worker, ui);
          assertTrue(edit.closed());
          assertEquals(expected, BackgroundMembershipProbe.resourceMembers(s, kind));
          assertEquals(expected, ((Membership) s.draft).voxelTypeUuids);
          assertEquals(" private-id ", ((Membership) s.draft).id);
          assertEquals("-", s.seedInputs.text("seedOffset"));
        }
  }

  @Test
  void changedChoicesRetireOldWorkEvenIfTheSameChoicesAreLaterRestored() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      edit.apply(s);
      worker.run();
      long version = edit.workVersion();
      edit.set(type(s, 0), false);
      edit.set(type(s, 0), true);
      assertTrue(edit.workVersion() > version);
      ui.remove().run();
      assertFalse(edit.closed());
      assertFalse(edit.applying());
      assertEquals(List.of(type(s, 1)), s.project().voxelSets.getFirst().voxelTypeUuids);
      edit.apply(s);
      deliver(worker, ui);
      assertTrue(edit.closed());
    }
  }

  @Test
  void unchangedCheckboxNotificationsAndDuplicateApplyDoNotScheduleMoreWork() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      edit.apply(s);
      long version = edit.workVersion(), choices = edit.revision();
      edit.set(type(s, 0), true);
      assertFalse(edit.apply(s));
      assertEquals(1, worker.tasks.size());
      assertEquals(version, edit.workVersion());
      assertEquals(choices, edit.revision());
      deliver(worker, ui);
      assertTrue(edit.closed());
    }
  }

  @Test
  void boundedImmutableRejectionAndLaterFatalFailureRetainChoicesForExplicitRetry() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      var issues = new ArrayList<Diagnostic>();
      for (int i = 0; i < 300; i++)
        issues.add(Diagnostic.error("REJECTED", "/fixture/" + i, "Rejected"));
      s.document.setValidator(p -> issues);
      for (int i = 0; i < 2; i++) {
        edit.apply(s);
        deliver(worker, ui);
        assertEquals(300, edit.issueCount());
        assertEquals(256, edit.applyIssues().size());
      }
      issues.clear();
      assertEquals(256, edit.applyIssues().size());
      assertEquals(300, s.issues.size());
      s.document.setValidator(
          p -> {
            throw new AssertionError("worker fixture");
          });
      edit.apply(s);
      deliver(worker, ui);
      assertEquals(0, edit.issueCount());
      assertTrue(edit.applyIssues().isEmpty());
      assertFalse(edit.applyFailure().isBlank());
      assertEquals(2, edit.count());
      s.document.setValidator(NoiseCompiler::validate);
      edit.apply(s);
      deliver(worker, ui);
      assertTrue(edit.closed());
    }
  }

  @Test
  void schedulerHandoffAndExternallyCancelledTaskFailureLeaveApplyAndCancelUsable() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state("set")) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        int action = mode;
        var edit =
            new MembershipEditSession(
                s,
                (Membership) s.draft,
                task -> {
                  if (action == 0 && fail.get())
                    throw new RejectedExecutionException("scheduler fixture");
                  worker.execute(task);
                },
                task -> {
                  if (action == 1 && fail.get()) throw new AssertionError("handoff fixture");
                  ui.add(task);
                });
        edit.set(type(s, 0), true);
        edit.apply(s);
        if (mode == 1) worker.run();
        else if (mode == 2) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertFalse(edit.applying());
        assertFalse(edit.closed());
        assertFalse(edit.applyFailure().isBlank());
        assertEquals(2, edit.count());
        if (mode == 2) worker.run();
        fail.set(false);
        edit.apply(s);
        deliver(worker, ui);
        assertTrue(edit.closed());
      }
  }

  @Test
  void newParentRawSelectionOrCommittedValuesCannotReceiveTheOldMemberList() {
    for (int mode = 0; mode < 6; mode++)
      try (var s = state("set")) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui);
        edit.apply(s);
        worker.run();
        if (mode == 0) ((Membership) s.draft).id = "new-private";
        else if (mode == 1) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else if (mode == 2) s.multiSelection.add(type(s, 2));
        else if (mode == 3) s.refreshDraft();
        else if (mode == 4) s.project().voxelTypes.getLast().color.r = 17;
        else assertTrue(s.change(p -> p.voxelSets.getFirst().voxelTypeUuids = new ArrayList<>()));
        var project = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        ui.remove().run();
        assertFalse(edit.closed());
        assertFalse(edit.applying());
        assertSame(draft, s.draft);
        assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft));
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void unrelatedCatalogChangesAreAllowedBeforeApplyAndCanBeExplicitlyRetriedAfterStaleWork() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      assertTrue(s.change(p -> p.voxelTypes.getLast().color.r = 17));
      assertTrue(edit.currentOwner(s));
      edit.apply(s);
      worker.run();
      assertTrue(s.change(p -> p.voxelTypes.getLast().color.r = 19));
      ui.remove().run();
      assertFalse(edit.applying());
      assertFalse(edit.closed());
      assertEquals(2, edit.count());
      assertTrue(edit.currentOwner(s));
      edit.apply(s);
      deliver(worker, ui);
      assertTrue(edit.closed());
      assertEquals(19, s.project().voxelTypes.getLast().color.r);
    }
  }

  @Test
  void savePolicyHistoryNewAndSameUuidOpenSupersedePreparedPublication() throws Exception {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state("set")) {
        var path = directory.resolve("source-" + mode + ".json");
        s.document.save(path);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui);
        edit.apply(s);
        worker.run();
        if (mode == 0) s.document.save(path);
        else if (mode == 1) s.document.setValidator(p -> List.of());
        else if (mode == 2) {
          s.document.undo();
          s.document.redo();
        } else if (mode == 3) s.newSession();
        else assertTrue(s.document.open(path).isEmpty());
        var project = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        ui.remove().run();
        assertFalse(edit.closed());
        assertFalse(edit.applying());
        assertSame(draft, s.draft);
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void cancelCloseAndNewDialogIgnoreAnAlreadyCompletedOldCallback() {
    for (boolean close : new boolean[] {false, true})
      try (var s = state("set")) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui);
        edit.apply(s);
        worker.run();
        if (close) edit.close();
        else edit.cancel();
        var successor = session(s, worker, ui);
        successor.set(type(s, 2), true);
        ui.remove().run();
        assertTrue(edit.closed());
        assertFalse(successor.closed());
        assertEquals(3, successor.count());
        assertEquals(List.of(type(s, 1)), ((Membership) s.draft).voxelTypeUuids);
        successor.cancel();
      }
  }

  @Test
  void newerSharedDiagnosticsCannotBeOverwrittenByTheOldMembershipAttempt() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      edit.apply(s);
      worker.run();
      var newer = List.of(Diagnostic.error("NEWER", "/fixture", "New result"));
      s.issues = newer;
      ui.remove().run();
      assertSame(newer, s.issues);
      assertFalse(edit.applying());
      assertFalse(edit.closed());
      assertEquals(List.of(type(s, 1)), ((Membership) s.draft).voxelTypeUuids);
    }
  }

  @Test
  void noOpKeepsRedoAndPublishedValidatorValuesAreCopiedOnlyIntoPrivateMembers() {
    for (boolean mutate : new boolean[] {false, true})
      try (var s = state("set")) {
        assertTrue(s.change(p -> p.project.seed = 6));
        s.document.undo();
        long bytes = s.document.historyBytes();
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = new MembershipEditSession(s, (Membership) s.draft, worker, ui::add);
        UUID third = type(s, 2);
        if (mutate)
          s.document.setValidator(
              p -> {
                p.voxelSets.getFirst().voxelTypeUuids = new ArrayList<>(List.of(third));
                return List.of();
              });
        edit.apply(s);
        deliver(worker, ui);
        assertTrue(edit.closed());
        assertEquals(
            BackgroundMembershipProbe.resourceMembers(s, "set"),
            ((Membership) s.draft).voxelTypeUuids);
        assertEquals(" private-id ", ((Membership) s.draft).id);
        assertEquals("-", s.seedInputs.text("seedOffset"));
        if (!mutate) {
          assertTrue(s.document.canRedo());
          assertEquals(bytes, s.document.historyBytes());
        }
      }
  }

  @Test
  void oldCompiledMembershipWorldRemainsImmutableAcrossApplyUndoAndRedo() {
    try (var s = state("set");
        var old = s.engine.openWorld(new CompiledProject(s.project()))) {
      var pos = new BlockPos(0, 0, 0);
      assertEquals(1, old.getVoxelTypeId(pos));
      var before = ProjectIO.JSON.valueToTree(s.project());
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      edit.apply(s);
      deliver(worker, ui);
      try (var accepted = s.engine.openWorld(new CompiledProject(s.project()))) {
        assertEquals(0, accepted.getVoxelTypeId(pos));
        assertEquals(1, old.getVoxelTypeId(pos));
        s.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(0, accepted.getVoxelTypeId(pos));
        s.document.redo();
        assertEquals(1, old.getVoxelTypeId(pos));
      }
    }
  }

  @Test
  void acceptedRefreshFailureAndReentrantSchedulingCannotReplayOrPublishACancelledAttempt() {
    try (var s = state("set")) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui);
      s.document.setValidator(
          p -> {
            p.voxelSets.clear();
            return List.of();
          });
      long revision = s.revision;
      edit.apply(s);
      deliver(worker, ui);
      assertTrue(edit.closed());
      assertEquals(revision + 1, s.revision);
      assertTrue(s.project().voxelSets.isEmpty());
      assertThrows(IllegalStateException.class, () -> edit.apply(s));
      assertEquals("MEMBERSHIP_APPLY_REFRESH_FAILED", s.events.view().entries().getLast().code());
    }
    try (var s = state("set")) {
      var ui = new ArrayDeque<Runnable>();
      var holder = new MembershipEditSession[1];
      var edit =
          new MembershipEditSession(
              s,
              (Membership) s.draft,
              task -> {
                assertFalse(holder[0].apply(s));
                holder[0].cancel();
                task.run();
              },
              ui::add);
      holder[0] = edit;
      edit.set(type(s, 0), true);
      assertFalse(edit.apply(s));
      assertTrue(edit.closed());
      assertTrue(ui.isEmpty());
      assertEquals(List.of(type(s, 1)), ((Membership) s.draft).voxelTypeUuids);
    }
  }
}
