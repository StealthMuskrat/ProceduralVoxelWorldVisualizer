package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

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
      if (content.getBytes(StandardCharsets.UTF_8).length > UpdateBody.MAX_BYTES)
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
      String url = n.path("releaseUrl").asText();
      var release = new Release(version, url);
      validateRelease(release, trusted);
      return release;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static void validateRelease(Release release, Collection<String> trusted) {
    Objects.requireNonNull(release, "Metadata request returned no release");
    ReleaseVersion.parse(release.version());
    if (release.releaseUrl() == null || release.releaseUrl().length() > 4096)
      throw new IllegalArgumentException("Release information URL exceeds 4096 characters");
    if (!trusted.contains(origin(URI.create(release.releaseUrl()))))
      throw new IllegalArgumentException("Release origin is not approved");
  }

  static String decode(byte[] bytes) throws IOException {
    if (bytes.length > UpdateBody.MAX_BYTES) throw new IOException("Metadata exceeds 64 KiB");
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  static HttpResponse.BodyHandler<byte[]> handler(UpdateBody body) {
    return info -> {
      if (info.statusCode() != 200)
        body.onError(new IOException("Metadata HTTP " + info.statusCode()));
      return body;
    };
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
    var body = new UpdateBody();
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      pending =
          client.sendAsync(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(),
              handler(body));
      // This deadline includes headers and complete response-body consumption.
      var response = pending.get(12, TimeUnit.SECONDS);
      if (response.statusCode() != 200)
        throw new IOException("Metadata HTTP " + response.statusCode());
      return parse(decode(response.body()), trust);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (TimeoutException timeout) {
      throw new IOException("Metadata response did not complete within 12 seconds", timeout);
    } catch (ExecutionException rejected) {
      if (rejected.getCause() instanceof Exception cause) throw cause;
      throw rejected;
    } finally {
      if (pending != null) pending.cancel(true);
      body.cancel();
      client.shutdownNow();
    }
  }
}
