package io.voxelcreator.editor;

import java.util.*;

/** One-shot browser reveal after accepted navigation; ordinary selection does not request it. */
final class ResourceReveal {
  record Ticket(Object lifetime, UUID resource) {}

  private Ticket pending;

  void request(Object lifetime, UUID resource) {
    pending = new Ticket(Objects.requireNonNull(lifetime), Objects.requireNonNull(resource));
  }

  Ticket current(Object lifetime, UUID selected) {
    if (pending != null && (pending.lifetime() != lifetime || !pending.resource().equals(selected)))
      pending = null;
    return pending;
  }

  boolean consume(Ticket ticket, Object lifetime, UUID selected) {
    if (ticket == null || pending != ticket || current(lifetime, selected) != ticket) return false;
    pending = null;
    return true;
  }
}
