package io.voxelcreator.render;

/** Render-thread presentation receipt; texture completion alone need not release the retained frame. */
final class VolumeDisplayGate {
  private boolean staged, requireAcceptance, blocked;
  private long version;

  void staged(boolean requireAcceptance) {
    staged = true;
    this.requireAcceptance = requireAcceptance;
  }

  void committed(long version) {
    this.version = version;
    blocked = requireAcceptance;
    staged = requireAcceptance = false;
  }

  boolean accept(long version) {
    if (!blocked || staged || this.version != version) return false;
    blocked = false;
    return true;
  }

  void cancel() {
    staged = requireAcceptance = false;
  }

  void clear() {
    staged = requireAcceptance = blocked = false;
    version = 0;
  }

  boolean blocked() {
    return blocked;
  }
}
