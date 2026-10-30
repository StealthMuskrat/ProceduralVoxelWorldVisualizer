package io.voxelcreator.editor;

import java.util.*;

/** UI-thread mailbox. Only explicit help requests are consumed; no document/draft ownership. */
final class HelpRequests {
  private String pending;

  void request(String id) {
    pending = Objects.requireNonNull(id);
  }

  Optional<String> take() {
    var value = Optional.ofNullable(pending);
    pending = null;
    return value;
  }
}
