package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred manual-only trust text, strict policy, stale ownership and persistence recovery. */
class UpdateTrustSessionTest {
  @TempDir Path directory;
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final String ORIGIN = "https://updates.example.test";

  @Test
  void canonicalOriginsAreOwnedDeduplicatedAndRetainExplicitOrder() {
    var values =
        UpdateTrust.parse(
            " " + ENDPOINT + " ",
            " HTTPS://UPDATES.EXAMPLE.TEST:443/\n"
                + ORIGIN
                + "\nhttps://releases.example.test:8443\n");
    assertEquals(ENDPOINT, values.endpoint());
    assertEquals(List.of(ORIGIN, "https://releases.example.test:8443"), values.origins());
    assertThrows(UnsupportedOperationException.class, () -> values.origins().clear());
  }

  @Test
  void invalidSchemesCredentialsPathsQueriesFragmentsAndPortsCannotExpandTrust() {
    for (var origin :
        List.of(
            "http://updates.example.test",
            "https://user:secret@updates.example.test",
            ORIGIN + "/releases",
            ORIGIN + "?token=x",
            ORIGIN + "#fragment",
            ORIGIN + ":0",
            ORIGIN + ":65536",
            ORIGIN + ":"))
      assertThrows(
          IllegalArgumentException.class, () -> UpdateTrust.parse(ENDPOINT, origin), origin);
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateTrust.parse(ENDPOINT, "https://other.example.test"));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateTrust.parse("http://updates.example.test/meta.json", ORIGIN));
  }

  @Test
  void privateTextAndValidationCacheNeverPublishAPreviousValidCandidate() {
    var user = new UserConfig();
    var session = new UpdateTrustSession(user);
    session.editEndpoint(ENDPOINT);
    session.editOrigins(ORIGIN);
    var valid = session.feedback();
    long validations = session.validations();
    session.editEndpoint(ENDPOINT);
    assertSame(valid, session.feedback());
    assertEquals(validations, session.validations());
    session.editOrigins("not an origin");
    var invalid = session.feedback();
    assertNull(invalid.value());
    assertFalse(invalid.problem().isBlank());
    assertSame(invalid, session.feedback());
    assertThrows(
        IllegalArgumentException.class,
        () -> session.apply(user, directory.resolve("blocked.json"), session.text()));
    assertEquals("", user.performance.updateEndpoint);
    assertTrue(user.performance.trustedOrigins.isEmpty());
    assertFalse(Files.exists(directory.resolve("blocked.json")));
  }

  @Test
  void provisionalBoundsAndExactDisplayedStringsBlockOldValueFallback() {
    var user = new UserConfig();
    var session = new UpdateTrustSession(user);
    session.editEndpoint(ENDPOINT);
    session.editOrigins(ORIGIN);
    var before = session.text();
    assertThrows(
        IllegalArgumentException.class,
        () -> session.editEndpoint("x".repeat(UpdateTrust.MAX_ENDPOINT + 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> session.editOrigins("x".repeat(UpdateTrust.MAX_TEXT + 1)));
    assertSame(before, session.text());
    assertThrows(
        IllegalStateException.class,
        () ->
            session.apply(
                user,
                directory.resolve("blocked.json"),
                new UpdateTrustSession.Text(" " + ENDPOINT, ORIGIN)));
    assertFalse(Files.exists(directory.resolve("blocked.json")));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateTrust.parse("", (ORIGIN + "\n").repeat(UpdateTrust.MAX_ORIGINS + 1)));
  }

  @Test
  void failedWriteRetainsExactRawTextAndCandidateUntilSuccessfulCanonicalRetry() throws Exception {
    var user = new UserConfig();
    var session = new UpdateTrustSession(user);
    session.editEndpoint(" " + ENDPOINT + " ");
    session.editOrigins(" HTTPS://UPDATES.EXAMPLE.TEST:443/\n" + ORIGIN);
    var raw = session.text();
    var feedback = session.feedback();
    var active = user.performance.trustedOrigins;
    var occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "retained");
    assertThrows(IllegalStateException.class, () -> session.apply(user, occupied, raw));
    assertSame(raw, session.text());
    assertSame(feedback, session.feedback());
    assertSame(active, user.performance.trustedOrigins);
    assertEquals("", user.performance.updateEndpoint);
    assertEquals("retained", Files.readString(occupied.resolve("keep")));
    user.workspace.width = 1234;
    user.appearance.theme = "Light";
    var target = directory.resolve("retry.json");
    session.apply(user, target, raw);
    var restored = UserConfig.read(target);
    assertEquals(ENDPOINT, restored.performance.updateEndpoint);
    assertEquals(List.of(ORIGIN), restored.performance.trustedOrigins);
    assertEquals(1234, restored.workspace.width);
    assertEquals("Light", restored.appearance.theme);
    assertEquals(new UpdateTrustSession.Text(ENDPOINT, ORIGIN), session.text());
    assertFalse(session.changed());
  }

  @Test
  void staleActiveValuesRejectUntilExplicitRevertWhileUnrelatedPreferencesMerge() throws Exception {
    var user = new UserConfig();
    var session = new UpdateTrustSession(user);
    session.editEndpoint(ENDPOINT);
    session.editOrigins(ORIGIN);
    var raw = session.text();
    user.performance.trustedOrigins.add("https://other.example.test");
    assertFalse(session.current(user));
    assertThrows(
        IllegalStateException.class,
        () -> session.apply(user, directory.resolve("blocked.json"), raw));
    assertSame(raw, session.text());
    session.revert(user);
    assertTrue(session.current(user));
    assertFalse(session.changed());
    assertEquals("https://other.example.test", session.text().origins());
    user.performance.trustedOrigins = new ArrayList<>(user.performance.trustedOrigins);
    assertTrue(session.current(user));
  }

  @Test
  void periodicSavesExcludePendingTrustAndBlankEndpointExplicitlyDisablesChecks() throws Exception {
    var user = new UserConfig();
    var session = new UpdateTrustSession(user);
    session.editEndpoint(ENDPOINT);
    session.editOrigins(ORIGIN);
    var target = directory.resolve("user.json");
    user.save(target);
    assertEquals("", UserConfig.read(target).performance.updateEndpoint);
    session.editEndpoint("");
    session.apply(user, target, session.text());
    var restored = UserConfig.read(target);
    assertEquals("", restored.performance.updateEndpoint);
    assertEquals(List.of(ORIGIN), restored.performance.trustedOrigins);
  }

  @Test
  void historicalOversizeTextIsPreservedAndInvalidActiveFieldsCannotOverwriteDraft() {
    var user = new UserConfig();
    user.performance.updateEndpoint = "x".repeat(UpdateTrust.MAX_ENDPOINT + 1);
    var session = new UpdateTrustSession(user);
    assertEquals(user.performance.updateEndpoint, session.text().endpoint());
    assertNull(session.feedback().value());
    var raw = session.text();
    user.performance.trustedOrigins = null;
    assertFalse(session.current(user));
    assertThrows(RuntimeException.class, () -> session.revert(user));
    assertSame(raw, session.text());
    assertThrows(IllegalArgumentException.class, user::validate);
    user.performance.trustedOrigins = new ArrayList<>();
    user.performance.trustedOrigins.add(null);
    assertThrows(IllegalArgumentException.class, user::validate);
  }

  @Test
  void metadataParserRejectsTrailingDocumentsVersionOverflowAndNonTextFields() {
    String valid =
        "{\"formatVersion\":1,\"version\":\"1.2.3\",\"releaseUrl\":\"https://releases.example.test/1.2.3\"}";
    var approved = Set.of("https://releases.example.test");
    assertEquals("1.2.3", UpdateChecker.parse(valid, approved).version());
    for (var text :
        List.of(
            valid + " {}",
            valid + " trailing",
            valid.replace("Version\":1", "Version\":4294967297"),
            valid.replace("\"1.2.3\"", "123"),
            valid.replace("\"https://releases.example.test/1.2.3\"", "null"),
            "null"))
      assertThrows(IllegalArgumentException.class, () -> UpdateChecker.parse(text, approved), text);
    assertThrows(
        IllegalArgumentException.class, () -> UpdateChecker.parse(" ".repeat(65537), approved));
  }
}
