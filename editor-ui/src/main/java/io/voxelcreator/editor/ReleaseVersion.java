package io.voxelcreator.editor;

import java.math.BigInteger;
import java.util.*;

/** SemVer precedence with arbitrary-sized numeric components; build metadata has no precedence. */
final class ReleaseVersion implements Comparable<ReleaseVersion> {
  static final int MAX_LENGTH = 256;
  private final List<BigInteger> core;
  private final List<String> prerelease;

  private ReleaseVersion(List<BigInteger> core, List<String> prerelease) {
    this.core = List.copyOf(core);
    this.prerelease = List.copyOf(prerelease);
  }

  static ReleaseVersion parse(String text) {
    if (text == null
        || text.length() > MAX_LENGTH
        || !text.matches(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"))
      throw new IllegalArgumentException(
          "Expected a semantic release version (at most 256 characters)");
    String precedence = text.split("\\+", 2)[0];
    String[] parts = precedence.split("-", 2);
    var core = Arrays.stream(parts[0].split("\\.")).map(BigInteger::new).toList();
    var pre = parts.length == 1 ? List.<String>of() : List.of(parts[1].split("\\."));
    for (String id : pre)
      if (id.matches("[0-9]+") && id.length() > 1 && id.startsWith("0"))
        throw new IllegalArgumentException(
            "Numeric prerelease identifiers cannot have leading zeroes");
    return new ReleaseVersion(core, pre);
  }

  @Override
  public int compareTo(ReleaseVersion other) {
    for (int i = 0; i < 3; i++) {
      int value = core.get(i).compareTo(other.core.get(i));
      if (value != 0) return value;
    }
    if (prerelease.isEmpty() || other.prerelease.isEmpty())
      return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
    for (int i = 0; i < Math.min(prerelease.size(), other.prerelease.size()); i++) {
      String a = prerelease.get(i), b = other.prerelease.get(i);
      boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
      int value =
          an && bn
              ? new BigInteger(a).compareTo(new BigInteger(b))
              : an != bn ? (an ? -1 : 1) : a.compareTo(b);
      if (value != 0) return value;
    }
    return Integer.compare(prerelease.size(), other.prerelease.size());
  }
}
