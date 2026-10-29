package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Manual-only HTTPS metadata check. No redirects or executable downloads. */
public final class UpdateChecker {
  public record Release(String version, String releaseUrl) {}

  public static String origin(URI uri) {
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || uri.getRawAuthority().endsWith(":"))
      throw new IllegalArgumentException(
          "An HTTPS URL without credentials or fragment is required");
    return "https://"
        + uri.getHost().toLowerCase(Locale.ROOT)
        + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
  }

  public static Release parse(String content, Set<String> trusted) {
    try {
      if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
        throw new IllegalArgumentException("Metadata exceeds 64 KiB");
      var n =
          ProjectIO.JSON
              .reader()
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(content);
      if (n == null
          || !n.isObject()
          || n.size() != 3
          || !n.path("formatVersion").isIntegralNumber()
          || !n.path("formatVersion").canConvertToInt()
          || n.path("formatVersion").intValue() != 1
          || !n.path("version").isTextual()
          || !n.path("releaseUrl").isTextual())
        throw new IllegalArgumentException(
            "Expected metadata {formatVersion:1,version,releaseUrl}");
      String version = n.path("version").asText();
      if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))
        throw new IllegalArgumentException("Invalid version");
      String url = n.path("releaseUrl").asText();
      if (!trusted.contains(origin(URI.create(url))))
        throw new IllegalArgumentException("Release origin is not approved");
      return new Release(version, url);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static Release check(String endpoint, List<String> approved) throws Exception {
    var configuration = UpdateTrust.parse(endpoint, String.join("\n", approved));
    Set<String> trust = new HashSet<>(configuration.origins());
    URI uri = URI.create(configuration.endpoint());
    if (!trust.contains(origin(uri)))
      throw new IllegalArgumentException("Metadata origin is not approved");
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var response =
        client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var input = response.body()) {
      if (response.statusCode() != 200)
        throw new IllegalStateException("Metadata HTTP " + response.statusCode());
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536) throw new IllegalArgumentException("Metadata exceeds 64 KiB");
      return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), trust);
    }
  }
}
