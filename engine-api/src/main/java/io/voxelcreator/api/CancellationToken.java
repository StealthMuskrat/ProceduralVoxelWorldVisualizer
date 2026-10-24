package io.voxelcreator.api;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final java.util.function.BooleanSupplier parent;

  public CancellationToken() {
    this(() -> false);
  }

  public CancellationToken(java.util.function.BooleanSupplier parent) {
    this.parent = parent;
  }

  public boolean cancel() {
    return cancelled.compareAndSet(false, true);
  }

  public boolean isCancelled() {
    return cancelled.get() || parent.getAsBoolean();
  }

  public void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Generation cancelled");
  }
}
