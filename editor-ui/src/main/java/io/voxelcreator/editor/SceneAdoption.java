package io.voxelcreator.editor;

import java.util.Objects;
import java.util.function.Function;

/** CPU source ownership transfers only through a normally constructed, still-current scene. */
final class SceneAdoption {
  private SceneAdoption() {}

  interface Owner<C> {
    boolean eligible();
    void failed();
    void publish(C scene);
  }

  record Result<C>(C scene, Throwable failure, Throwable cleanup) {}

  /** Owner settlement is callback-free; a normal factory return transfers source ownership. */
  static <S extends AutoCloseable, C extends AutoCloseable> Result<C> adopt(
      S source, C previous, Owner<C> owner, Function<S, C> factory) {
    Objects.requireNonNull(source);
    if (!owner.eligible()) return new Result<>(null, null, dispose(source));
    C candidate;
    try {
      candidate = Objects.requireNonNull(factory.apply(source), "Scene factory returned null");
    } catch (RuntimeException | Error failure) {
      if (owner.eligible()) owner.failed();
      return new Result<>(null, failure, dispose(source));
    }
    if (!owner.eligible()) return new Result<>(null, null, dispose(candidate));
    owner.publish(candidate);
    return new Result<>(candidate, null, dispose(previous));
  }

  private static Throwable dispose(AutoCloseable owner) {
    if (owner == null) return null;
    try {
      owner.close();
      return null;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return failure;
    } catch (Throwable failure) {
      return failure;
    }
  }
}
