package io.voxelcreator.editor;

import java.util.*;

/** Presentation-only subjects, separate from project values, preferences and capture options. */
final class SettingsControlMetadata {
  record Definition(String id, String label, String tooltip, String description) {}

  static final List<Definition> DEFINITIONS =
      List.of(
          definition(
              "settings.project.seed",
              "Project Seed",
              "Set the exact signed 64-bit project seed. Private text is committed only by Project"
                  + " Settings Apply.",
              "Every seed digit is significant, including adjacent values above 2^53. Project Apply"
                  + " validates the complete candidate and creates one project transaction; saving"
                  + " remains explicit."),
          definition(
              "settings.project.minY",
              "Project Minimum Y",
              "Set the inclusive lower world Y bound as a signed 32-bit integer.",
              "The complete candidate must keep minimum Y at or below maximum Y. Bounds clip World"
                  + " generation and supply ambient project values. Apply does not silently move"
                  + " the other bound."),
          definition(
              "settings.project.maxY",
              "Project Maximum Y",
              "Set the inclusive upper world Y bound as a signed 32-bit integer.",
              "The complete candidate must keep maximum Y at or above minimum Y. Outside the"
                  + " project Y range, World voxels are EMPTY. Bounds remain private until Project"
                  + " Settings Apply."),
          definition(
              "settings.performance.workers",
              "Worker Thread Limit",
              "Choose generation worker threads for the next launch. Apply Performance preserves"
                  + " the running engine.",
              "This integer is a user preference, not project data. Its restart notice compares the"
                  + " applied setting to the launched engine. Reading Help never applies or starts"
                  + " work."),
          definition(
              "settings.performance.maxPreviewDimension",
              "Maximum Preview Dimension",
              "Limit each requested fixed preview axis. Default preview extent cannot exceed this"
                  + " limit.",
              "Limits restrict new work rather than reserving memory. Exact input remains subject"
                  + " to the complete Performance candidate validation and explicit Apply"
                  + " Performance."),
          definition(
              "settings.performance.previewSize",
              "Default Preview Extent",
              "Choose the default preview extent for the next launch, no larger than Maximum"
                  + " preview dimension.",
              "Existing fixed previews retain their requested geometry. The saved default is a user"
                  + " preference; it does not alter procedural values or reserve a preview"
                  + " allocation."),
          definition(
              "settings.performance.previewCellBudget",
              "Preview Cell Budget",
              "Limit preview cells using exact signed-long integer text, including values above"
                  + " 2^53.",
              "This is an admission limit for new preview work, not a reservation. The suggested"
                  + " slider only adjusts a bounded neighborhood; complete exact text remains"
                  + " authoritative."),
          definition(
              "settings.performance.cacheMiB",
              "Cache Memory Limit",
              "Configure the cache memory limit in MiB through Apply Performance.",
              "Cache admission and eviction preserve exact generated values. This user preference"
                  + " does not change resource definitions, point sampling or the project seed."),
          definition(
              "settings.performance.engineMiB",
              "Engine Memory Limit",
              "Choose the engine memory budget in MiB for the next launch.",
              "The running engine retains its launched budget and the dialog displays its restart"
                  + " notice. Applying preferences does not replace the engine or reserve the full"
                  + " budget immediately."),
          definition(
              "settings.performance.sceneCpuMiB",
              "Streaming CPU Memory Limit",
              "Limit streaming CPU memory per view in MiB.",
              "The scene scheduler admits new work within its existing reservations. This"
                  + " display/runtime preference never changes exact canonical generated chunks."),
          definition(
              "settings.performance.sceneGpuMiB",
              "Streaming GPU Memory Limit",
              "Limit streaming GPU data memory per view in MiB.",
              "GPU admission and display fallback affect rendering availability and quality, not"
                  + " the underlying exact voxel results."),
          definition(
              "settings.performance.uploadKiB",
              "Upload Budget Per Frame",
              "Bound display uploads in KiB per frame.",
              "Upload scheduling keeps the current valid display while accepted work transfers"
                  + " incrementally. The limit does not change scalar or voxel evaluation."),
          definition(
              "settings.performance.renderDistance",
              "Horizontal Streaming Distance",
              "Choose horizontal render distance in logical chunks, subject to resident and memory"
                  + " limits.",
              "Streaming demand remains bounded by the other applied limits. This is view demand,"
                  + " not a change to the generated world definition."),
          definition(
              "settings.performance.verticalDistance",
              "Vertical Streaming Distance",
              "Choose vertical render distance in logical chunks, subject to resident and memory"
                  + " limits.",
              "Streaming demand remains bounded by the other applied limits. Project Y clipping and"
                  + " exact chunk coordinates remain unchanged."),
          definition(
              "settings.performance.lodStart",
              "LOD Start Distance",
              "Choose the chunk distance at which display level of detail starts.",
              "LOD changes display geometry and sampling quality only. Exact engine chunks and"
                  + " direct noise samplers retain their canonical resolution and values."),
          definition(
              "settings.performance.lodQuality",
              "LOD Quality",
              "Choose display LOD quality: 0 low, 1 medium or 2 high.",
              "This integer is a rendering preference. Lower display quality never changes exported"
                  + " exact chunks, project resources or direct sampler results."),
          definition(
              "settings.performance.streamChunks",
              "Resident Streaming Chunk Limit",
              "Bound resident streaming chunks across the existing scene demand.",
              "The resident limit works with CPU/GPU memory and distance limits to reject or retire"
                  + " display work. It is not an allocation made by editing this field."),
          definition(
              "settings.capture.duration",
              "Next Capture Duration",
              "Set the next profiler capture's duration in milliseconds.",
              "Editing configures only the next explicit Start Capture. Active captures and"
                  + " prepared exports retain their own immutable options."),
          definition(
              "settings.capture.samples",
              "Next Capture Sample Limit",
              "Bound the number of retained samples for the next profiler capture.",
              "Sample retention is separate from complete work totals and series capacity. Editing"
                  + " does not start, stop, clear or export any capture."),
          definition(
              "settings.capture.series",
              "Next Capture Series Limit",
              "Bound distinct retained measurement series for the next profiler capture.",
              "The next explicit Start Capture snapshots these options. Active captures and"
                  + " prepared exports are not reconfigured by this private integer input."),
          definition(
              "settings.capture.stride",
              "Next Capture Operation Stride",
              "Sample individual OPERATION measurements at this stride in the next capture.",
              "Layer/operation work totals still time every visited placement and include column"
                  + " prefetch when enabled. The stride applies to individual OPERATION samples."),
          definition(
              "settings.capture.work",
              "Next Capture Work Totals",
              "Include complete layer/operation work totals in the next profiler capture.",
              "Work totals add capture overhead, time every visited placement and include column"
                  + " prefetch. This does not change the operation sample stride or start a"
                  + " capture; Start Capture snapshots the option."),
          definition(
              "settings.editor.opportunitiesPerSecond",
              "Regeneration Opportunities Per Second",
              HelpDefinitions.REGENERATION_RATE_TOOLTIP,
              "A complete whole decimal rate from 1 through 20 takes effect immediately. Invalid"
                  + " text retains the last valid active rate. Restore Active Rate discards only"
                  + " rejected input. Save Preferences persists active values and does not apply"
                  + " invalid rate text or private project inputs. These are opportunities for"
                  + " dirty selected data and dependencies; idle opportunities do not generate"
                  + " work. Changing the rate does not change field or voxel values."));

  private static final Set<String> IDS;

  static {
    var ids = new HashSet<String>();
    for (var definition : DEFINITIONS) ids.add(definition.id());
    IDS = Set.copyOf(ids);
  }

  private static Definition definition(
      String id, String label, String tooltip, String description) {
    return new Definition(id, label, tooltip, description);
  }

  static void contribute(Map<String, AppMetadata.Control> controls) {
    int order = 0;
    for (var definition : DEFINITIONS) {
      var control =
          new AppMetadata.Control(
              definition.label(), definition.tooltip(), definition.description());
      control.group = "Settings input guidance";
      control.order = order++;
      controls.put(definition.id(), control);
    }
  }

  static String project(String field) {
    return topic(field == null ? null : "settings.project." + field);
  }

  static String performance(PerformancePreferences.Field field) {
    return field == null ? null : topic("settings.performance." + field.key());
  }

  static String capture(ProfileCaptureInputs.Field field) {
    return field == null
        ? null
        : topic("settings.capture." + field.name().toLowerCase(Locale.ROOT));
  }

  static String topic(String id) {
    return id != null && IDS.contains(id) ? id : null;
  }

  static String tooltip(AppMetadata metadata, String id, String detail) {
    Objects.requireNonNull(topic(id), "Unknown settings guidance topic");
    var control = Objects.requireNonNull(metadata).control(id);
    String configured = control == null ? "" : Objects.requireNonNullElse(control.tooltip, "");
    return (configured.isBlank() ? "" : configured + "\n")
        + (detail == null || detail.isBlank() ? "" : detail + "\n")
        + "Right-click for Help.";
  }

  static String integerTooltip(
      AppMetadata metadata, String id, String raw, long minimum, long maximum, boolean current) {
    Objects.requireNonNull(raw);
    boolean valid = false;
    try {
      if (raw.matches("[+-]?[0-9]+")) {
        long value = Long.parseLong(raw);
        valid = value >= minimum && value <= maximum;
      }
    } catch (NumberFormatException rejected) {
      /* Keep rejected spelling visible. */
    }
    return tooltip(
        metadata,
        id,
        "Text: "
            + raw
            + "\nWhole decimal integer range ["
            + minimum
            + ", "
            + maximum
            + "]. Exact text retains every digit; the slider suggests a bounded adjustment."
            + (!valid || !current
                ? "\n"
                    + "Correct or restore rejected, stale or disagreeing input before using the"
                    + " slider."
                : ""));
  }

  private SettingsControlMetadata() {}
}
