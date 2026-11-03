package io.voxelcreator.api;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Retires an admitted subscriber before any public terminal publication or dependent callback. */
final class RequestFuture<T> extends CompletableFuture<T> {
  private final Object retirementGate = new Object();
  private Runnable retirement;
  private Throwable retirementFailure;

  RequestFuture(Runnable retirement) {
    this.retirement = Objects.requireNonNull(retirement);
  }

  private Throwable retire(Throwable requested) {
    Throwable cleanup;
    synchronized (retirementGate) {
      var action = retirement;
      if (action != null) {
        // Drop the owner closure even when the caller retains this completed future indefinitely.
        retirement = null;
        try {
          action.run();
        } catch (Throwable failure) {
          retirementFailure = failure;
        }
      }
      cleanup = retirementFailure;
    }
    if (cleanup == null) return requested;
    if (requested == null || requested == cleanup) return cleanup;
    var combined = new CompletionException("Chunk subscriber retirement failed", requested);
    combined.addSuppressed(cleanup);
    return combined;
  }

  @Override
  public boolean complete(T value) {
    var failure = retire(null);
    return failure == null ? super.complete(value) : super.completeExceptionally(failure);
  }

  @Override
  public boolean completeExceptionally(Throwable failure) {
    return super.completeExceptionally(retire(Objects.requireNonNull(failure)));
  }

  @Override
  public boolean cancel(boolean interrupt) {
    var failure = retire(null);
    if (failure != null && !isDone()) {
      super.completeExceptionally(failure);
      return false;
    }
    return super.cancel(interrupt);
  }

  @Override
  public void obtrudeValue(T value) {
    var failure = retire(null);
    if (failure == null || isDone()) super.obtrudeValue(value);
    else super.obtrudeException(failure);
  }

  @Override
  public void obtrudeException(Throwable failure) {
    Objects.requireNonNull(failure);
    var outcome = retire(failure);
    super.obtrudeException(isDone() ? failure : outcome);
  }

  @Override
  public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
    return completeAsync(supplier, defaultExecutor());
  }

  @Override
  public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
    Objects.requireNonNull(supplier);
    Objects.requireNonNull(executor);
    // The JDK's async supplier completes its result internally, bypassing complete overrides.
    // Keep its executor/rejection/skip-if-done behavior, but retire before the supplier returns.
    return super.completeAsync(
        () -> {
          T value;
          try {
            value = supplier.get();
          } catch (Throwable failure) {
            var outcome = retire(failure);
            throw outcome instanceof CompletionException completed
                ? completed
                : new CompletionException(outcome);
          }
          var failure = retire(null);
          if (failure != null) throw new CompletionException(failure);
          return value;
        },
        executor);
  }
}
