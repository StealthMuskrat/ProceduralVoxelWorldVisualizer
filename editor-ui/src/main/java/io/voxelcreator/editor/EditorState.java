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
  public final UserConfig user = UserConfig.load();
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
  public Object draft;
  public List<Diagnostic> issues = List.of();
  public final Set<UUID> multiSelection = new LinkedHashSet<>();
  private String selectionParent = "";
  public final List<Notice> notices = new ArrayList<>();
  public final Deque<String> logs = new ArrayDeque<>();

  public record Notice(String severity, String message, Instant time, int count, boolean read) {}

  public EditorState() {
    document.setValidator(NoiseCompiler::validate);
    engine = new NoiseGeneratorEngine(user.performance.workers, 128, 512L << 20);
    engine.addDiagnosticListener(d -> uiQueue.offer(() -> notify("Error", d.toString())));
  }

  public ProjectData project() {
    return document.data();
  }

  public boolean change(Consumer<ProjectData> edit) {
    try {
      var result = document.edit(edit);
      issues = result;
      if (result.isEmpty()) {
        revision++;
        status = "Project modified";
        return true;
      }
      notify("Error", result.getFirst().toString());
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
    status = message;
    String log = Instant.now() + " " + severity + " " + message;
    logs.addFirst(log);
    while (logs.size() > 1000) logs.removeLast();
    for (int i = 0; i < notices.size(); i++) {
      var n = notices.get(i);
      if (n.message.equals(message) && n.severity.equals(severity)) {
        notices.set(i, new Notice(severity, message, Instant.now(), n.count + 1, false));
        return;
      }
    }
    notices.addFirst(new Notice(severity, message, Instant.now(), 1, false));
    while (notices.size() > 1000) notices.removeLast();
  }

  public void newSession() {
    document.newProject();
    selected = null;
    draft = null;
    selectedKind = "";
    multiSelection.clear();
    revision++;
    issues = List.of();
  }

  public void drain() {
    for (Runnable r; (r = uiQueue.poll()) != null; ) r.run();
  }

  @Override
  public void close() {
    background.shutdownNow();
    engine.close();
    user.save();
  }
}
