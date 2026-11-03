package io.voxelcreator.api;

import io.voxelcreator.model.Diagnostic;
import java.util.*;
import java.util.function.Consumer;

/** Host observations have no authority over generation, delivery policy or engine cleanup. */
final class DiagnosticListeners implements AutoCloseable {
  private static final class Entry {
    volatile Consumer<Diagnostic> listener;

    Entry(Consumer<Diagnostic> listener) {
      this.listener = listener;
    }
  }

  private final List<Entry> entries = new ArrayList<>();
  private boolean closed;

  synchronized void add(Consumer<Diagnostic> listener) {
    Objects.requireNonNull(listener);
    if (closed) throw new IllegalStateException("Engine diagnostic listeners closed");
    entries.add(new Entry(listener));
  }

  synchronized boolean remove(Consumer<Diagnostic> listener) {
    Objects.requireNonNull(listener);
    boolean removed = false;
    for (var iterator = entries.iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      // Host-defined equals/hashCode must never run inside the ownership gate.
      if (entry.listener == listener) {
        entry.listener = null;
        iterator.remove();
        removed = true;
      }
    }
    return removed;
  }

  void report(Diagnostic diagnostic) {
    List<Entry> snapshot;
    synchronized (this) {
      if (closed) return;
      snapshot = List.copyOf(entries);
    }
    for (var entry : snapshot) {
      // Removal/closure revoke captured entries until their callback is claimed here. A claimed
      // callback may finish after removal; never wait for host code or hold the registry gate.
      var listener = entry.listener;
      if (listener == null) continue;
      try {
        listener.accept(diagnostic);
      } catch (Throwable ignored) {
        // Reporting an observer failure here would recursively invoke the same host callbacks.
      }
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (var entry : entries) entry.listener = null;
    entries.clear();
  }
}
