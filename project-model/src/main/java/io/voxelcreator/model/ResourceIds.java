package io.voxelcreator.model;

import java.text.Normalizer;

/** Shared resource ID shape; namespace uniqueness and reserved names are checked by the owner. */
public final class ResourceIds {
  private ResourceIds() {}

  /** Checks without trimming, normalization, case folding or changing the supplied text. */
  public static boolean isCanonical(String id) {
    return id != null
        && !id.isEmpty()
        && id.equals(id.strip())
        && id.codePoints().noneMatch(Character::isISOControl)
        && Normalizer.isNormalized(id, Normalizer.Form.NFC);
  }
}
