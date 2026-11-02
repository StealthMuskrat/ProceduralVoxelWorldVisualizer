package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiKey;
import java.util.*;
import java.util.function.Consumer;

/** Read-only identity disclosure shared by Voxel Manager and Properties; parent edits own Apply. */
final class VoxelIdentityReview {
  private record Review(
      VoxelIdentityIndex.Type type, String proposed, Object draft, UUID selected) {}

  private final SettingsPageChrome chrome = new SettingsPageChrome(1);
  private final SettingsActionScope actions = new SettingsActionScope();
  private final String instance = UUID.randomUUID().toString();
  private Review review;
  private DialogBounds.Rect bounds;
  private int submitted;
  private String message = "";
  private Float scroll;

  void draw(EditorState state, UUID uuid, String proposed, float height, Consumer<UUID> find) {
    var type =
        state
            .voxelIdentities
            .get(state.document.sessionToken(), state.project(), state.revision)
            .type(uuid);
    if (type == null) {
      clear();
      return;
    }
    var next = new Review(type, proposed, state.draft, state.selected);
    if (review == null || next.type() != review.type() || !next.equals(review)) {
      review = next;
      message = "";
    }
    long revision = state.revision;
    Object lifetime = state.document.sessionToken();
    long version = actions.synchronize(lifetime, review, revision);
    submitted = 0;
    UiScale.beginChild("voxel-identity-review:" + instance, 0, Math.max(1, height), true);
    bounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    chrome.begin("voxel-identity-body");
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    ImGui.textWrapped("Semantic voxel ID: " + StatusBarText.clip(type.id(), 256));
    if (!type.id().equals(proposed))
      ImGui.textWrapped(
          "Proposed ID: "
              + StatusBarText.clip(Objects.requireNonNullElse(proposed, "(unset)"), 256));
    ImGui.textWrapped(
        "Properties Apply and inline Enter commit a validated rename while preserving UUID links."
            + " Properties Revert or inline Escape cancels that proposed rename.");
    ImGui.textWrapped(
        "Existing voxel archives retain their semantic ID strings and are not rewritten by a"
            + " project rename.");
    ImGui.textWrapped(
        type.dependents().size() + " dependent resource(s), including containing layers:");
    float stride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(type.dependents().size(), stride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var dependent = type.dependents().get(i);
          submitted++;
          float top = ImGui.getCursorPosY();
          ImGui.text(
              AppMetadata.uiText(
                  StatusBarText.clip(dependent.kind() + " / " + dependent.id(), 160)));
          PopupLayout.clippedLabel(
              dependent.kind() + " / " + dependent.id() + " / " + dependent.uuid());
          ImGui.setCursorPosY(top + stride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    if (type.dependents().isEmpty())
      ImGui.textDisabled("No committed resources reference this type.");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    chrome.end();
    ImGui.pushID("voxel-identity-action:" + instance + ":" + version);
    ImGui.beginDisabled(find == null);
    boolean requested = chrome.identifiedAction("references", "Find All References");
    ImGui.endDisabled();
    ImGui.popID();
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false);
    ImGui.endChild();
    if (requested
        && !escape
        && find != null
        && actions.current(state.document.sessionToken(), review, state.revision)
        && state.draft == review.draft()
        && Objects.equals(state.selected, review.selected())
        && state
                .voxelIdentities
                .get(state.document.sessionToken(), state.project(), state.revision)
                .type(uuid)
            == type) {
      try {
        find.accept(uuid);
      } catch (RuntimeException failure) {
        message =
            StatusBarText.bounded(
                "Could not open references: "
                    + Objects.requireNonNullElse(failure.getMessage(), failure.toString()),
                1024);
        state.error("VOXEL_ID_REFERENCES_FAILED", EditorEvents.Subsystem.APPLICATION, message);
      }
    }
  }

  void clear() {
    review = null;
    bounds = null;
    submitted = 0;
    scroll = null;
    message = "";
    actions.release();
  }

  VoxelIdentityIndex.Type type() {
    return review == null ? null : review.type();
  }

  String proposed() {
    return review == null ? "" : review.proposed();
  }

  DialogBounds.Rect bounds() {
    return bounds;
  }

  DialogBounds.Rect referenceBounds() {
    return chrome.actions().get("references");
  }

  void scroll(float value) {
    scroll = value;
  }

  int submitted() {
    return submitted;
  }

  float[] referencesAt() {
    var rect = chrome.actions().get("references");
    return rect == null
        ? null
        : new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2};
  }
}
