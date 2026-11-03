package io.voxelcreator.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A one-registration lifetime: an installation racing after retirement closes itself. */
final class CancellationRegistrationSlot implements AutoCloseable {
  private static final CancellationToken.Registration CLOSED = () -> {};
  private final AtomicReference<CancellationToken.Registration> registration =
      new AtomicReference<>();

  void install(CancellationToken.Registration incoming) {
    Objects.requireNonNull(incoming);
    if (!registration.compareAndSet(null, incoming)) incoming.close();
  }

  @Override
  public void close() {
    var old = registration.getAndSet(CLOSED);
    if (old != null && old != CLOSED) old.close();
  }
}
