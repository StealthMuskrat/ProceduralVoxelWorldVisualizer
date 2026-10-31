package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.util.*;
import java.util.function.*;

/** Searchable local choices retain private queries and consume one reviewed selection at a time. */
public final class DraftChoicePicker {
  public record Choice(String key, String label, String detail) {}

  private static final class Widget {
    final ImString query = new ImString(256);
    final SelectorSession session = new SelectorSession();
    List<Choice> snapshot = List.of(), filtered = List.of(), source;
    String text = "";
    long evaluations;

    List<Choice> snapshot(List<Choice> values) {
      if (values != snapshot && !values.equals(snapshot)) snapshot = List.copyOf(values);
      return snapshot;
    }

    List<Choice> rows(List<Choice> values) {
      var frozen = snapshot(values);
      String next = query.get().strip().toLowerCase(Locale.ROOT);
      if (source != frozen || !text.equals(next)) {
        source = frozen;
        text = next;
        filtered =
            frozen.stream()
                .filter(
                    v ->
                        (v.label() + " " + v.detail() + " " + v.key())
                            .toLowerCase(Locale.ROOT)
                            .contains(next))
                .toList();
        evaluations++;
      }
      return filtered;
    }
  }

  private final EditorState state;
  private final Supplier<?> owner;
  private final LongSupplier ownerVersion;
  private final String nativeId = UUID.randomUUID().toString();
  private long openings;
  private final Map<Integer, Widget> widgets = new LinkedHashMap<>(16, .75f, true);
  private final Map<String, float[]> options = new HashMap<>();
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();
  final PopupLayout layout = new PopupLayout();
  private Widget lastWidget;
  private boolean open, focused;
  private int submitted;
  private float[] buttonCenter, noneCenter;
  private float stride;

  public DraftChoicePicker() {
    this(null, null, () -> 0);
  }

  DraftChoicePicker(EditorState state) {
    this(state, null, () -> 0);
  }

  DraftChoicePicker(EditorState state, Supplier<?> owner, LongSupplier ownerVersion) {
    this.state = state;
    this.owner = owner == null ? () -> this : owner;
    this.ownerVersion = Objects.requireNonNull(ownerVersion);
  }

  private Widget widget(int id) {
    if (!widgets.containsKey(id) && widgets.size() >= 128)
      widgets.remove(widgets.keySet().iterator().next());
    return widgets.computeIfAbsent(id, ignored -> new Widget());
  }

  private SelectorSession.Context context(
      Widget widget, String current, List<Choice> choices, String none) {
    return new SelectorSession.Context(
        state == null ? null : state.document.sessionToken(),
        state == null ? null : state.project(),
        state == null ? 0 : state.revision,
        owner.get(),
        ownerVersion.getAsLong(),
        current,
        "local",
        none,
        widget.snapshot(choices),
        widget.query.get());
  }

  public void draw(
      String label, String current, List<Choice> choices, String none, Consumer<String> change) {
    String preview =
        current == null
            ? Objects.requireNonNullElse(none, "Choose...")
            : choices.stream()
                .filter(c -> c.key().equals(current))
                .map(Choice::label)
                .findFirst()
                .orElse("Unavailable choice");
    int id = ImGui.getID(label);
    var widget = widgets.get(id);
    ImGui.pushID(id);
    boolean clicked =
        ImGui.button(
            AppMetadata.uiText(preview) + "  v###draft-choice",
            Math.max(1, Math.min(330, ImGui.getContentRegionAvailX() * .7f)),
            0);
    var anchor = PopupLayout.itemBounds();
    PopupLayout.clippedLabel(preview);
    focused = ImGui.isItemActive() || ImGui.isItemFocused();
    buttonCenter = new float[] {anchor.x() + anchor.width() / 2, anchor.y() + anchor.height() / 2};
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false), retrying = false;
    if ((clicked || open) && !escape) {
      widget = widget(id);
      widget.session.open();
      openings++;
      ImGui.openPopup("draft-choices");
    } else if (widget != null
        && !escape
        && widget.session.retry(context(widget, current, choices, none), ImGui.getFrameCount())) {
      openings++;
      retrying = true;
      ImGui.openPopup("draft-choices");
    }
    open = false;
    DialogLayout.sameLine(label);
    ImGui.textWrapped(label);
    submitted = 0;
    options.clear();
    rowIds.clear();
    noneCenter = null;
    SelectorSession.Ticket accepted = null;
    String chosen = null;
    if (layout.begin("draft-choices", anchor, 480, 340)) {
      var ticket =
          widget == null
              ? null
              : widget.session.observe(
                  ImGui.getFrameCount(), context(widget, current, choices, none));
      if (escape || ticket == null) {
        if (widget != null) widget.session.retire();
        ImGui.closeCurrentPopup();
      } else {
        lastWidget = widget;
        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        layout.search("##query", "Filter names, types or categories...", widget.query);
        ticket =
            widget.session.observe(ImGui.getFrameCount(), context(widget, current, choices, none));
        if (ticket == null) {
          ImGui.closeCurrentPopup();
          ImGui.endPopup();
          ImGui.popID();
          return;
        }
        String scope = "draft-rows:" + nativeId + ":" + ticket.version();
        boolean selected = false;
        ImGui.pushID(scope);
        if (none != null) {
          String row = AppMetadata.uiText(none) + "###none";
          rowIds.put("none", ImGui.getID(row));
          selected = ImGui.selectable(row, current == null);
          var r = PopupLayout.itemBounds();
          noneCenter = new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
        }
        ImGui.popID();
        var rows = widget.rows(choices);
        ImGui.textWrapped(rows.size() + " found");
        UiScale.beginChild("options", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
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
              var row = rows.get(i);
              submitted++;
              float top = ImGui.getCursorPosY();
              String key =
                  AppMetadata.uiText(row.label() + " / " + row.detail())
                      + "###choice-"
                      + i
                      + ":"
                      + row.key();
              rowIds.put(row.key(), ImGui.getID(key));
              if (ImGui.selectable(
                      key,
                      row.key().equals(current),
                      0,
                      Math.max(1, ImGui.getContentRegionAvailX()),
                      ImGui.getTextLineHeight())
                  && !selected) {
                chosen = row.key();
                selected = true;
              }
              PopupLayout.clippedLabel(row.label() + " / " + row.detail() + " / " + row.key());
              var r = PopupLayout.itemBounds();
              options.put(row.key(), new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
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
                ticket, ImGui.getFrameCount(), context(widget, current, choices, none))) {
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
        change.accept(chosen);
        widget.session.complete(accepted);
      } catch (RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
        detail = StatusBarText.clip(detail, 1024);
        if (state != null)
          state.error("DRAFT_CHOICE_FAILED", EditorEvents.Subsystem.APPLICATION, detail);
        if (completion == openings) widget.session.failed(accepted, detail);
        else widget.session.complete(accepted);
      }
    }
  }

  boolean focused() {
    return focused;
  }

  float[] buttonCenter() {
    return buttonCenter.clone();
  }

  void probeOpen() {
    openings++;
    open = true;
    widgets.values().forEach(widget -> widget.session.retire());
  }

  void probeQuery(String value) {
    lastWidget.query.set(value);
  }

  String query() {
    return lastWidget == null ? "" : lastWidget.query.get();
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

  long evaluations() {
    return lastWidget == null ? 0 : lastWidget.evaluations;
  }

  float[] noneCenter() {
    return noneCenter.clone();
  }

  float[] option(String id) {
    return options.get(id).clone();
  }

  int submitted() {
    return submitted;
  }

  int contexts() {
    return widgets.size();
  }
}
