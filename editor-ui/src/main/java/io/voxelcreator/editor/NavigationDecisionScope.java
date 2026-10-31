package io.voxelcreator.editor;

/** One delivery per native frame, owned by the exact opening of a shared draft dialog. */
final class NavigationDecisionScope {
  record Ticket(NavigationDecisionScope owner, long version, int frame) {}

  private long version;
  private int deliveredFrame = Integer.MIN_VALUE;
  private boolean active;

  void open() {
    version++;
    active = true;
  }

  void close() {
    active = false;
  }

  boolean active() {
    return active;
  }

  long version() {
    return version;
  }

  Ticket capture(int frame) {
    if (!active) throw new IllegalStateException("No active draft dialog");
    return new Ticket(this, version, frame);
  }

  boolean owns(Ticket ticket) {
    return ticket != null && ticket.owner() == this && active && ticket.version() == version;
  }

  boolean consume(Ticket ticket, int frame) {
    if (!owns(ticket) || ticket.frame() != frame || deliveredFrame == frame) return false;
    deliveredFrame = frame;
    return true;
  }
}
