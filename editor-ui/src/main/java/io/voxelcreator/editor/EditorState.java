package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class EditorState implements AutoCloseable {
  public final DocumentSession document = new DocumentSession();
  public final UserConfig user;
  private final boolean persistPreferences;
  public AppMetadata metadata = AppMetadata.load();
  public final NoiseGeneratorEngine engine;
  public final ThreadPoolExecutor background =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          Thread.ofPlatform().daemon(true).name("editor-preview-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());
  public final ConcurrentLinkedQueue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
  public long revision = 0;
  public UUID selected;
  public String selectedKind = "", status = "Ready";
  private final java.util.concurrent.atomic.AtomicReference<String> eventStatus =
      new java.util.concurrent.atomic.AtomicReference<>();
  public String validationScope = "Committed project";
  public Object draft;
  public List<Diagnostic> issues = List.of();
  public final Set<UUID> multiSelection = new LinkedHashSet<>();
  private String selectionParent = "";
  public final EditorEvents events = new EditorEvents();
  public final TraceHistory traces = new TraceHistory();

  public long publishTrace(TraceSnapshot trace) {
    var entry =
        events.publish(
            "GENERATION_TRACE",
            Diagnostic.Severity.INFORMATION,
            EditorEvents.Subsystem.GENERATION,
            trace.summary(),
            new EditorEvents.Target(trace.project(), null, null, null, "", false),
            false);
    if (!traces.put(entry.id(), trace))
      status = "Trace is too large for retained log details; Inspector still shows the result.";
    return entry.id();
  }

  public EditorState() {
    this(UserConfig.load(), true);
  }

  EditorState(UserConfig user, boolean persistPreferences) {
    this.user = Objects.requireNonNull(user);
    this.persistPreferences = persistPreferences;
    document.setValidator(NoiseCompiler::validate);
    engine =
        new NoiseGeneratorEngine(
            user.performance.workers, 128, (long) user.performance.engineMiB << 20);
    engine.addDiagnosticListener(
        d ->
            post(
                d.code(),
                d.severity(),
                EditorEvents.Subsystem.ENGINE,
                d.message(),
                EditorEvents.Target.diagnostic(null, d),
                d.severity() == Diagnostic.Severity.CRITICAL));
  }

  public ProjectData project() {
    return document.data();
  }

  public boolean change(Consumer<ProjectData> edit) {
    try {
      var result = document.edit(edit);
      issues = result;
      validationScope = result.isEmpty() ? "Committed project" : "Rejected edit";
      if (result.isEmpty()) {
        revision++;
        status = "Project modified";
        return true;
      }
      for (var diagnostic : result)
        reportDiagnostic(diagnostic, EditorEvents.Subsystem.PROJECT, true);
      return false;
    } catch (RuntimeException e) {
      notify("Error", String.valueOf(e.getMessage()));
      return false;
    }
  }

  public void select(String kind, UUID uuid, String parent, boolean toggle) {
    if (!toggle || !parent.equals(selectionParent)) multiSelection.clear();
    if (toggle && multiSelection.contains(uuid)) multiSelection.remove(uuid);
    else multiSelection.add(uuid);
    selectionParent = parent;
    selected =
        multiSelection.contains(uuid)
            ? uuid
            : multiSelection.stream().reduce((a, b) -> b).orElse(null);
    var entry = ResourceGraph.find(project(), selected);
    selectedKind = entry == null ? "" : entry.kind();
    refreshDraft();
  }

  public void select(String kind, UUID uuid) {
    select(kind, uuid, kind, false);
  }

  public void refreshDraft() {
    if (validationScope.equals("Rejected edit")) {
      issues = List.of();
      validationScope = "Committed project";
    }
    Object obj = find(project(), selected);
    draft =
        obj == null
            ? null
            : ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(obj), obj.getClass());
  }

  public boolean applyDraft() {
    Object copy = draft;
    if (copy instanceof Identity identity) {
      boolean ok = change(p -> replace(p, identity.uuid, copy));
      if (ok) refreshDraft();
      return ok;
    }
    return false;
  }

  private static void replace(ProjectData p, UUID id, Object copy) {
    for (var list : lists(p))
      for (int i = 0; i < list.size(); i++)
        if (list.get(i) instanceof Identity r && r.uuid.equals(id)) {
          @SuppressWarnings("unchecked")
          var writable = (List<Object>) (List<?>) list;
          writable.set(
              i, ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(copy), copy.getClass()));
          return;
        }
  }

  public static List<List<?>> lists(ProjectData p) {
    var lists = new ArrayList<List<?>>();
    lists.addAll(
        List.of(
            p.noise2D,
            p.noise3D,
            p.folders,
            p.voxelTypes,
            p.voxelGroups,
            p.voxelSets,
            p.worldGenerator.layers));
    for (var l : p.worldGenerator.layers) lists.add(l.operations);
    return lists;
  }

  public static Object find(ProjectData p, UUID id) {
    if (id == null) return null;
    for (var list : lists(p))
      for (var o : list) if (o instanceof Identity r && r.uuid.equals(id)) return o;
    return null;
  }

  public static Layer layerFor(ProjectData p, UUID op) {
    return p.worldGenerator.layers.stream()
        .filter(l -> l.operations.stream().anyMatch(o -> o.uuid.equals(op)))
        .findFirst()
        .orElse(null);
  }

  public void notify(String severity, String message) {
    Diagnostic.Severity level;
    try {
      level = Diagnostic.Severity.valueOf(severity.toUpperCase(Locale.ROOT));
    } catch (RuntimeException unknown) {
      level = Diagnostic.Severity.INFORMATION;
    }
    post(
        "APPLICATION_MESSAGE",
        level,
        EditorEvents.Subsystem.APPLICATION,
        message,
        null,
        level == Diagnostic.Severity.CRITICAL);
  }

  public EditorEvents.Entry post(
      String code,
      Diagnostic.Severity severity,
      EditorEvents.Subsystem subsystem,
      String message,
      EditorEvents.Target target,
      boolean actionRequired) {
    var entry = events.publish(code, severity, subsystem, message, target, actionRequired);
    eventStatus.set(entry.message());
    return entry;
  }

  public void error(String code, EditorEvents.Subsystem subsystem, String message) {
    post(code, Diagnostic.Severity.ERROR, subsystem, message, null, true);
  }

  public void reportDiagnostic(
      Diagnostic diagnostic, EditorEvents.Subsystem subsystem, boolean actionRequired) {
    post(
        diagnostic.code(),
        diagnostic.severity(),
        subsystem,
        diagnostic.message(),
        EditorEvents.Target.diagnostic(project().project.uuid, diagnostic),
        actionRequired);
  }

  public List<Diagnostic> validateProject() {
    issues = NoiseCompiler.validate(project());
    validationScope = "Committed project";
    if (issues.isEmpty())
      post(
          "PROJECT_VALID",
          Diagnostic.Severity.INFORMATION,
          EditorEvents.Subsystem.PROJECT,
          "Project is valid",
          null,
          false);
    else for (var issue : issues) reportDiagnostic(issue, EditorEvents.Subsystem.PROJECT, true);
    return issues;
  }

  public boolean canNavigate(EditorEvents.Target target) {
    return DiagnosticNavigation.available(project(), selected, draft, target);
  }

  public boolean navigate(EditorEvents.Target target) {
    if (!canNavigate(target) || target.settings()) return false;
    if (!Objects.equals(selected, target.resource()) || draft == null) {
      var entry = ResourceGraph.find(project(), target.resource());
      select(entry.kind(), entry.uuid());
    }
    multiSelection.clear();
    multiSelection.add(target.resource());
    return true;
  }

  public void newSession() {
    traces.clear();
    document.newProject();
    selected = null;
    draft = null;
    selectedKind = "";
    multiSelection.clear();
    revision++;
    issues = List.of();
    validationScope = "Committed project";
  }

  public void drain() {
    for (Runnable r; (r = uiQueue.poll()) != null; ) r.run();
    String latest = eventStatus.getAndSet(null);
    if (latest != null) status = latest;
  }

  @Override
  public void close() {
    traces.clear();
    background.shutdownNow();
    engine.close();
    if (persistPreferences) user.save();
  }
}
