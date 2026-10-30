package io.voxelcreator.editor;

import java.util.*;

/**
 * Immutable value snapshot of one active metadata publication, with reusable search projections.
 */
final class HelpCatalog {
  record Option(String key, String label) {}

  record Topic(
      String id,
      String name,
      String tooltip,
      String guidance,
      String group,
      List<Option> options,
      List<String> related) {
    Topic {
      options = List.copyOf(options);
      related = List.copyOf(related);
    }

    String copyText(HelpCatalog catalog) {
      var text =
          new StringBuilder(name)
              .append("\nTopic: ")
              .append(id)
              .append("\nGroup: ")
              .append(group)
              .append('\n')
              .append(tooltip)
              .append("\n\n")
              .append(guidance);
      if (!options.isEmpty()) {
        text.append("\n\nOptions:");
        for (var option : options)
          text.append('\n').append(option.key()).append(" — ").append(option.label());
      }
      if (!related.isEmpty()) {
        text.append("\n\nRelated topics:");
        for (String key : related)
          text.append('\n').append(catalog.topic(key).name()).append(" [").append(key).append(']');
      }
      return text.toString();
    }
  }

  private final List<Topic> topics;
  private final Map<String, Topic> byId;
  private final Map<String, String> searchable;
  private String query;
  private List<Topic> results;
  private long builds;

  HelpCatalog(AppMetadata metadata) {
    var entries = new LinkedHashMap<String, Topic>();
    for (var entry : metadata.orderedControls()) {
      var control = entry.getValue();
      var spec = CreationRegistry.CONTROLS.get(entry.getKey());
      var options =
          spec == null
              ? List.<Option>of()
              : spec.options().stream()
                  .map(key -> new Option(key, control.options.getOrDefault(key, key)))
                  .toList();
      entries.put(
          entry.getKey(),
          new Topic(
              entry.getKey(),
              control.label,
              control.tooltip,
              control.description,
              control.group,
              options,
              List.of()));
    }
    for (var guide : HelpDefinitions.GUIDES)
      entries.put(
          guide.id(),
          new Topic(
              guide.id(),
              guide.name(),
              "Application guide",
              guide.guidance(),
              "Manual",
              List.of(),
              List.of()));
    var complete = new LinkedHashMap<String, Topic>();
    for (var topic : entries.values())
      complete.put(
          topic.id(),
          new Topic(
              topic.id(),
              topic.name(),
              topic.tooltip(),
              topic.guidance(),
              topic.group(),
              topic.options(),
              HelpDefinitions.related(topic.id()).stream()
                  .filter(id -> !id.equals(topic.id()) && entries.containsKey(id))
                  .distinct()
                  .toList()));
    byId = Collections.unmodifiableMap(complete);
    topics = List.copyOf(complete.values());
    var text = new LinkedHashMap<String, String>();
    for (var topic : topics) text.put(topic.id(), topic.copyText(this).toLowerCase(Locale.ROOT));
    searchable = Map.copyOf(text);
  }

  Topic topic(String id) {
    return byId.get(id);
  }

  List<Topic> topics() {
    return topics;
  }

  List<Topic> search(String raw) {
    String next =
        Objects.requireNonNull(raw).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (results == null || !next.equals(query)) {
      query = next;
      var words = next.isEmpty() ? List.<String>of() : List.of(next.split(" "));
      results =
          words.isEmpty()
              ? topics
              : topics.stream()
                  .filter(
                      topic ->
                          words.stream()
                              .allMatch(word -> searchable.get(topic.id()).contains(word)))
                  .toList();
      builds++;
    }
    return results;
  }

  long builds() {
    return builds;
  }
}
