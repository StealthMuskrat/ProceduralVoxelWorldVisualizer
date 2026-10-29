package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;

/** Bounded exact text, cached feedback and live-value ownership for explicit update trust Apply. */
final class UpdateTrustSession {
  record Text(String endpoint, String origins) {}

  record Feedback(UpdateTrust.Values value, String problem) {}

  private String baseEndpoint;
  private List<String> baseOrigins;
  private Text text;
  private Feedback feedback;
  private long validations;
  private int endpointLimit, originsLimit;

  UpdateTrustSession(UserConfig user) {
    revert(user);
  }

  Text text() {
    return text;
  }

  int endpointLimit() {
    return endpointLimit;
  }

  int originsLimit() {
    return originsLimit;
  }

  void editEndpoint(String value) {
    edit(new Text(value, text.origins()));
  }

  void editOrigins(String value) {
    edit(new Text(text.endpoint(), value));
  }

  private void edit(Text next) {
    Objects.requireNonNull(next.endpoint());
    Objects.requireNonNull(next.origins());
    if (next.endpoint().length() > endpointLimit || next.origins().length() > originsLimit)
      throw new IllegalArgumentException("Update trust text exceeds the provisional editor limit");
    if (next.equals(text)) return;
    text = next;
    feedback = null;
  }

  boolean current(UserConfig user) {
    return user != null
        && user.performance != null
        && Objects.equals(baseEndpoint, user.performance.updateEndpoint)
        && baseOrigins.equals(user.performance.trustedOrigins);
  }

  boolean changed() {
    return !text.endpoint().equals(baseEndpoint)
        || !text.origins().equals(String.join("\n", baseOrigins));
  }

  long validations() {
    return validations;
  }

  Feedback feedback() {
    if (feedback != null) return feedback;
    validations++;
    try {
      feedback = new Feedback(UpdateTrust.parse(text.endpoint(), text.origins()), "");
    } catch (RuntimeException invalid) {
      String message = invalid.getMessage();
      if (message == null || message.isBlank()) message = invalid.getClass().getSimpleName();
      feedback =
          new Feedback(null, message.length() > 1024 ? message.substring(0, 1023) + "…" : message);
    }
    return feedback;
  }

  void requireDisplayed(Text displayed) {
    if (!text.equals(displayed))
      throw new IllegalStateException(
          "Displayed update trust fields differ from the private draft; correct them before"
              + " applying");
  }

  void apply(UserConfig user, Path path, Text displayed) {
    requireDisplayed(displayed);
    if (!current(user))
      throw new IllegalStateException(
          "Update trust changed elsewhere; Revert to load active values");
    var checked = feedback();
    if (checked.value() == null) throw new IllegalArgumentException(checked.problem());
    user.applyUpdateTrust(checked.value().endpoint(), checked.value().origins(), path);
    revert(user);
  }

  void revert(UserConfig user) {
    String endpoint = Objects.requireNonNull(user.performance.updateEndpoint);
    var origins = List.copyOf(user.performance.trustedOrigins);
    String raw = String.join("\n", origins);
    // Historical oversized values remain fully visible; validation blocks new publication.
    int nextEndpointLimit = Math.max(UpdateTrust.MAX_ENDPOINT, endpoint.length());
    int nextOriginsLimit = Math.max(UpdateTrust.MAX_TEXT, raw.length());
    Math.addExact(Math.multiplyExact(4, nextEndpointLimit), 1);
    Math.addExact(Math.multiplyExact(4, nextOriginsLimit), 1);
    baseEndpoint = endpoint;
    baseOrigins = origins;
    endpointLimit = nextEndpointLimit;
    originsLimit = nextOriginsLimit;
    text = new Text(endpoint, raw);
    feedback = null;
  }
}
