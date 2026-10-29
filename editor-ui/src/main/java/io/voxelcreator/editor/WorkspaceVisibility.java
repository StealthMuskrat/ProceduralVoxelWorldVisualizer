package io.voxelcreator.editor;

import java.util.*;

/**
 * Persistent tool visibility only; private editors, file decisions and update checks are transient.
 */
public final class WorkspaceVisibility {
  public static final List<String> WINDOWS =
      List.of("Find", "Logs", "Validation", "Help", "Notifications", "Profiler", "Themes");
  private static final Set<String> KEYS = Set.copyOf(WINDOWS);

  private WorkspaceVisibility() {}

  public static void validate(List<String> visible) {
    if (visible == null
        || visible.size() > WINDOWS.size()
        || visible.stream().anyMatch(Objects::isNull)
        || !WINDOWS.containsAll(visible)
        || new HashSet<>(visible).size() != visible.size())
      throw new IllegalArgumentException("Invalid workspace tool visibility");
  }

  static Map<String, Boolean> restore(UserConfig.Workspace workspace) {
    validate(workspace.visibleWindows);
    var values = new LinkedHashMap<String, Boolean>();
    for (var name : WINDOWS) values.put(name, workspace.visibleWindows.contains(name));
    return Collections.unmodifiableMap(values);
  }

  static void capture(UserConfig.Workspace workspace, Map<String, Boolean> values) {
    if (!values.keySet().equals(KEYS) || values.values().stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Complete tool visibility is required");
    int index = 0;
    boolean same = true;
    for (var name : WINDOWS)
      if (values.get(name)) {
        if (workspace.visibleWindows == null
            || index >= workspace.visibleWindows.size()
            || !name.equals(workspace.visibleWindows.get(index))) same = false;
        index++;
      }
    if (same && workspace.visibleWindows != null && index == workspace.visibleWindows.size())
      return;
    workspace.visibleWindows = new ArrayList<>(WINDOWS.stream().filter(values::get).toList());
  }

  static void captureSize(
      UserConfig.Workspace workspace, int width, int height, boolean iconified, boolean maximized) {
    if (iconified || maximized || width < 400 || height < 300) return;
    workspace.width = width;
    workspace.height = height;
  }
}
