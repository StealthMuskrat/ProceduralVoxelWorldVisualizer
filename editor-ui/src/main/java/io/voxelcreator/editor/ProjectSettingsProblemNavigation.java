package io.voxelcreator.editor;

import io.voxelcreator.model.Diagnostic;
import java.util.*;

/** Reveal a supported field without parsing, repairing or publishing any private input. */
final class ProjectSettingsProblemNavigation {
  private ProjectSettingsProblemNavigation() {}

  static String first(
      Map<String, String> owned,
      Map<String, String> displayed,
      List<Diagnostic> validation,
      List<Diagnostic> candidate) {
    for (String field : ProjectSettingsSession.FIELDS)
      if (!Objects.equals(owned.get(field), displayed.get(field))) return field;
    var destinations = new HashSet<String>();
    for (var problems : List.of(validation, candidate))
      for (var problem : problems) {
        if (problem.sourceUuid() != null || problem.itemUuid() != null) continue;
        String field =
            ProjectSettingsSession.field(EditorEvents.Target.diagnostic(new UUID(0, 0), problem));
        if (field != null) destinations.add(field);
      }
    for (String field : ProjectSettingsSession.FIELDS)
      if (destinations.contains(field)) return field;
    return null;
  }
}
