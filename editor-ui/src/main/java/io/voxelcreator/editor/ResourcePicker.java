package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.util.*;
import java.util.function.*;

/** Searchable UUID choices are consumed after complete drawing and balanced native scopes. */
public final class ResourcePicker {
  private final EditorState state;
  private final Supplier<?> owner;
  private final LongSupplier ownerVersion;
  private final ResourceTreeIndex.Cache cache = new ResourceTreeIndex.Cache();
  private final String nativeId = UUID.randomUUID().toString();
  private long openings;

  private static final class Widget {
    final ImString filter = new ImString(256);
    final ResourceChoices choices = new ResourceChoices();
    final SelectorSession session = new SelectorSession();
  }

  private final Map<Integer, Widget> widgets = new LinkedHashMap<>(16, .75f, true);
  final PopupLayout layout;
  private Widget lastWidget;
  private boolean probeOpen, focused;
  private final Map<UUID, float[]> optionBounds = new HashMap<>();
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();
  private int submitted;
  private float[] buttonBounds, noneBounds;
  private float stride;
  private io.voxelcreator.model.ProjectData observedProject;
  private long observedRevision;

  public ResourcePicker(EditorState state) {
    this(state, new PopupLayout(), null, () -> 0);
  }

  ResourcePicker(EditorState state, PopupLayout layout) {
    this(state, layout, null, () -> 0);
  }

  ResourcePicker(EditorState state, Supplier<?> owner, LongSupplier ownerVersion) {
    this(state, new PopupLayout(), owner, ownerVersion);
  }

  private ResourcePicker(
      EditorState state, PopupLayout layout, Supplier<?> owner, LongSupplier ownerVersion) {
    this.state = Objects.requireNonNull(state);
    this.layout = Objects.requireNonNull(layout);
    this.owner = owner == null ? () -> this : owner;
    this.ownerVersion = Objects.requireNonNull(ownerVersion);
  }

  public void synchronize() {
    if (observedProject != state.project() || observedRevision != state.revision) {
      cache.clear();
      widgets.values().forEach(widget -> widget.choices.clear());
      observedProject = state.project();
      observedRevision = state.revision;
    }
  }

  public boolean contains(String kind, UUID id) {
    synchronize();
    return ResourceChoices.accepts(cache.get(state.project(), state.revision).get(id), kind);
  }

  private Widget widget(int id) {
    if (!widgets.containsKey(id) && widgets.size() >= 128)
      widgets.remove(widgets.keySet().iterator().next());
    return widgets.computeIfAbsent(id, key -> new Widget());
  }

  private SelectorSession.Context context(
      ResourceTreeIndex index, String kind, UUID current, String none, Widget widget) {
    return new SelectorSession.Context(
        state.document.sessionToken(),
        state.project(),
        state.revision,
        owner.get(),
        ownerVersion.getAsLong(),
        current,
        kind,
        none,
        index,
        widget.filter.get());
  }

  public void draw(
      String label, String kind, UUID current, String noneLabel, Consumer<UUID> change) {
    synchronize();
    var index = cache.get(state.project(), state.revision);
    int id = ImGui.getID(label);
    var widget = widgets.get(id);
    var node = index.get(current);
    String preview =
        current == null
            ? Objects.requireNonNullElse(noneLabel, "Select...")
            : ResourceChoices.accepts(node, kind)
                ? ResourceChoices.label(node)
                : "Missing reference";
    ImGui.pushID(id);
    boolean clicked =
        ImGui.button(
            AppMetadata.uiText(preview) + "  v###resource-button",
            Math.max(1, Math.min(320, ImGui.getContentRegionAvailX() * .65f)),
            0);
    var anchor = PopupLayout.itemBounds();
    focused = ImGui.isItemActive() || ImGui.isItemFocused();
    buttonBounds = new float[] {anchor.x() + anchor.width() / 2, anchor.y() + anchor.height() / 2};
    if (ImGui.isItemHovered() && current != null) UiScale.tooltip(preview + " / " + current);
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false);
    boolean retrying = false;
    if ((clicked || probeOpen) && !escape) {
      widget = widget(id);
      widget.session.open();
      openings++;
      ImGui.openPopup("resource-choice");
    } else if (widget != null
        && !escape
        && widget.session.retry(
            context(index, kind, current, noneLabel, widget), ImGui.getFrameCount())) {
      openings++;
      retrying = true;
      ImGui.openPopup("resource-choice");
    }
    probeOpen = false;
    DialogLayout.sameLine(label);
    ImGui.textWrapped(label);
    submitted = 0;
    optionBounds.clear();
    rowIds.clear();
    noneBounds = null;
    SelectorSession.Ticket accepted = null;
    UUID chosen = null;
    if (layout.begin("resource-choice", anchor, 420, 340)) {
      var ticket =
          widget == null
              ? null
              : widget.session.observe(
                  ImGui.getFrameCount(), context(index, kind, current, noneLabel, widget));
      if (escape || ticket == null) {
        if (widget != null) widget.session.retire();
        ImGui.closeCurrentPopup();
      } else {
        lastWidget = widget;
        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        layout.search("##query", "Filter IDs...", widget.filter);
        // Search is intentionally outside the versioned row IDs.
        ticket =
            widget.session.observe(
                ImGui.getFrameCount(), context(index, kind, current, noneLabel, widget));
        if (ticket == null) {
          ImGui.closeCurrentPopup();
          ImGui.endPopup();
          ImGui.popID();
          return;
        }
        String scope = "resource-rows:" + nativeId + ":" + ticket.version();
        boolean selected = false;
        ImGui.pushID(scope);
        if (noneLabel != null) {
          String none = AppMetadata.uiText(noneLabel) + "###none";
          rowIds.put("none", ImGui.getID(none));
          selected = ImGui.selectable(none, current == null);
          var r = PopupLayout.itemBounds();
          noneBounds = new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
        }
        ImGui.popID();
        var rows = widget.choices.rows(index, kind, widget.filter.get());
        ImGui.textWrapped(rows.size() + " found");
        UiScale.beginChild(
            "resource-options", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
        layout.observeList();
        if (retrying) ImGui.setScrollY(0);
        if (!widget.session.feedback().isEmpty())
          ImGui.textWrapped("! " + widget.session.feedback());
        stride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
        ImGui.pushID(scope);
        var clipper = new ImGuiListClipper();
        try {
          clipper.begin(rows.size(), stride);
          while (clipper.step())
            for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
              var option = rows.get(i);
              submitted++;
              float top = ImGui.getCursorPosY();
              String row =
                  AppMetadata.uiText(ResourceChoices.label(option)) + "###" + option.uuid();
              rowIds.put(option.uuid().toString(), ImGui.getID(row));
              if (ImGui.selectable(
                      row,
                      option.uuid().equals(current),
                      0,
                      Math.max(1, ImGui.getContentRegionAvailX()),
                      ImGui.getTextLineHeight())
                  && !selected) {
                chosen = option.uuid();
                selected = true;
              }
              PopupLayout.clippedLabel(ResourceChoices.label(option) + " / " + option.uuid());
              var r = PopupLayout.itemBounds();
              optionBounds.put(
                  option.uuid(), new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
              ImGui.setCursorPosY(top + stride - ImGui.getStyle().getItemSpacingY());
              ImGui.dummy(0, 0);
            }
          clipper.end();
        } finally {
          clipper.destroy();
        }
        ImGui.popID();
        ImGui.endChild();
        if (selected
            && widget.session.consume(
                ticket, ImGui.getFrameCount(), context(index, kind, current, noneLabel, widget))) {
          accepted = ticket;
          ImGui.closeCurrentPopup();
        }
      }
      ImGui.endPopup();
    } else if (widget != null && widget.session.active()) widget.session.retire();
    ImGui.popID();
    if (accepted != null) {
      long completion = openings;
      try {
        change.accept(ResourceChoices.choose(index, kind, chosen, noneLabel != null));
        widget.session.complete(accepted);
      } catch (RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
        detail = StatusBarText.clip(detail, 1024);
        state.error("RESOURCE_CHOICE_FAILED", EditorEvents.Subsystem.APPLICATION, detail);
        if (completion == openings) widget.session.failed(accepted, detail);
        else widget.session.complete(accepted);
      }
    }
  }

  float[] noneCenter() {
    return noneBounds.clone();
  }

  boolean focused() {
    return focused;
  }

  float[] buttonCenter() {
    return buttonBounds.clone();
  }

  void probeOpen() {
    openings++;
    probeOpen = true;
    widgets.values().forEach(widget -> widget.session.retire());
  }

  void probeQuery(String value) {
    lastWidget.filter.set(value);
  }

  String query() {
    return lastWidget == null ? "" : lastWidget.filter.get();
  }

  String feedback() {
    return lastWidget == null ? "" : lastWidget.session.feedback();
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  float rowStride() {
    return stride;
  }

  float[] optionCenter(UUID id) {
    return optionBounds.get(id).clone();
  }

  int submitted() {
    return submitted;
  }

  int widgetCount() {
    return widgets.size();
  }
}
