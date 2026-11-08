package io.voxelcreator.editor;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Bounded owner-thread delivery; nested posts wait for a later drain and closure rejects them. */
final class UiDispatchQueue extends AbstractQueue<Runnable> implements AutoCloseable {
  static final int CAPACITY = 256, PER_FRAME = 32;
  private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
  private final int capacity, budget;
  private boolean closed, draining;

  UiDispatchQueue() {
    this(CAPACITY, PER_FRAME);
  }

  UiDispatchQueue(int capacity, int budget) {
    if (capacity < 1 || budget < 1) throw new IllegalArgumentException("Positive dispatch bounds required");
    this.capacity = capacity;
    this.budget = budget;
  }

  @Override
  public synchronized boolean offer(Runnable callback) {
    Objects.requireNonNull(callback);
    if (closed || pending.size() >= capacity) return false;
    pending.addLast(callback);
    return true;
  }

  void post(Runnable callback) {
    if (!offer(callback))
      throw new RejectedExecutionException("Editor UI callback queue is full or closed");
  }

  @Override
  public synchronized Runnable poll() { return pending.pollFirst(); }

  @Override
  public synchronized Runnable peek() { return pending.peekFirst(); }

  @Override
  public synchronized int size() { return pending.size(); }

  @Override
  public synchronized void clear() { pending.clear(); }

  @Override
  public synchronized boolean remove(Object callback) { return pending.remove(callback); }

  @Override
  public synchronized Iterator<Runnable> iterator() { return List.copyOf(pending).iterator(); }

  synchronized boolean closed() { return closed; }

  int drain(Consumer<Throwable> failure) {
    Objects.requireNonNull(failure);
    List<Runnable> batch;
    synchronized (this) {
      if (closed || draining) return 0;
      batch = new ArrayList<>(Math.min(budget, pending.size()));
      for (int i = Math.min(budget, pending.size()); i > 0; i--) batch.add(pending.removeFirst());
      draining = true;
    }
    int delivered = 0;
    try {
      for (var callback : batch) {
        if (closed()) break;
        delivered++;
        try {
          callback.run();
        } catch (Throwable rejected) {
          try {
            failure.accept(rejected);
          } catch (Throwable ignored) {
            // Error reporting cannot strand the rest of an already admitted batch.
          }
        }
      }
      return delivered;
    } finally {
      synchronized (this) { draining = false; }
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    pending.clear();
  }
}
