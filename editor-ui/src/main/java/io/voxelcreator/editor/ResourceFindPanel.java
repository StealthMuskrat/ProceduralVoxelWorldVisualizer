package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;

/** Advanced Find keeps committed query state separate from simple selectors and property drafts. */
public final class ResourceFindPanel {
  private final EditorState state;
  final DialogLayout layout;
  private final PropertyPanel properties;
  private final ResourceSearchSession session = new ResourceSearchSession();
  private final SearchNavigation navigation;
  private final ResourcePicker directories;
  private final UiFields fields = new UiFields();
  private final ImString query = new ImString(1024);
  private String kind = "All", status = "All", root = "All", message = "";
  private ResourceSearchIndex.Mode mode = ResourceSearchIndex.Mode.ANY_TEXT;
  private UUID directory, reference;
  private boolean containers, prompt;
  private int submitted;
  private Float scroll;
  private boolean guardDrawn;
  private boolean smokeDrawn;
  private final Map<String, float[]> centers = new HashMap<>();

  public ResourceFindPanel(EditorState state, PropertyPanel properties) {
    this.state = state;
    layout = new DialogLayout(state, "Find", 620, 280);
    this.properties = properties;
    navigation = new SearchNavigation(state);
    directories = new ResourcePicker(state);
  }

  public void references(UUID id) {
    reference = id;
    query.set("");
    kind = status = root = "All";
    directory = null;
    mode = ResourceSearchIndex.Mode.ANY_TEXT;
    containers = false;
  }

  public void clearReferences() {
    reference = null;
  }

  boolean navigationPending() {
    return navigation.pending();
  }

  public void draw(ImBoolean open) {
    directories.synchronize();
    if (!open.get()) {
      session.clear();
      guard();
      return;
    }
    ImGui.setNextWindowSize(940, 680, ImGuiCond.FirstUseEver);
    boolean visible = ImGui.begin("Find", open);
    UiScale.window(state.user, "Find");
    DialogLayout.observe(state, "Find");
    if (!visible || !open.get()) {
      if (!open.get()) session.clear();
      submitted = 0;
      centers.clear();
      ImGui.end();
      guard();
      return;
    }
    ImGui.inputTextWithHint("##find-text", "Find ID, UUID, authored value or breadcrumb...", query);
    fields.choice(
        "Match",
        mode.name(),
        Arrays.stream(ResourceSearchIndex.Mode.values()).map(Enum::name).toList(),
        v -> mode = ResourceSearchIndex.Mode.valueOf(v));
    fields.choice("Kind", kind, ResourceSearchIndex.KINDS, v -> kind = v);
    fields.choice(
        "Root",
        root,
        ResourceSearchIndex.ROOTS,
        v -> {
          root = v;
          directory = null;
        });
    fields.choice("Enabled flags", status, List.of("All", "Enabled", "Disabled"), v -> status = v);
    directories.draw(
        "Folder or layer scope", "directory", directory, "All directories", v -> directory = v);
    if (reference != null) {
      ImGui.text("References to " + reference);
      var include = new ImBoolean(containers);
      if (ImGui.checkbox("Include containing resources", include)) containers = include.get();
      if (ImGui.smallButton("Clear Reference Filter")) reference = null;
    }
    var filter =
        new ResourceSearchIndex.Filter(
            query.get(), kind, status, root, directory, reference, containers, mode);
    var results = session.search(state.project(), state.revision, filter);
    ImGui.text(results.size() + " result(s) / committed resources");
    if (!results.isEmpty()) smokeDrawn = true;
    if (!message.isEmpty()) ImGui.textWrapped(message);
    ImGui.beginChild("find-results", 0, 0, true);
    submitted = 0;
    centers.clear();
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(results.size(), 2 * ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var hit = results.get(i);
          submitted++;
          if (ImGui.selectable(
              AppMetadata.uiText(hit.kind() + " / " + hit.label())
                  + (hit.enabled() ? "" : " [disabled]")
                  + "###"
                  + hit.key())) activate(hit);
          var a = ImGui.getItemRectMin();
          var b = ImGui.getItemRectMax();
          centers.put(hit.key(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
          ImGui.textDisabled(hit.breadcrumb());
          if (ImGui.isItemHovered())
            ImGui.setTooltip(hit.breadcrumb() + "\n" + hit.target().pointer());
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
    ImGui.end();
    guard();
  }

  private void activate(ResourceSearchIndex.Hit hit) {
    if (properties.searchBlocked()) {
      message = "Finish or cancel the open Curve/Membership Editor before navigating.";
      return;
    }
    var result = navigation.request(hit.target());
    if (result == SearchNavigation.Result.NAVIGATED) reveal(hit.target());
    else if (result == SearchNavigation.Result.NEEDS_DECISION) prompt = true;
    else message = "This target is unavailable in the current project or draft.";
  }

  private void reveal(EditorEvents.Target target) {
    message =
        properties.revealSearch(target) ? "" : "This target is unavailable in the current draft.";
  }

  private void guard() {
    if (prompt) {
      layout.open("Leave Property Draft");
      prompt = false;
    }
    if (!layout.begin("Leave Property Draft", ImGuiWindowFlags.AlwaysAutoResize)) return;
    guardDrawn = true;
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      navigation.decide(SearchNavigation.Decision.CANCEL);
      ImGui.closeCurrentPopup();
    }
    ImGui.textWrapped(
        "The current resource has unapplied edits. Apply them, discard them for this navigation, or"
            + " cancel.");
    if (message.startsWith("Apply failed")) ImGui.textWrapped(message);
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) DialogLayout.sameLine(decision.name());
      if (DialogLayout.button(decision.name())) {
        var target = navigation.target();
        var result = navigation.decide(decision);
        if (result == SearchNavigation.Result.NEEDS_DECISION)
          message = "Apply failed; correct the draft or choose Discard/Cancel.";
        else {
          if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
          else if (result == SearchNavigation.Result.UNAVAILABLE)
            message = "Navigation became stale; select the result again.";
          ImGui.closeCurrentPopup();
        }
      }
    }
    ImGui.endPopup();
  }

  void smoke(boolean show) {
    if (show) {
      smokeDrawn = false;
      filter(
          new ResourceSearchIndex.Filter(
              "caves",
              "All",
              "All",
              "noise3D",
              null,
              null,
              false,
              ResourceSearchIndex.Mode.BREADCRUMB));
    } else {
      if (!smokeDrawn) throw new AssertionError("Find did not render real results");
      filter(ResourceSearchIndex.Filter.all());
    }
  }

  void filter(ResourceSearchIndex.Filter value) {
    query.set(value.query());
    kind = value.kind();
    status = value.state();
    root = value.root();
    directory = value.directory();
    reference = value.reference();
    containers = value.containers();
    mode = value.mode();
  }

  void scroll(float value) {
    scroll = value;
  }

  boolean guardDrawn() {
    return guardDrawn;
  }

  void query(String text) {
    query.set(text);
  }

  int submitted() {
    return submitted;
  }

  long builds() {
    return session.builds();
  }

  long queries() {
    return session.queries();
  }

  List<String> visibleKeys() {
    return centers.keySet().stream().sorted().toList();
  }

  float[] center(String key) {
    return centers.get(key).clone();
  }
}
