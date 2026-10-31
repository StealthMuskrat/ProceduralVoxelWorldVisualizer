package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.*;
import java.util.function.*;

/** Small finite view choices, consumed after complete bounded popup drawing and native cleanup. */
final class ViewportChoicePanel {
  enum Option {
    DISPLAY("Display", List.of("Grayscale", "Heatmap", "Contour", "Threshold", "Histogram")),
    REPRESENTATION(
        "Representation",
        List.of("Threshold Voxels", "Isosurface", "Volume", "Point Cloud", "Slices")),
    SLICE_NORMAL("Slice normal", List.of("X", "Y", "Z")),
    STAGE_PREVIEW("Stage preview", List.of("Final", "Before", "After", "Only Changes"));
    final String label;
    final List<String> values;

    Option(String label, List<String> values) {
      this.label = label;
      this.values = values;
    }

    boolean accepts(String value) {
      return value != null && values.contains(value);
    }
  }

  record Review(ViewportActionContext view, String current) {
    boolean same(Review other) {
      return other != null && view.same(other.view) && Objects.equals(current, other.current);
    }
  }

  private final EditorState state;
  final Option option;
  final PopupLayout layout = new PopupLayout();
  private final SettingsActionScope trigger = new SettingsActionScope();
  private final SelectorSession session = new SelectorSession();
  private final String nativeId = UUID.randomUUID().toString();
  private Review shown;
  private int frame = -1, buttonId;
  private long openings;
  private boolean openRequested;
  private DialogBounds.Rect buttonBounds;
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> rowBounds = new LinkedHashMap<>();
  private float stride;

  ViewportChoicePanel(EditorState state, Option option) {
    this.state = Objects.requireNonNull(state);
    this.option = Objects.requireNonNull(option);
  }

  private SelectorSession.Context context(Review review) {
    var view = review.view();
    return new SelectorSession.Context(
        view.document(),
        view.project(),
        view.revision(),
        shown,
        0,
        review.current(),
        option.name(),
        null,
        option.values,
        "");
  }

  void draw(Supplier<Review> source, Consumer<String> change) {
    var review = source.get();
    int currentFrame = ImGui.getFrameCount();
    if (currentFrame < frame || currentFrame - frame > 1) suspend();
    frame = currentFrame;
    if (!review.same(shown)) shown = review;
    long version = trigger.synchronize(review.view().document(), shown);
    rowIds.clear();
    rowBounds.clear();
    ImGui.pushID("viewport-choice:" + nativeId);
    ImGui.pushID("trigger:" + version);
    String preview = option.accepts(review.current()) ? review.current() : "Unavailable choice";
    String label = AppMetadata.uiText(preview) + "  v###choice";
    buttonId = ImGui.getID(label);
    boolean clicked =
        ImGui.button(label, Math.max(1, Math.min(330, ImGui.getContentRegionAvailX() * .7f)), 0);
    buttonBounds = PopupLayout.itemBounds();
    PopupLayout.clippedLabel(Objects.toString(review.current(), "Choose..."));
    ImGui.popID();
    DialogLayout.sameLine(option.label);
    ImGui.textWrapped(option.label);
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false), retrying = false;
    if ((clicked || openRequested) && !escape && review.same(source.get())) {
      session.open();
      openings++;
      ImGui.openPopup("choices");
    } else if (!escape && session.retry(context(review), currentFrame)) {
      openings++;
      retrying = true;
      ImGui.openPopup("choices");
    }
    openRequested = false;
    String chosen = null;
    SelectorSession.Ticket accepted = null;
    if (layout.begin("choices", buttonBounds, 420, 280)) {
      var ticket = session.observe(currentFrame, context(review));
      if (escape || ticket == null) {
        session.retire();
        ImGui.closeCurrentPopup();
      } else {
        UiScale.beginChild(
            "viewport-choice-options", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
        layout.observeList();
        if (retrying) ImGui.setScrollY(0);
        if (!session.feedback().isEmpty()) ImGui.textWrapped("! " + session.feedback());
        stride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
        ImGui.pushID("rows:" + ticket.version());
        for (String value : option.values) {
          float top = ImGui.getCursorPosY();
          rowIds.put(value, ImGui.getID(value));
          if (ImGui.selectable(
                  value,
                  value.equals(review.current()),
                  0,
                  Math.max(1, ImGui.getContentRegionAvailX()),
                  ImGui.getTextLineHeight())
              && chosen == null) chosen = value;
          rowBounds.put(value, PopupLayout.itemBounds());
          PopupLayout.clippedLabel(value);
          ImGui.setCursorPosY(top + stride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
        }
        ImGui.popID();
        ImGui.endChild();
        if (chosen != null
            && !ImGui.isKeyPressed(ImGuiKey.Escape, false)
            && review.same(source.get())
            && session.consume(ticket, currentFrame, context(review))) {
          accepted = ticket;
          ImGui.closeCurrentPopup();
        } else chosen = null;
      }
      ImGui.endPopup();
    } else if (session.active()) session.retire();
    ImGui.popID();
    if (accepted != null) {
      long completion = openings;
      try {
        change.accept(chosen);
        session.complete(accepted);
      } catch (RuntimeException failure) {
        var detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
        detail = StatusBarText.clip(detail, 1024);
        state.error("VIEWPORT_OPTION_FAILED", EditorEvents.Subsystem.RENDERING, detail);
        if (completion == openings) session.failed(accepted, detail);
        else session.complete(accepted);
      }
    }
  }

  void finishFrame(int currentFrame) {
    if (frame != currentFrame) suspend();
  }

  private void suspend() {
    trigger.release();
    session.retire();
    shown = null;
    frame = -1;
    buttonId = 0;
    buttonBounds = null;
    rowIds.clear();
    rowBounds.clear();
  }

  void probeOpen() {
    openings++;
    session.retire();
    openRequested = true;
  }

  int buttonId() {
    return buttonId;
  }

  DialogBounds.Rect buttonBounds() {
    return buttonBounds;
  }

  float[] buttonCenter() {
    return center(buttonBounds);
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  Map<String, DialogBounds.Rect> rowBounds() {
    return Map.copyOf(rowBounds);
  }

  float[] rowCenter(String value) {
    return center(rowBounds.get(value));
  }

  String feedback() {
    return session.feedback();
  }

  float stride() {
    return stride;
  }

  private static float[] center(DialogBounds.Rect r) {
    return r == null ? null : new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
  }
}
