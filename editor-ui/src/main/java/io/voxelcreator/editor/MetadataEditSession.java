package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.Path;
import java.util.*;

/** Private metadata draft. Validate and persist before publishing into the running editor. */
public final class MetadataEditSession {
  private AppMetadata draft;
  private com.fasterxml.jackson.databind.JsonNode base;
  private CreationValueInputs inputs;
  private long version;

  public MetadataEditSession(AppMetadata metadata) {
    revert(metadata);
  }

  public AppMetadata draft() {
    return draft;
  }

  public boolean changed() {
    return inputs.changed() || !base.equals(fingerprint(draft));
  }

  public void revert(AppMetadata metadata) {
    base = fingerprint(metadata);
    draft = AppMetadata.copy(metadata);
    inputs = new CreationValueInputs(draft);
    version++;
  }

  CreationValueInputs inputs() {
    return inputs;
  }

  public void stageBuiltIns() {
    draft = AppMetadata.defaults();
    inputs = new CreationValueInputs(draft);
    version++;
  }

  private Map<String, List<String>> raw() {
    var values = new LinkedHashMap<String, List<String>>();
    for (var id : inputs.ids()) values.put(id, inputs.text(id));
    return Map.copyOf(values);
  }

  Request capture(AppMetadata current, Map<String, List<String>> displayed) {
    inputs.requireDisplayed(displayed);
    if (!base.equals(fingerprint(current)))
      throw new IllegalStateException("Metadata changed elsewhere; Revert before applying");
    return new Request(current);
  }

  /** Owner capture only; worker preparation never reads the live metadata, draft or raw input. */
  final class Request {
    private final AppMetadata owner = draft;
    private final CreationValueInputs ledger = inputs;
    private final long revision = version;
    private final com.fasterxml.jackson.databind.JsonNode value = fingerprint(draft);
    private final Map<String, List<String>> text = raw();
    private final AppMetadata capturedActive;

    private Request(AppMetadata active) {
      capturedActive = active;
    }

    boolean current(AppMetadata current, Map<String, List<String>> displayed) {
      if (current != capturedActive
          || draft != owner
          || inputs != ledger
          || version != revision
          || !base.equals(fingerprint(current))
          || !value.equals(fingerprint(draft))
          || !inputs.retainsRaw(text)) return false;
      try {
        inputs.requireDisplayed(displayed);
        return true;
      } catch (RuntimeException changed) {
        return false;
      }
    }

    Prepared prepare(Path path, PreferenceFiles files) throws Exception {
      var candidate = ProjectIO.JSON.convertValue(value, AppMetadata.class);
      candidate.validate();
      var next = AppMetadata.copy(candidate);
      var nextInputs = new CreationValueInputs(next);
      var checkpoint = fingerprint(candidate);
      var file = files.prepare(path, candidate, ".app-config-");
      try {
        return new Prepared(this, candidate, next, nextInputs, checkpoint, file);
      } catch (RuntimeException | Error failure) {
        try {
          file.close();
        } catch (Throwable cleanup) {
          if (failure != cleanup) failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    void publish(Prepared prepared, EditorState state, Map<String, List<String>> displayed)
        throws Exception {
      if (prepared.request != this || !current(state.metadata, displayed))
        throw new IllegalStateException("Profile or input changed; Apply the current profile again");
      prepared.file.publish();
      // No validation, copying or callbacks after replacement: activate exactly the written value.
      state.metadata = prepared.active;
      draft = prepared.next;
      inputs = prepared.inputs;
      base = prepared.checkpoint;
      version++;
    }
  }

  static final class Prepared implements AutoCloseable {
    private final Request request;
    private final AppMetadata active, next;
    private final CreationValueInputs inputs;
    private final com.fasterxml.jackson.databind.JsonNode checkpoint;
    private final PreferenceFiles.Prepared file;

    private Prepared(
        Request request,
        AppMetadata active,
        AppMetadata next,
        CreationValueInputs inputs,
        com.fasterxml.jackson.databind.JsonNode checkpoint,
        PreferenceFiles.Prepared file) {
      this.request = request;
      this.active = active;
      this.next = next;
      this.inputs = inputs;
      this.checkpoint = checkpoint;
      this.file = file;
    }

    @Override
    public void close() throws Exception {
      file.close();
    }
  }

  public AppMetadata apply(AppMetadata current, Path path) {
    if (!base.equals(fingerprint(current)))
      throw new IllegalStateException("Metadata changed elsewhere; Revert before applying");
    inputs.requireValid();
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
