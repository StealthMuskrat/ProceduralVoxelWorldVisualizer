package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.Path;

/** Private metadata draft. Validate and persist before publishing into the running editor. */
public final class MetadataEditSession {
  private AppMetadata draft;
  private com.fasterxml.jackson.databind.JsonNode base;

  public MetadataEditSession(AppMetadata metadata) {
    revert(metadata);
  }

  public AppMetadata draft() {
    return draft;
  }

  public boolean changed() {
    return !base.equals(fingerprint(draft));
  }

  public void revert(AppMetadata metadata) {
    base = fingerprint(metadata);
    draft = AppMetadata.copy(metadata);
  }

  public AppMetadata apply(AppMetadata current, Path path) {
    if (!base.equals(fingerprint(current)))
      throw new IllegalStateException("Metadata changed elsewhere; Revert before applying");
    draft.validate();
    var candidate = AppMetadata.copy(draft);
    candidate.save(path);
    revert(candidate);
    return candidate;
  }

  private static com.fasterxml.jackson.databind.JsonNode fingerprint(AppMetadata metadata) {
    return ProjectIO.JSON.valueToTree(metadata);
  }
}
