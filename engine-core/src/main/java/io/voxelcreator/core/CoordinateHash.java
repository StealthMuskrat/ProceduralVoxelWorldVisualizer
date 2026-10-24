package io.voxelcreator.core;

import java.util.UUID;

/** SplitMix64 finalizer; unsigned high 53 bits map exactly to [0,1). */
public final class CoordinateHash {
  public static long mix(long x) {
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }

  public static long seed(long project, UUID uuid, long offset) {
    return mix(
        project
            ^ mix(uuid.getMostSignificantBits())
            ^ Long.rotateLeft(mix(uuid.getLeastSignificantBits()), 17)
            ^ mix(offset));
  }

  public static long hash(long seed, int x, int y, int z) {
    return mix(
        seed
            ^ mix((long) x * 0x9e3779b97f4a7c15L)
            ^ Long.rotateLeft(mix((long) y * 0xc2b2ae3d27d4eb4fL), 21)
            ^ Long.rotateLeft(mix((long) z * 0x165667b19e3779f9L), 42));
  }

  public static double probability(long seed, int x, int y, int z) {
    return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
  }
}
