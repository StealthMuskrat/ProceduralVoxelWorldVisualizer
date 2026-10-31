package io.voxelcreator.editor;

import java.util.*;

/** Read-only topic navigation: filtering never silently replaces the selected topic. */
final class HelpSession {
  private AppMetadata source;
  private HelpCatalog catalog;
  private String selected;
  private long version;
  private final Deque<String> history = new ArrayDeque<>();

  void synchronize(AppMetadata metadata) {
    Objects.requireNonNull(metadata);
    if (source == metadata) return;
    catalog = new HelpCatalog(metadata);
    source = metadata;
    version++;
    history.removeIf(id -> catalog.topic(id) == null);
    if (selected != null && catalog.topic(selected) == null) selected = null;
  }

  boolean open(String id) {
    if (catalog.topic(id) == null) return false;
    if (!Objects.equals(selected, id)) version++;
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
    version++;
    return true;
  }

  boolean canBack() {
    return !history.isEmpty();
  }

  long version() {
    return version;
  }

  HelpCatalog catalog() {
    return catalog;
  }

  HelpCatalog.Topic selected() {
    return selected == null ? null : catalog.topic(selected);
  }
}
