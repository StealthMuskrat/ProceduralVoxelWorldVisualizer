package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import java.util.*;
import java.util.function.Consumer;

/** Retained topic/guidance pages with clipped results and persistent navigation/copy actions. */
final class HelpPanel {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(750, 600);
  final SettingsPageChrome chrome = new SettingsPageChrome(2, 2);
  private final EditorState state;
  private final Consumer<String> copy;
  private final HelpSession session = new HelpSession();
  private final ImString query = new ImString(1024);
  private final UiFields fields = new UiFields();
  private String page = "Topics";
  private boolean reveal, scrollTop, scrollEnd;
  private DialogBounds.Rect searchBounds, selectorBounds, resultsBounds;
  private float stride;
  private int submitted;
  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();

  HelpPanel(EditorState state, Consumer<String> copy) {
    this.state = Objects.requireNonNull(state);
    this.copy = Objects.requireNonNull(copy);
    session.synchronize(state.metadata);
  }

  boolean openTopic(String id) {
    session.synchronize(state.metadata);
    if (!session.open(id)) return false;
    query.set("");
    page = "Guidance";
    scrollTop = true;
    return true;
  }

  void draw(ImBoolean open) {
    if (!open.get()) return;
    windowLayout.prepare();
    boolean visible = ImGui.begin("Help", open);
    UiScale.window(state.user, "Help");
    windowLayout.observe();
    if (!visible || !open.get()) {
      ImGui.end();
      return;
    }
    session.synchronize(state.metadata);
    controls.clear();
    ids.clear();
    submitted = 0;
    resultsBounds = null;
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint(
        "##help-query", "Search features, guidance, options or related topics", query);
    searchBounds = PopupLayout.itemBounds();
    control("query");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice("##help-page", page, List.of("Topics", "Guidance"), this::page);
    selectorBounds = PopupLayout.itemBounds();
    var results = session.catalog().search(query.get());
    chrome.begin("help-body");
    if (scrollTop) {
      ImGui.setScrollY(0);
      scrollTop = false;
    }
    if (scrollEnd) {
      ImGui.setScrollY(ImGui.getScrollMaxY());
      scrollEnd = false;
    }
    var selected = session.selected();
    if (selected != null) {
      ImGui.text(
          "Selected: "
              + StatusBarText.clip(selected.name(), 160)
              + (results.stream().noneMatch(t -> t.id().equals(selected.id()))
                  ? " (outside search)"
                  : ""));
      PopupLayout.clippedLabel(selected.name());
    }
    if (page.equals("Topics")) results(results);
    else guidance(selected);
    chrome.end();
    ImGui.beginDisabled(selected == null);
    if (chrome.action("copy", "Copy")) copy.accept(selected.copyText(session.catalog()));
    control("copy");
    ImGui.endDisabled();
    if (chrome.action("page", page.equals("Topics") ? "Guidance" : "Topics"))
      page(page.equals("Topics") ? "Guidance" : "Topics");
    control("page");
    ImGui.beginDisabled(!session.canBack());
    if (chrome.action("back", "Back") && session.back()) {
      query.set("");
      page("Guidance");
    }
    control("back");
    ImGui.endDisabled();
    if (chrome.action("clear", "Clear")) {
      query.set("");
      page("Topics");
      reveal = true;
    }
    control("clear");
    ImGui.end();
  }

  private void results(List<HelpCatalog.Topic> topics) {
    ImGui.text(topics.size() + " topic(s)");
    PopupLayout.clippedLabel(
        "Select a topic and choose Guidance. Clear reveals the selected topic in the full list.");
    UiScale.beginChild("help-results", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
    resultsBounds = bounds();
    stride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    var selected = session.selected();
    if (reveal && selected != null) {
      int index = topics.indexOf(selected);
      if (index >= 0) ImGui.setScrollY(index * stride);
      reveal = false;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(topics.size(), stride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var topic = topics.get(i);
          float top = ImGui.getCursorPosY();
          String widget = AppMetadata.uiText(topic.name()) + "###help-topic-" + topic.id();
          ids.put(topic.id(), ImGui.getID(widget));
          if (ImGui.selectable(
              widget,
              selected == topic,
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) session.open(topic.id());
          control("topic:" + topic.id());
          PopupLayout.clippedLabel(topic.name());
          ImGui.setCursorPosY(top + stride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
          submitted++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
  }

  private void guidance(HelpCatalog.Topic topic) {
    if (topic == null) {
      ImGui.textWrapped(
          "Select a topic on the Topics page to inspect guidance and related topics.");
      return;
    }
    ImGui.textWrapped(topic.group() + " / " + topic.id());
    ImGui.textWrapped(topic.tooltip());
    ImGui.separator();
    ImGui.textWrapped(topic.guidance());
    if (!topic.options().isEmpty()) {
      ImGui.separator();
      ImGui.textWrapped("Relevant options (canonical key — display label)");
      for (var option : topic.options()) ImGui.textWrapped(option.key() + " — " + option.label());
    }
    ImGui.separator();
    ImGui.textWrapped("Related topics");
    for (String key : topic.related()) {
      var related = session.catalog().topic(key);
      if (ImGui.selectable(
          AppMetadata.uiText(related.name()) + "###help-related-" + key,
          false,
          0,
          Math.max(1, ImGui.getContentRegionAvailX()),
          ImGui.getTextLineHeight())) openTopic(key);
      control("related:" + key);
      PopupLayout.clippedLabel(related.name());
    }
  }

  private static DialogBounds.Rect bounds() {
    return new DialogBounds.Rect(
        ImGui.getWindowPosX(),
        ImGui.getWindowPosY(),
        ImGui.getWindowSizeX(),
        ImGui.getWindowSizeY());
  }

  private void control(String key) {
    var rect = PopupLayout.itemBounds();
    controls.put(key, new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2});
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(ids);
  }

  DialogBounds.Rect searchBounds() {
    return searchBounds;
  }

  DialogBounds.Rect selectorBounds() {
    return selectorBounds;
  }

  DialogBounds.Rect resultsBounds() {
    return resultsBounds;
  }

  float rowStride() {
    return stride;
  }

  int submittedRows() {
    return submitted;
  }

  String page() {
    return page;
  }

  HelpSession session() {
    return session;
  }

  void topics() {
    page("Topics");
  }

  void guidance() {
    page("Guidance");
  }

  private void page(String value) {
    page = value;
    scrollTop = true;
  }

  void probeEnd() {
    scrollEnd = true;
  }
}
