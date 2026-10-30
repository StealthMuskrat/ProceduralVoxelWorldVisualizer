package io.voxelcreator.editor;

import java.util.Map;

/** Choose a correction destination without parsing, changing inheritance or replacing raw input. */
final class UiScaleProblemNavigation {
  private UiScaleProblemNavigation() {}

  static String first(
      UiScaleEditSession.Text owned,
      UiScaleEditSession.Text displayed,
      Map<String, String> problems) {
    if (!owned.global().equals(displayed.global())) return UiScaleEditSession.GLOBAL;
    for (String name : UiScalePreferences.WINDOWS)
      if (!owned.windows().get(name).equals(displayed.windows().get(name))
          || owned.enabled().contains(name) != displayed.enabled().contains(name)) return name;
    if (problems.containsKey(UiScaleEditSession.GLOBAL)) return UiScaleEditSession.GLOBAL;
    for (String name : UiScalePreferences.WINDOWS) if (problems.containsKey(name)) return name;
    return null;
  }
}
