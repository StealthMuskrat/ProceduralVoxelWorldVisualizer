package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;

/** Advanced Find keeps committed query state separate from simple selectors and property drafts. */
public final class ResourceFindPanel {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(940, 680);
  private final EditorState state;
  final DialogLayout layout;
  private final PropertyPanel properties;
  private final ResourceSearchSession session = new ResourceSearchSession();
  private final SearchNavigation navigation;
  private final FindSelection selection = new FindSelection();
  private ResourceSearchIndex.Hit pendingHit;
  final SettingsPageChrome chrome = new SettingsPageChrome(3);
  final NavigationGuardDialog guardDialog;
  private String section = "Results";
  private DialogBounds.Rect resultsBounds, searchBounds, sectionBounds;
  private float rowStride;
  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<String, Integer> rowIds = new HashMap<>();
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
    guardDialog = new NavigationGuardDialog(state, "Find");
    layout = guardDialog.layout;
    this.properties = properties;
    navigation = new SearchNavigation(state);
    directories = new ResourcePicker(state);
  }

  public void references(UUID id) {
    reference = id;
    section = "Results";
    scroll = 0f;
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
      if (!navigation.pending()) selection.clear();
      guard();
      return;
    }
    windowLayout.prepare();
    boolean visible = ImGui.begin("Find", open);
    UiScale.window(state.user, "Find");
    windowLayout.observe();
    DialogLayout.observe(state, "Find");
    if (!visible || !open.get()) {
      if (!open.get()) session.clear();
      submitted = 0;
      centers.clear();
      ImGui.end();
      guard();
      return;
    }
    selection.synchronize(state);
    controls.clear();
    rowIds.clear();
    centers.clear();
    submitted = 0;
    resultsBounds = null;
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint("##find-text", "Find ID, UUID, authored value or breadcrumb...", query);
    searchBounds = PopupLayout.itemBounds();
    control("query");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##find-section", section, List.of("Results", "Filters"), value -> section = value);
    sectionBounds = PopupLayout.itemBounds();
    var filter =
        new ResourceSearchIndex.Filter(
            query.get(), kind, status, root, directory, reference, containers, mode);
    var results = session.search(state.project(), state.revision, filter);
    chrome.begin("find-workspace-" + section);
    var selectedHit = selection.hit();
    if (selectedHit != null) {
      ImGui.text(
          "Selected: "
              + StatusBarText.clip(selectedHit.kind() + " / " + selectedHit.label(), 160)
              + (results.stream().noneMatch(hit -> hit == selectedHit)
                  ? " (outside current filters)"
                  : ""));
      PopupLayout.clippedLabel(selectedHit.breadcrumb() + " / " + selectedHit.target().pointer());
    }
    if (section.equals("Filters")) drawFilters();
    else drawResults(results);
    if (!message.isEmpty()) ImGui.textWrapped(message);
    chrome.end();
    var hit = selection.hit();
    ImGui.beginDisabled(
        hit == null
            || !selection.owns(state, hit)
            || !state.canNavigate(hit.target())
            || navigation.pending());
    if (chrome.action("navigate", "Go To Selected")) activate(hit);
    control("navigate");
    ImGui.endDisabled();
    if (chrome.action("section", section.equals("Results") ? "Show Filters" : "Show Results"))
      section = section.equals("Results") ? "Filters" : "Results";
    control("section");
    if (chrome.action("clear", "Clear Filters")) filter(ResourceSearchIndex.Filter.all());
    control("clear");
    ImGui.end();
    guard();
  }

  private void choice(
      String label,
      String id,
      String current,
      List<String> options,
      java.util.function.Consumer<String> change) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(id, current, options, change);
  }

  private void drawFilters() {
    choice(
        "Match",
        "##find-match",
        mode.name(),
        Arrays.stream(ResourceSearchIndex.Mode.values()).map(Enum::name).toList(),
        value -> mode = ResourceSearchIndex.Mode.valueOf(value));
    choice("Kind", "##find-kind", kind, ResourceSearchIndex.KINDS, value -> kind = value);
    choice(
        "Root",
        "##find-root",
        root,
        ResourceSearchIndex.ROOTS,
        value -> {
          root = value;
          directory = null;
        });
    choice(
        "Enabled flags",
        "##find-enabled",
        status,
        List.of("All", "Enabled", "Disabled"),
        value -> status = value);
    directories.draw(
        "Folder or layer scope",
        "directory",
        directory,
        "All directories",
        value -> directory = value);
    if (reference != null) {
      ImGui.textWrapped("References to " + reference);
      var include = new ImBoolean(containers);
      if (ImGui.checkbox("##find-containers", include)) containers = include.get();
      DialogLayout.sameLine("Include containing resources");
      ImGui.textWrapped("Include containing resources");
      if (DialogLayout.button("Clear Reference Filter")) reference = null;
    }
    ImGui.textWrapped(
        "Filters search committed resources. They do not apply private Properties input.");
  }

  private void drawResults(List<ResourceSearchIndex.Hit> results) {
    ImGui.text(results.size() + " result(s)");
    PopupLayout.clippedLabel(
        "Search covers committed resources and does not apply private Properties input.");
    var chosen = selection.hit();
    if (!results.isEmpty()) smokeDrawn = true;
    float height =
        Math.max(
            1,
            ImGui.getWindowSizeY() - ImGui.getCursorPosY() - ImGui.getStyle().getWindowPaddingY());
    UiScale.beginChild("find-results", 0, height, true);
    resultsBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    rowStride = 2 * (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(results.size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var hit = results.get(i);
          float top = ImGui.getCursorPosY();
          submitted++;
          String label =
              AppMetadata.uiText(
                  StatusBarText.clip(
                      hit.kind() + " / " + hit.label() + (hit.enabled() ? "" : " [disabled]"),
                      160));
          String id = label + "###" + hit.key();
          rowIds.put(hit.key(), ImGui.getID(id));
          if (ImGui.selectable(
              id,
              chosen == hit,
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) {
            if (!navigation.pending()) {
              selection.select(state, hit, results);
              activate(hit);
            }
          }
          var bounds = PopupLayout.itemBounds();
          centers.put(
              hit.key(),
              new float[] {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2});
          PopupLayout.clippedLabel(hit.label() + " / " + hit.target().pointer());
          ImGui.textDisabled(AppMetadata.uiText(StatusBarText.clip(hit.breadcrumb(), 160)));
          PopupLayout.clippedLabel(hit.breadcrumb());
          ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
  }

  private void activate(ResourceSearchIndex.Hit hit) {
    if (navigation.pending() || !selection.owns(state, hit)) return;
    if (properties.searchBlocked()) {
      message = "Finish or cancel the open Curve/Membership Editor before navigating.";
      return;
    }
    var result = navigation.request(hit.target());
    if (result == SearchNavigation.Result.NAVIGATED) reveal(hit.target());
    else if (result == SearchNavigation.Result.NEEDS_DECISION) {
      pendingHit = hit;
      prompt = true;
    } else message = "This target is unavailable in the current project or draft.";
  }

  private void reveal(EditorEvents.Target target) {
    message =
        properties.revealSearch(target) ? "" : "This target is unavailable in the current draft.";
  }

  private void guard() {
    if (prompt && navigation.pending()) {
      guardDialog.open("Leave Property Draft");
      prompt = false;
    }
    guardDialog.draw(
        "Leave Property Draft",
        "The current resource has unapplied edits. Apply them, discard them for this navigation, or"
            + " cancel.",
        decision -> {
          var target = navigation.target();
          SearchNavigation.Result result;
          if (decision != SearchNavigation.Decision.CANCEL && !selection.owns(state, pendingHit)) {
            navigation.clear();
            result = SearchNavigation.Result.UNAVAILABLE;
          } else result = navigation.decide(decision);
          if (result != SearchNavigation.Result.NEEDS_DECISION) {
            prompt = false;
            pendingHit = null;
          }
          if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
          else if (result == SearchNavigation.Result.UNAVAILABLE)
            message = "Navigation became stale; select the result again.";
          return result;
        });
    if (!guardDialog.buttons().isEmpty()) guardDrawn = true;
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
    section = "Results";
    scroll = 0f;
    query.set(value.query());
    kind = value.kind();
    status = value.state();
    root = value.root();
    directory = value.directory();
    reference = value.reference();
    containers = value.containers();
    mode = value.mode();
  }

  private void control(String key) {
    var bounds = PopupLayout.itemBounds();
    controls.put(
        key, new float[] {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2});
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  DialogBounds.Rect resultsBounds() {
    return resultsBounds;
  }

  DialogBounds.Rect searchBounds() {
    return searchBounds;
  }

  DialogBounds.Rect sectionBounds() {
    return sectionBounds;
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  float rowStride() {
    return rowStride;
  }

  String section() {
    return section;
  }

  ResourceSearchIndex.Hit selectedHit() {
    return selection.hit();
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
