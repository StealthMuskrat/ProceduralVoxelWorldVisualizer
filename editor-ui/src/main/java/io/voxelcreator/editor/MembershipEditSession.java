package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Membership;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Temporary membership selection; Apply changes only members in one document transaction. */
public final class MembershipEditSession implements AutoCloseable {
  private final EditorState editor;
  private final Membership parent;
  private final UUID project;
  private final Object projectSession;
  private final String kind;
  private final List<UUID> selection;
  private final UUID owner;
  private final JsonNode activeBase, draftBase;
  private final List<UUID> values;
  private final Set<UUID> selected;
  private long revision;
  private boolean closed;
  private List<Diagnostic> applyIssues = List.of();
  private int issueCount;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private Object workToken = new Object();
  private FutureTask<Void> task;
  private boolean admitting;
  private long workVersion;
  private ProjectFileOwner workOwner;
  private List<Diagnostic> sharedIssues, sharedValues;
  private String sharedScope;
  private String progress = "", applyFailure = "";

  public MembershipEditSession(EditorState state, Membership draft) {
    this(state, draft, null, null);
  }

  MembershipEditSession(
      EditorState state, Membership draft, Executor executor, Consumer<Runnable> ui) {
    if (state.draft != draft || !Objects.equals(state.selected, draft.uuid))
      throw new IllegalArgumentException("Select the membership's current Properties draft");
    var active = ResourceGraph.find(state.project(), draft.uuid);
    if (active == null || !(active.resource() instanceof Membership))
      throw new IllegalArgumentException("Membership resource no longer exists");
    editor = state;
    this.executor = executor;
    this.ui = ui;
    if (executor != null) Objects.requireNonNull(ui);
    parent = draft;
    project = state.project().project.uuid;
    projectSession = state.document.sessionToken();
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
    owner = draft.uuid;
    activeBase = ProjectIO.JSON.valueToTree(active.resource());
    draftBase = ProjectIO.JSON.valueToTree(draft);
    values = new ArrayList<>(draft.voxelTypeUuids);
    selected = new HashSet<>(values);
  }

  public UUID owner() {
    return owner;
  }

  public long revision() {
    return revision;
  }

  public int count() {
    return selected.size();
  }

  public boolean contains(UUID id) {
    return selected.contains(id);
  }

  public List<UUID> values() {
    return List.copyOf(values);
  }

  public void set(UUID id, boolean include) {
    requireOpen();
    Objects.requireNonNull(id);
    if (include) {
      if (selected.add(id)) {
        values.add(id);
        revision++;
        changed();
      }
    } else if (selected.remove(id)) {
      values.removeIf(id::equals);
      revision++;
      changed();
    }
  }

  public void setVisible(List<ResourceTreeIndex.Node> visible, boolean include) {
    requireOpen();
    for (var node : visible) {
      if (!node.kind().equals("voxel"))
        throw new IllegalArgumentException("Membership accepts voxel types only");
    }
    for (var node : visible) set(node.uuid(), include);
  }

  public boolean apply(EditorState state) {
    requireOpen();
    if (!currentOwner(state))
      throw new IllegalStateException("Membership owner changed; Cancel and reopen the editor");
    if (task != null || admitting) return false;
    var active = ResourceGraph.find(state.project(), owner);
    if (active == null
        || !activeBase.equals(ProjectIO.JSON.valueToTree(active.resource()))
        || !(state.draft instanceof Membership draft)
        || !draft.uuid.equals(owner)
        || !draftBase.equals(ProjectIO.JSON.valueToTree(draft)))
      throw new IllegalStateException("Membership changed elsewhere; Cancel and reopen the editor");
    var valid = new HashSet<UUID>();
    state.project().voxelTypes.forEach(t -> valid.add(t.uuid));
    if (!valid.containsAll(values))
      throw new IllegalArgumentException("A selected voxel type no longer exists");
    var candidate = values();
    if (executor != null) {
      prepare(candidate);
      return false;
    }
    var result =
        state.changeResult(
            p -> {
              var target = ResourceGraph.find(p, owner);
              if (target == null || !(target.resource() instanceof Membership m))
                throw new IllegalArgumentException("Membership resource no longer exists");
              m.voxelTypeUuids = new ArrayList<>(candidate);
            });
    if (result.applied()) {
      draft.voxelTypeUuids = new ArrayList<>(candidate);
      closed = true;
      applyIssues = List.of();
      issueCount = 0;
    } else {
      issueCount = result.issueCount();
      applyIssues = result.issues();
    }
    return result.applied();
  }

  /** Cheap draw-time ownership check; Apply also compares complete opening resource values. */
  boolean currentOwner(EditorState state) {
    return !closed
        && state == editor
        && state.document.sessionToken() == projectSession
        && Objects.equals(project, state.project().project.uuid)
        && state.draft == parent
        && Objects.equals(owner, state.selected)
        && Objects.equals(kind, state.selectedKind)
        && selection.equals(List.copyOf(state.multiSelection));
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  int issueCount() {
    return issueCount;
  }

  public boolean closed() {
    refresh();
    return closed;
  }

  public void cancel() {
    retireWork();
    closed = true;
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
    progress = "";
  }

  boolean applying() {
    refresh();
    return task != null || admitting;
  }

  long workVersion() {
    return workVersion;
  }

  String progress() {
    refresh();
    return progress;
  }

  String applyFailure() {
    return applyFailure;
  }

  private void changed() {
    if (task != null || admitting) {
      retireWork();
      progress =
          "Pending Apply cancelled because member choices changed. Review the selection and Apply"
              + " Members again.";
    }
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
  }

  private void prepare(List<UUID> candidate) {
    retireWork();
    Object token = workToken;
    admitting = true;
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
    try {
      var request =
          editor.document.requestEdit(
              p -> {
                var target = ResourceGraph.find(p, owner);
                if (target == null || !(target.resource() instanceof Membership m))
                  throw new IllegalArgumentException("Membership resource no longer exists");
                m.voxelTypeUuids = new ArrayList<>(candidate);
              });
      workOwner = new ProjectFileOwner(editor);
      sharedIssues = editor.issues;
      sharedValues = List.copyOf(sharedIssues);
      sharedScope = editor.validationScope;
      progress =
          "Preparing and validating member changes in the background. Committed members and private"
              + " Properties input remain active. Cancel or Escape closes this selection without"
              + " applying.";
      task =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedEdit prepared = null;
                Throwable failure = null;
                try {
                  prepared = request.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var value = prepared;
                var rejected = failure;
                ui.accept(() -> publish(token, candidate, value, rejected));
                return null;
              });
      executor.execute(task);
    } catch (Throwable failure) {
      fail(token, failure);
    } finally {
      if (token == workToken) admitting = false;
    }
  }

  private boolean owns(Object token) {
    return token == workToken && task != null && !closed;
  }

  private boolean feedbackCurrent() {
    return sharedIssues == editor.issues
        && sharedValues.equals(editor.issues)
        && Objects.equals(sharedScope, editor.validationScope);
  }

  private void publish(
      Object token,
      List<UUID> candidate,
      DocumentSession.PreparedEdit prepared,
      Throwable failure) {
    if (!owns(token)) return;
    boolean applied = false;
    try {
      if (!currentOwner(editor)
          || !candidate.equals(values)
          || !workOwner.valuesCurrent(editor)
          || !feedbackCurrent()) {
        stale();
        return;
      }
      if (failure != null) {
        fail(token, failure);
        return;
      }
      if (!editor.document.currentEdit(prepared)) {
        stale();
        return;
      }
      if (!prepared.success()) {
        var diagnostics = prepared.diagnostics();
        var result = ProjectChangeResult.rejected(diagnostics);
        retireWork();
        applyIssues = result.issues();
        issueCount = result.issueCount();
        progress =
            "Member changes were rejected. The selection and Properties input are retained; retry"
                + " explicitly, or Cancel.";
        editor.rejectDraftApply(diagnostics);
        return;
      }
      editor.document.applyEdit(prepared);
      applied = true;
      retireWork();
      closed = true;
      editor.revision++;
      editor.issues = List.of();
      editor.validationScope = "Committed project";
      editor.status = "Membership applied";
      var accepted = ResourceGraph.find(editor.project(), owner);
      if (accepted == null || !(accepted.resource() instanceof Membership m))
        throw new IllegalStateException("Applied membership no longer exists for private refresh");
      // Update only members: an unrelated private ID/seed ledger must remain private and intact.
      parent.voxelTypeUuids = new ArrayList<>(m.voxelTypeUuids);
      progress = "Membership applied. Other private Properties edits and saving remain separate.";
    } catch (Throwable rejected) {
      if (applied) {
        progress =
            "Membership applied; private member refresh failed: "
                + BackgroundFailure.describe(rejected);
        report("MEMBERSHIP_APPLY_REFRESH_FAILED", progress);
      } else fail(token, rejected);
    }
  }

  private void refresh() {
    if (task == null || closed) return;
    try {
      if (!currentOwner(editor) || !workOwner.current(editor) || !feedbackCurrent()) {
        stale();
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(workToken, done.failure());
    } catch (Throwable failure) {
      fail(workToken, failure);
    }
  }

  private void stale() {
    retireWork();
    progress =
        "Membership Apply became stale. The selection is retained; review it and Apply Members"
            + " again, or Cancel and reopen if the owner changed.";
  }

  private void fail(Object token, Throwable failure) {
    if (token != workToken || closed) return;
    retireWork();
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = BackgroundFailure.describe(failure);
    progress =
        "Membership Apply failed. The selection is retained; Apply Members again to retry, or"
            + " Cancel.";
    report("MEMBERSHIP_APPLY_FAILED", applyFailure);
  }

  private void report(String code, String message) {
    try {
      editor.error(code, EditorEvents.Subsystem.PROJECT, message);
    } catch (Throwable ignored) {
      /* Preserve correction and cancellation. */
    }
  }

  private void retireWork() {
    var previous = task;
    workToken = new Object();
    workVersion++;
    task = null;
    admitting = false;
    workOwner = null;
    sharedIssues = sharedValues = null;
    sharedScope = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  @Override
  public void close() {
    cancel();
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Membership editor is closed");
  }
}
