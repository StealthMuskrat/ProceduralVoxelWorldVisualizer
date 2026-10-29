package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Main-window screen-coordinate geometry; independent of GLFW, ImGui and authored projects. */
public final class WindowPlacement {
  public static final int MIN_WIDTH = 400, MIN_HEIGHT = 300;
  public static final int BOOTSTRAP_WIDTH = 1440, BOOTSTRAP_HEIGHT = 900;

  public record Rect(int x, int y, int width, int height) {
    public Rect {
      if (width < MIN_WIDTH || height < MIN_HEIGHT)
        throw new IllegalArgumentException("Normal window size must be at least 400 x 300");
    }
  }

  public record WorkArea(int x, int y, int width, int height) {
    public WorkArea {
      if (width <= 0 || height <= 0) throw new IllegalArgumentException("Empty monitor work area");
    }
  }

  public record Insets(int left, int top, int right, int bottom) {
    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public Insets {
      if (left < 0 || top < 0 || right < 0 || bottom < 0)
        throw new IllegalArgumentException("Negative native frame extent");
    }
  }

  public record Plan(Rect normal, boolean maximized) {}

  private WindowPlacement() {}

  public static void validate(UserConfig.Workspace workspace) {
    if (workspace.width < MIN_WIDTH
        || workspace.height < MIN_HEIGHT
        || (workspace.x == null) != (workspace.y == null))
      throw new IllegalArgumentException("Invalid normal window placement");
  }

  static void validateStored(JsonNode tree) {
    var workspace = tree.path("workspace");
    for (var field : List.of("width", "height", "x", "y")) {
      if (!workspace.has(field)) continue;
      var value = workspace.get(field);
      if (List.of("x", "y").contains(field) && value.isNull()) continue;
      if (!value.isIntegralNumber() || !value.canConvertToInt())
        throw new IllegalArgumentException("Invalid window placement integer: " + field);
    }
    if (workspace.has("maximized") && !workspace.path("maximized").isBoolean())
      throw new IllegalArgumentException("Invalid maximized preference");
  }

  /** Ordered work areas put the primary monitor first; ties retain that order. */
  static Plan restore(
      UserConfig.Workspace workspace, List<WorkArea> areas, Insets frame, boolean center) {
    validate(workspace);
    Objects.requireNonNull(frame);
    var monitors = List.copyOf(areas);
    if (monitors.isEmpty())
      monitors = List.of(new WorkArea(0, 0, BOOTSTRAP_WIDTH, BOOTSTRAP_HEIGHT));
    Rect saved =
        workspace.x == null
            ? null
            : new Rect(workspace.x, workspace.y, workspace.width, workspace.height);
    WorkArea target =
        center || saved == null ? monitors.getFirst() : choose(saved, monitors, frame);
    int width =
        (int)
            Math.max(
                MIN_WIDTH,
                Math.min(
                    (long) workspace.width, (long) target.width() - frame.left() - frame.right()));
    int height =
        (int)
            Math.max(
                MIN_HEIGHT,
                Math.min(
                    (long) workspace.height,
                    (long) target.height() - frame.top() - frame.bottom()));
    long left = (long) target.x() + frame.left(), top = (long) target.y() + frame.top();
    long right = (long) target.x() + target.width() - frame.right() - width;
    long bottom = (long) target.y() + target.height() - frame.bottom() - height;
    long x = center || saved == null ? left + Math.max(0, right - left) / 2 : saved.x();
    long y = center || saved == null ? top + Math.max(0, bottom - top) / 2 : saved.y();
    var normal =
        new Rect(
            coordinate(clamp(x, left, Math.max(left, right))),
            coordinate(clamp(y, top, Math.max(top, bottom))),
            width,
            height);
    return new Plan(normal, !center && workspace.maximized);
  }

  private static WorkArea choose(Rect window, List<WorkArea> areas, Insets frame) {
    WorkArea chosen = areas.getFirst();
    long largest = -1, nearest = Long.MAX_VALUE;
    long left = (long) window.x() - frame.left(), top = (long) window.y() - frame.top();
    long right = (long) window.x() + window.width() + frame.right(),
        bottom = (long) window.y() + window.height() + frame.bottom();
    for (var area : areas) {
      long overlapX = overlap(left, right, area.x(), (long) area.x() + area.width());
      long overlapY = overlap(top, bottom, area.y(), (long) area.y() + area.height());
      long intersection = overlapX * overlapY;
      long distance =
          gap(left, right, area.x(), (long) area.x() + area.width())
              + gap(top, bottom, area.y(), (long) area.y() + area.height());
      if (intersection > largest || intersection == largest && distance < nearest) {
        chosen = area;
        largest = intersection;
        nearest = distance;
      }
    }
    return chosen;
  }

  /** A usable segment of the title bar must remain on one work area after topology changes. */
  static boolean reachable(Rect window, Insets frame, List<WorkArea> areas) {
    long left = (long) window.x() - frame.left(),
        right = (long) window.x() + window.width() + frame.right();
    long top = (long) window.y() - frame.top(), bottom = top + Math.max(1, frame.top());
    for (var area : areas)
      if (overlap(left, right, area.x(), (long) area.x() + area.width())
              >= Math.min(160, area.width())
          && overlap(top, bottom, area.y(), (long) area.y() + area.height())
              >= Math.min(bottom - top, area.height())) return true;
    return false;
  }

  static void remember(UserConfig.Workspace workspace, Rect normal, boolean maximized) {
    workspace.x = normal.x();
    workspace.y = normal.y();
    workspace.width = normal.width();
    workspace.height = normal.height();
    workspace.maximized = maximized;
  }

  private static long overlap(long start, long end, long otherStart, long otherEnd) {
    return Math.max(0, Math.min(end, otherEnd) - Math.max(start, otherStart));
  }

  private static long gap(long start, long end, long otherStart, long otherEnd) {
    return Math.max(0, Math.max(otherStart - end, start - otherEnd));
  }

  private static long clamp(long value, long minimum, long maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static int coordinate(long value) {
    return (int) clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }
}
