package io.voxelcreator.editor;

import imgui.*;
import java.util.*;
import java.util.function.Supplier;

/** Native value acquisition and transient zero-size retention; the fitting decision stays pure. */
final class PopupWorkAreaResolver {
  private final Supplier<UiMonitorWorkAreas.Snapshot> inventory;
  private DialogBounds.Rect lastClient;
  private int lastViewport, lastFrame = -1;

  PopupWorkAreaResolver() {
    this(UiMonitorWorkAreas::snapshot);
  }

  PopupWorkAreaResolver(Supplier<UiMonitorWorkAreas.Snapshot> inventory) {
    this.inventory = Objects.requireNonNull(inventory);
  }

  PopupWorkArea.Plan resolve(int requested, DialogBounds.Rect anchor, float width, float height) {
    int frame = ImGui.getFrameCount();
    if (frame < lastFrame) {
      lastClient = null;
      lastViewport = 0;
    }
    lastFrame = frame;
    var main = ImGui.getMainViewport();
    var mainClient = work(main);
    if (mainClient == null)
      mainClient =
          UiMonitorWorkAreas.rectangle(
              main.getPosX(), main.getPosY(), main.getSizeX(), main.getSizeY());
    if (mainClient == null)
      mainClient =
          new DialogBounds.Rect(0, 0, Math.max(160, width) + 16, Math.max(120, height) + 16);
    int target = requested == 0 ? main.getID() : requested;
    var owner = target == main.getID() ? main : ImGui.findViewportByID(target);
    DialogBounds.Rect client = null;
    if (owner != null && owner.isValidPtr()) {
      client = work(owner);
      if (client == null && lastViewport == target) client = lastClient;
      if (client == null && target == main.getID()) client = mainClient;
    }
    if (client != null) {
      lastClient = client;
      lastViewport = target;
    }
    return PopupWorkArea.resolve(target, main.getID(), client, mainClient, anchor, inventory.get());
  }

  private static DialogBounds.Rect work(ImGuiViewport viewport) {
    return UiMonitorWorkAreas.rectangle(
        viewport.getWorkPosX(),
        viewport.getWorkPosY(),
        viewport.getWorkSizeX(),
        viewport.getWorkSizeY());
  }
}
