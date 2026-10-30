package io.voxelcreator.editor;

import java.util.*;

/**
 * Pure client/monitor intersection and visible-main recovery for modal and selector presentations.
 */
final class PopupWorkArea {
  record Plan(
      int viewportId,
      DialogBounds.Rect bounds,
      boolean monitorConstrained,
      boolean recoveredToMain) {}

  private PopupWorkArea() {}

  static Plan resolve(
      int ownerId,
      int mainId,
      DialogBounds.Rect ownerClient,
      DialogBounds.Rect mainClient,
      DialogBounds.Rect anchor,
      UiMonitorWorkAreas.Snapshot inventory) {
    if (mainId == 0) throw new IllegalArgumentException("Missing main viewport");
    Objects.requireNonNull(mainClient);
    Objects.requireNonNull(inventory);
    int target = ownerId == 0 ? mainId : ownerId;
    boolean recovered = ownerClient == null && target != mainId;
    var client = ownerClient;
    if (client == null) {
      target = mainId;
      client = mainClient;
    }
    if (!inventory.absolute() || inventory.monitors().isEmpty())
      return new Plan(target, client, false, recovered);
    var monitor = choose(anchor == null ? client : anchor, inventory.monitors());
    var visible = intersection(client, monitor);
    if (usable(visible, client)) return new Plan(target, visible, true, recovered);
    // A fully offscreen owner, or a clipped sliver, must not trap a private decision there.
    var mainMonitor = choose(mainClient, inventory.monitors());
    var mainVisible = intersection(mainClient, mainMonitor);
    if (usable(mainVisible, mainClient))
      return new Plan(mainId, mainVisible, true, recovered || target != mainId);
    // Main-window placement owns physical main-host recovery. Retain its client coordinate space
    // until that occurs rather than inventing a popup viewport outside its actual client area.
    return new Plan(mainId, mainClient, false, recovered || target != mainId);
  }

  private static DialogBounds.Rect choose(
      DialogBounds.Rect anchor, List<DialogBounds.Rect> monitors) {
    return FloatingWindowBounds.work(true, false, null, anchor, monitors);
  }

  private static boolean usable(DialogBounds.Rect visible, DialogBounds.Rect client) {
    return visible != null
        && visible.width() >= Math.min(160, client.width())
        && visible.height() >= Math.min(120, client.height());
  }

  static DialogBounds.Rect intersection(DialogBounds.Rect a, DialogBounds.Rect b) {
    double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y());
    double right = Math.min((double) a.x() + a.width(), (double) b.x() + b.width());
    double bottom = Math.min((double) a.y() + a.height(), (double) b.y() + b.height());
    if (right <= x || bottom <= y) return null;
    return new DialogBounds.Rect((float) x, (float) y, (float) (right - x), (float) (bottom - y));
  }
}
