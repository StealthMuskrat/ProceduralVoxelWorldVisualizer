package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Revision-scoped search data; drawing/scrolling never serializes the document. */
public final class ResourceSearchIndex {
  public record Hit(
      UUID uuid,
      UUID owner,
      String kind,
      String label,
      boolean enabled,
      String text,
      Set<UUID> references) {}

  private final List<Hit> all;

  public ResourceSearchIndex(ProjectData p) {
    var hits = new ArrayList<Hit>();
    for (var e : ResourceGraph.entries(p)) {
      Identity r = e.resource();
      boolean enabled =
          r instanceof Layer l ? l.enabled : r instanceof Operation op ? op.enabled : true;
      hits.add(hit(r.uuid, null, e.kind(), r.id, enabled, r));
      if (r instanceof Noise n) {
        for (var item : n.coordinateTransforms)
          hits.add(hit(item.uuid, n.uuid, "transform", n.id + " / " + item.id, item.enabled, item));
        for (var item : n.processing) {
          hits.add(
              hit(item.uuid, n.uuid, "processing", n.id + " / " + item.id, item.enabled, item));
          if (item.parameters.containsKey("curve")) {
            try {
              Curve curve = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
              for (var point : curve.points)
                hits.add(
                    hit(
                        point.uuid,
                        n.uuid,
                        "curve point",
                        n.id + " / " + item.id + " / (" + point.x + ", " + point.y + ")",
                        item.enabled,
                        point));
            } catch (RuntimeException ignored) {
            }
          }
        }
        for (var parameter : n.sharedParameters)
          hits.add(
              hit(
                  parameter.uuid,
                  n.uuid,
                  "parameter",
                  n.id + " / " + parameter.id,
                  true,
                  parameter));
      }
    }
    all = List.copyOf(hits);
  }

  private static Hit hit(
      UUID id, UUID owner, String kind, String label, boolean enabled, Object value) {
    String text =
        (label + " " + kind + " " + id + " " + ProjectIO.JSON.valueToTree(value))
            .toLowerCase(Locale.ROOT);
    return new Hit(id, owner, kind, label, enabled, text, ResourceGraph.references(value));
  }

  public List<Hit> search(String query, String kind, String state, UUID reference) {
    String text = query.toLowerCase(Locale.ROOT).strip();
    return all.stream()
        .filter(h -> kind.equals("All") || h.kind.equals(kind))
        .filter(h -> state.equals("All") || h.enabled == state.equals("Enabled"))
        .filter(h -> reference == null ? h.text.contains(text) : h.references.contains(reference))
        .toList();
  }
}
