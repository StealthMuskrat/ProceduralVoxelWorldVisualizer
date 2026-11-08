package io.voxelcreator.editor;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Prepare matching metadata before GPU changes and revoke later steps after callback reentry. */
final class SceneStartup {
  private SceneStartup() {}

  static <P> boolean apply(
      BooleanSupplier current, Supplier<P> prepare, Consumer<P> activate,
      Consumer<P> uploadPalette, Consumer<P> publish) {
    if (!current.getAsBoolean()) return false;
    var prepared = Objects.requireNonNull(prepare.get(), "Scene preparation returned null");
    if (!current.getAsBoolean()) return false;
    activate.accept(prepared);
    if (!current.getAsBoolean()) return false;
    uploadPalette.accept(prepared);
    if (!current.getAsBoolean()) return false;
    publish.accept(prepared);
    return current.getAsBoolean();
  }
}
