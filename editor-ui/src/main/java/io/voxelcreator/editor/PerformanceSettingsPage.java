package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.*;

/** Two retained private forms share one category without consuming each other's action space. */
final class PerformanceSettingsPage {
  private final PerformancePanel limits;
  private final UpdateTrustPanel trust;
  private final UiFields fields = new UiFields();
  private String section = "Performance limits";
  private DialogBounds.Rect selector;

  PerformanceSettingsPage(PerformancePanel limits, UpdateTrustPanel trust) {
    this.limits = Objects.requireNonNull(limits);
    this.trust = Objects.requireNonNull(trust);
  }

  void draw(Runnable savePreferences) {
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##performance-section",
        section,
        List.of("Performance limits", "Update trust"),
        value -> section = value);
    selector = PopupLayout.itemBounds();
    fields.tooltip("Choose performance limits or manual update trust");
    if (section.equals("Performance limits")) {
      trust.suspendActions();
      limits.draw(savePreferences);
    } else {
      limits.suspendActions();
      trust.draw(savePreferences);
    }
  }

  void suspendActions() {
    limits.suspendActions();
    trust.suspendActions();
  }

  void showLimits() {
    section = "Performance limits";
  }

  void showUpdates() {
    section = "Update trust";
  }

  String section() {
    return section;
  }

  DialogBounds.Rect selector() {
    return selector;
  }
}
