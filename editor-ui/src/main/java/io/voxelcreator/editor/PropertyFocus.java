package io.voxelcreator.editor;

import java.util.*;

/** One owned focus request; scrolling and widget activation have separate lifetimes. */
final class PropertyFocus {
  private EditorEvents.Target target;
  private Object draft;
  private String parameter, acknowledged;
  private boolean submitted;

  void request(EditorEvents.Target target, Object draft) {
    cancel();
    this.target = Objects.requireNonNull(target);
    this.draft = Objects.requireNonNull(draft);
    parameter = DiagnosticNavigation.controlParameter(target);
  }

  void synchronize(UUID project, UUID resource, Object draft) {
    if (target != null
        && (!Objects.equals(target.project(), project)
            || !Objects.equals(target.resource(), resource)
            || this.draft != draft)) cancel();
  }

  void chooseControl(UUID control) {
    if (target != null && !Objects.equals(target.control(), control)) cancel();
  }

  boolean matches(UUID control, String parameter) {
    return target != null
        && parameter != null
        && acknowledged == null
        && Objects.equals(target.control(), control)
        && (Objects.equals(this.parameter, parameter)
            || parameter.endsWith("/0")
                && Objects.equals(this.parameter, parameter.substring(0, parameter.length() - 2)));
  }

  boolean submit() {
    if (target == null || acknowledged != null || submitted) return false;
    submitted = true;
    return true;
  }

  void acknowledge(String key) {
    if (target != null && submitted) acknowledged = Objects.requireNonNull(key);
  }

  String acknowledged() {
    return acknowledged;
  }

  void cancel() {
    target = null;
    draft = null;
    parameter = null;
    acknowledged = null;
    submitted = false;
  }
}
