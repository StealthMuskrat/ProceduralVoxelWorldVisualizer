package io.voxelcreator.editor;

import java.util.*;

/** Read-only topic navigation: filtering never silently replaces the selected topic. */
final class HelpSession {
  private AppMetadata source;
  private HelpCatalog catalog;
  private String selected;
  private final Deque<String> history = new ArrayDeque<>();

  void synchronize(AppMetadata metadata) {
    Objects.requireNonNull(metadata);
    if (source == metadata) return;
    catalog = new HelpCatalog(metadata);
    source = metadata;
    history.removeIf(id -> catalog.topic(id) == null);
    if (selected != null && catalog.topic(selected) == null) selected = null;
  }

  boolean open(String id) {
    if (catalog.topic(id) == null) return false;
    if (selected != null && !selected.equals(id)) {
      history.push(selected);
      while (history.size() > 32) history.removeLast();
    }
    selected = id;
    return true;
  }

  boolean back() {
    if (history.isEmpty()) return false;
    selected = history.pop();
    return true;
  }

  boolean canBack() {
    return !history.isEmpty();
  }

  HelpCatalog catalog() {
    return catalog;
  }

  HelpCatalog.Topic selected() {
    return selected == null ? null : catalog.topic(selected);
  }
}
