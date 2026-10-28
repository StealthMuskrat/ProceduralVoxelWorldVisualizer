package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production COPY/REUSE/SKIP workspace, with bounded rows and explicit stale-preview reload. */
final class ResourceImportPanel {
  private final EditorState state;
  private final BooleanSupplier blocked;
  private final Runnable validation;
  private ImportEditSession session;
  private boolean prompt;
  private final ImString filter = new ImString(256);
  private final UiFields fields = new UiFields();
  private String kind = "All", resolution = "All", queryKey = "", message = "";
  private ResourceImport.Plan filteredPlan;
  private List<ResourceImport.Row> rows = List.of();
  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<UUID, float[]> rowControls = new HashMap<>();
  private int submitted;
  private int copies, reuses, skips;
  private Float scroll;
  private long queries;

  ResourceImportPanel(EditorState state, BooleanSupplier blocked, Runnable validation) {
    this.state = state;
    this.blocked = Objects.requireNonNull(blocked);
    this.validation = Objects.requireNonNull(validation);
  }

  boolean pending() {
    return session != null && !session.closed();
  }

  boolean open(ProjectData incoming) {
    if (pending() || blocked.getAsBoolean()) {
      state.status = "Finish the private editor or pending project action before opening Import";
      return false;
    }
    if (incoming == null) {
      state.status = "A resource package must contain resources";
      return false;
    }
    var issues = NoiseCompiler.validate(incoming);
    if (!issues.isEmpty()) {
      state.issues = issues;
      state.validationScope = "Rejected import package";
      issues.forEach(d -> state.reportDiagnostic(d, EditorEvents.Subsystem.IMPORT_EXPORT, true));
      return false;
    }
    session = new ImportEditSession(state, incoming);
    filter.set("");
    kind = resolution = "All";
    message = "";
    resetRows();
    prompt = true;
    return true;
  }

  void cancel() {
    if (session != null) session.cancel();
    session = null;
    prompt = false;
    fields.clear();
    resetRows();
    message = "";
  }

  private void resetRows() {
    filteredPlan = null;
    rows = List.of();
    queryKey = "";
    scroll = 0f;
  }

  void draw() {
    controls.clear();
    rowControls.clear();
    submitted = 0;
    if (prompt) {
      ImGui.openPopup("Import Resources");
      prompt = false;
    }
    ImGui.setNextWindowSize(820, 650, ImGuiCond.FirstUseEver);
    if (!ImGui.beginPopupModal("Import Resources", ImGuiWindowFlags.NoSavedSettings)) return;
    if (!pending()) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    ImGui.textWrapped(
        "Preview COPY, compatible REUSE and SKIP with dependent exclusion. Existing definitions"
            + " stay owned by this project; import preserves a separate Properties draft.");
    boolean current = session.current(), unavailable = blocked.getAsBoolean();
    if (!current) {
      ImGui.textWrapped(
          "The destination changed. Reload Preview to retain your choices and review a fresh"
              + " transaction. COPY identities may change on reload.");
      ImGui.beginDisabled(!session.canReload() || unavailable);
      boolean reload = button("Reload Preview", "reload");
      ImGui.endDisabled();
      if (reload) {
        session.reload();
        resetRows();
        message = "";
        ImGui.endPopup();
        return;
      }
      if (!session.canReload())
        ImGui.textWrapped("The project was replaced. Cancel and reopen Import for that project.");
    }
    if (unavailable)
      ImGui.textWrapped(
          "Finish the other private editor or project action, or cancel this import.");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    ImGui.inputTextWithHint(
        "##import-filter", "Filter incoming IDs, kinds, UUIDs or target IDs...", filter);
    remember("filter");
    fields.choice(
        "Kind",
        kind,
        List.of("All", "noise2D", "noise3D", "folder", "layer", "voxel", "set", "group"),
        v -> kind = v);
    fields.choice(
        "Resolution filter",
        resolution,
        List.of("All", "COPY", "REUSE", "SKIP"),
        v -> resolution = v);
    var preview = session.preview();
    if (filteredPlan != preview) {
      copies = reuses = skips = 0;
      for (var row : preview.rows()) {
        if (row.resolution().startsWith("COPY")) copies++;
        else if (row.resolution().startsWith("REUSE")) reuses++;
        else skips++;
      }
    }
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    String key = kind + "\n" + resolution + "\n" + query;
    if (filteredPlan != preview || !key.equals(queryKey)) {
      rows =
          preview.rows().stream()
              .filter(
                  r ->
                      (kind.equals("All") || kind.equals(r.kind()))
                          && (resolution.equals("All") || r.resolution().startsWith(resolution))
                          && (r.kind()
                                  + " "
                                  + r.id()
                                  + " "
                                  + r.uuid()
                                  + " "
                                  + r.resolution()
                                  + " "
                                  + Objects.toString(r.targetId(), ""))
                              .toLowerCase(Locale.ROOT)
                              .contains(query))
              .toList();
      filteredPlan = preview;
      queryKey = key;
      queries++;
    }
    ImGui.text(rows.size() + " matching / " + preview.rows().size() + " incoming resource rows");
    ImGui.textDisabled(
        "COPY "
            + copies
            + " / REUSE "
            + reuses
            + " / SKIP "
            + skips
            + " (including dependent exclusion)");
    boolean hasIssues = !preview.diagnostics().isEmpty() || session.issueCount() > 0;
    ImGui.beginChild(
        "import-resources",
        0,
        Math.max(130, ImGui.getContentRegionAvailY() - (hasIssues ? 220 : 80)),
        true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(
          rows.size(),
          3 * ImGui.getTextLineHeightWithSpacing() + ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
          ImGui.pushID(row.uuid().toString());
          ImGui.text(AppMetadata.uiText(row.kind() + " / " + bounded(row.id(), 100)));
          if (ImGui.isItemHovered())
            ImGui.setTooltip(bounded(row.id(), 1024) + "\nIncoming UUID: " + row.uuid());
          ImGui.textDisabled(
              row.existing() == null
                  ? "No existing identity/ID conflict"
                  : "Existing: " + row.existing() + "; compatible: " + row.compatible());
          ImGui.beginDisabled(!current || unavailable);
          boolean expanded = ImGui.beginCombo("Resolution", session.choice(row.uuid()).name());
          var a = ImGui.getItemRectMin();
          var b = ImGui.getItemRectMax();
          rowControls.put(row.uuid(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
          if (expanded) {
            for (var choice : ResourceImport.Choice.values()) {
              if (choice == ResourceImport.Choice.REUSE && !row.compatible()) continue;
              if (ImGui.selectable(choice.name(), session.choice(row.uuid()) == choice))
                session.choose(row.uuid(), choice);
              remember(row.uuid() + ":" + choice);
            }
            ImGui.endCombo();
          }
          ImGui.endDisabled();
          ImGui.textDisabled(
              AppMetadata.uiText(
                  row.resolution()
                      + (row.targetId() == null ? "" : " -> " + bounded(row.targetId(), 100))));
          if (ImGui.isItemHovered() && row.targetUuid() != null)
            ImGui.setTooltip("Preview target UUID: " + row.targetUuid());
          ImGui.popID();
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    ImGui.endChild();
    if (hasIssues) ImGui.beginChild("import-issues", 0, 110, true);
    if (!preview.diagnostics().isEmpty()) {
      ImGui.textWrapped("Preview issues: " + preview.diagnostics().size());
      preview.diagnostics().stream()
          .limit(8)
          .forEach(d -> ImGui.textWrapped(bounded(d.toString(), 1024)));
    }
    if (session.issueCount() > 0) {
      ImGui.textWrapped("Rejected candidate issues: " + session.issueCount());
      session.applyIssues().stream()
          .limit(8)
          .forEach(d -> ImGui.textWrapped(bounded(d.toString(), 1024)));
    }
    if (hasIssues) ImGui.endChild();
    if (session.issueCount() > 0 && button("View Validation Results", "validation"))
      validation.run();
    // A choice edited while drawing invalidates this exact plan; its successor must be displayed.
    session.markShown(preview);
    ImGui.beginDisabled(!session.canApply() || unavailable);
    boolean apply = button("Import Previewed Transaction", "apply");
    ImGui.endDisabled();
    if (apply && !blocked.getAsBoolean()) {
      var result = session.apply();
      if (result == ImportEditSession.Result.APPLIED) {
        state.notify("Information", "Previewed resource import committed");
        cancel();
        ImGui.closeCurrentPopup();
      } else
        message =
            result == ImportEditSession.Result.STALE
                ? "The destination changed; reload and review the import."
                : "The import was rejected. Review its choices and validation results, or cancel.";
    }
    ImGui.sameLine();
    if (button("Cancel", "cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      cancel();
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private boolean button(String label, String key) {
    boolean clicked = ImGui.button(label);
    remember(key);
    return clicked;
  }

  private void remember(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  private static String bounded(String value, int length) {
    return value.length() <= length ? value : value.substring(0, length - 1) + "…";
  }

  float[] control(String key) {
    return controls.get(key);
  }

  float[] rowControl(UUID id) {
    return rowControls.get(id);
  }

  int submitted() {
    return submitted;
  }

  long queries() {
    return queries;
  }

  void probeScroll(float y) {
    scroll = y;
  }

  ImportEditSession session() {
    return session;
  }

  Set<UUID> visibleIds() {
    return Set.copyOf(rowControls.keySet());
  }
}
