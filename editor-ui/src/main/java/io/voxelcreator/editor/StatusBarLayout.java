package io.voxelcreator.editor;

import java.util.*;

/** Pure pixel-aligned dock/status split and bounded, notification-prioritized action slots. */
final class StatusBarLayout {
  record Rect(float x, float y, float width, float height) {
    Rect {
      if (!Float.isFinite(x)
          || !Float.isFinite(y)
          || !Float.isFinite(width)
          || !Float.isFinite(height)
          || width < 0
          || height < 0) throw new IllegalArgumentException("Invalid chrome rectangle");
    }

    boolean visible() {
      return width > 0 && height > 0;
    }
  }

  record Metrics(
      float fontHeight,
      float frameHeight,
      float gap,
      float minimumStatus,
      List<Float> settings,
      List<Float> notifications) {
    Metrics {
      settings = List.copyOf(settings);
      notifications = List.copyOf(notifications);
      if (settings.size() != 2 || notifications.size() != 3)
        throw new IllegalArgumentException("Missing action label widths");
      for (float value : settings) positive(value);
      for (float value : notifications) positive(value);
      positive(fontHeight);
      positive(frameHeight);
      positive(minimumStatus);
      if (!Float.isFinite(gap) || gap < 0) throw new IllegalArgumentException("Invalid spacing");
    }
  }

  record Plan(
      Rect dock,
      Rect bar,
      Rect status,
      Rect settings,
      Rect notifications,
      int settingsLabel,
      int notificationLabel,
      boolean twoRows) {}

  private StatusBarLayout() {}

  static Plan prepare(DialogBounds.Rect work, Metrics metrics) {
    double x = Math.ceil(work.x()), y = Math.ceil(work.y());
    double width = Math.max(0, Math.floor((double) work.x() + work.width()) - x);
    double height = Math.max(0, Math.floor((double) work.y() + work.height()) - y);
    double available = Math.max(0, width - 16);
    boolean two =
        available
            < (double) metrics.minimumStatus()
                + 2 * metrics.gap()
                + metrics.settings().getFirst()
                + metrics.notifications().getFirst();
    double wanted = metrics.frameHeight() + 8.0 + (two ? metrics.fontHeight() + metrics.gap() : 0);
    double barHeight = Math.min(Math.ceil(wanted), Math.max(0, height - 1));
    double top = y + height - barHeight;
    var dock = rect(x, y, width, height - barHeight);
    var bar = rect(x, top, width, barHeight);
    int notice = 0;
    if (two)
      while (notice < 2
          && metrics.notifications().get(notice)
                  + (double) metrics.settings().get(1)
                  + metrics.gap()
              > available) notice++;
    double noticeWidth = Math.min(available, metrics.notifications().get(notice));
    double settingsAvailable = Math.max(0, available - noticeWidth - metrics.gap());
    int settings = settingsAvailable >= metrics.settings().getFirst() ? 0 : 1;
    double settingsWidth = Math.min(settingsAvailable, metrics.settings().get(settings));
    double actionHeight = Math.min(metrics.frameHeight(), Math.max(0, barHeight - 8));
    double actionY = two ? top + Math.max(4, barHeight - 4 - actionHeight) : top + 4;
    double right = x + width - Math.min(8, width / 2);
    double left = x + Math.min(8, width / 2);
    var notificationRect = rect(right - noticeWidth, actionY, noticeWidth, actionHeight);
    var settingsRect =
        rect(
            two ? left : right - noticeWidth - metrics.gap() - settingsWidth,
            actionY,
            settingsWidth,
            actionHeight);
    double statusHeight =
        two
            ? Math.min(metrics.fontHeight(), Math.max(0, actionY - top - 4 - metrics.gap()))
            : Math.min(metrics.fontHeight(), actionHeight);
    var status =
        rect(
            left,
            top + 4 + (two ? 0 : Math.max(0, (actionHeight - statusHeight) / 2)),
            two ? available : Math.max(0, settingsRect.x() - metrics.gap() - left),
            statusHeight);
    if (barHeight < 8) {
      status = rect(left, top, 0, 0);
      settingsRect = rect(left, top, 0, 0);
      notificationRect = rect(left, top, available, barHeight);
    }
    return new Plan(dock, bar, status, settingsRect, notificationRect, settings, notice, two);
  }

  private static Rect rect(double x, double y, double width, double height) {
    return new Rect(coordinate(x), coordinate(y), dimension(width), dimension(height));
  }

  private static float coordinate(double value) {
    return (float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, value));
  }

  private static float dimension(double value) {
    return (float) Math.max(0, Math.min(Float.MAX_VALUE, value));
  }

  private static void positive(float value) {
    if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid metric");
  }
}
