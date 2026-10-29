package io.voxelcreator.editor;

import java.net.URI;
import java.util.*;

/** Manual-only endpoint/allowlist validation; configuring trust never performs network work. */
final class UpdateTrust {
  static final int MAX_ENDPOINT = 4096, MAX_TEXT = 32768, MAX_ORIGINS = 128;

  record Values(String endpoint, List<String> origins) {
    Values {
      origins = List.copyOf(origins);
    }
  }

  static Values parse(String endpoint, String text) {
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(text);
    if (endpoint.length() > MAX_ENDPOINT || text.length() > MAX_TEXT)
      throw new IllegalArgumentException("Update endpoint or origin text exceeds the editor limit");
    var origins = new LinkedHashSet<String>();
    int lines = 0;
    for (var line : text.lines().toList()) {
      String value = line.strip();
      if (value.isEmpty()) continue;
      if (++lines > MAX_ORIGINS)
        throw new IllegalArgumentException("At most 128 approved origins are allowed");
      final URI uri;
      try {
        uri = URI.create(value);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException(
            "Invalid approved origin on nonblank line " + lines, invalid);
      }
      String origin = UpdateChecker.origin(uri);
      if (uri.getRawQuery() != null
          || uri.getRawPath() != null
              && !uri.getRawPath().isEmpty()
              && !uri.getRawPath().equals("/"))
        throw new IllegalArgumentException(
            "Approved entries must be HTTPS origins without paths or queries (line " + lines + ")");
      origins.add(origin);
    }
    String target = endpoint.strip();
    if (!target.isEmpty()) {
      String origin = UpdateChecker.origin(URI.create(target));
      if (!origins.contains(origin))
        throw new IllegalArgumentException(
            "Approve the metadata endpoint's HTTPS origin before applying");
    }
    return new Values(target, List.copyOf(origins));
  }
}
