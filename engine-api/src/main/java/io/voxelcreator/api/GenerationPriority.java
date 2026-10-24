package io.voxelcreator.api;

/** Weighted queued service; priority never changes generated values or preempts running work. */
public enum GenerationPriority {
  INTERACTIVE,
  NORMAL,
  BACKGROUND
}
