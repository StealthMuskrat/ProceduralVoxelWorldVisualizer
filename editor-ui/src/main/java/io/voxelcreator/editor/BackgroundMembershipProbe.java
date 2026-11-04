package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deferred actual Properties entry, member checkboxes and footer across worker/owner boundaries.
 */
final class BackgroundMembershipProbe {
  private static PropertyPanel properties;
  private static MembershipDialog members;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String kind : List.of("set", "group")) {
      for (String mode :
          kind.equals("set")
              ? List.of("success", "cancel", "escape", "retry", "edit", "raw", "close", "empty")
              : List.of("success", "empty")) {
        var directory = out.resolve(kind + "-" + mode);
        Files.createDirectories(directory);
        try (var state = new EditorState(new UserConfig(), false)) {
          require(state.change(BackgroundMembershipProbe::fixture), "Membership fixture");
          state.document.save(directory.resolve("before.json"));
          byte[] original = Files.readAllBytes(directory.resolve("before.json"));
          var resource =
              kind.equals("set")
                  ? state.project().voxelSets.getFirst()
                  : state.project().voxelGroups.getFirst();
          state.select(kind, resource.uuid);
          var draft = (Membership) state.draft;
          draft.id = "private-id";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          long seedVersion = state.seedInputs.version(state);
          var preferences = ProjectIO.JSON.valueToTree(state.user);
          Files.writeString(directory.resolve("preferences-before.json"), preferences.toString());
          var tasks = new ArrayDeque<Runnable>();
          var ui = new ArrayDeque<Runnable>();
          var fail = new AtomicBoolean(mode.equals("retry"));
          state.document.setValidator(
              p -> {
                if (fail.getAndSet(false)) throw new AssertionError("membership worker fixture");
                return NoiseCompiler.validate(p);
              });
          properties = new PropertyPanel(state, tasks::add, ui::add);
          try {
            frames(4);
            click(properties.probeMembershipOpenButton());
            members = properties.probeMembershipDialog();
            require(members != null, "Properties did not open membership editor");
            frames(3);
            UUID solid = state.project().voxelTypes.get(0).uuid,
                spare = state.project().voxelTypes.get(1).uuid,
                third = state.project().voxelTypes.get(2).uuid;
            click(members.control((mode.equals("empty") ? spare : solid).toString()));
            var expected = mode.equals("empty") ? List.<UUID>of() : List.of(spare, solid);
            require(members.session().values().equals(expected), "Exact private member choices");
            click(members.control("apply"));
            var session = members.session();
            require(session.applying() && tasks.size() == 1, "Single membership worker");
            long work = session.workVersion();
            for (int i = 0; i < 12; i++) {
              frame();
              require(
                  session.applying()
                      && session.workVersion() == work
                      && state.draft == draft
                      && resourceMembers(state, kind).equals(List.of(spare))
                      && draft.voxelTypeUuids.equals(List.of(spare))
                      && session.values().equals(expected),
                  "Pending membership published or changed private input");
            }
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            require(
                session.applying() && resourceMembers(state, kind).equals(List.of(spare)),
                "Worker published before UI delivery");
            if (mode.equals("cancel")) click(members.control("cancel"));
            else if (mode.equals("escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
              frames(3);
            } else if (mode.equals("close")) properties.close();
            else if (mode.equals("raw")) state.project().project.seed = 6;
            else if (mode.equals("edit")) {
              click(members.control(solid.toString()));
              click(members.control(third.toString()));
              expected = List.of(spare, third);
              require(
                  !session.applying() && session.values().equals(expected),
                  "Changed checkboxes did not retire old Apply");
            }
            ui.remove().run();
            frames(3);
            if (mode.equals("retry") || mode.equals("edit")) {
              require(
                  !session.closed()
                      && !session.applying()
                      && session.values().equals(expected)
                      && resourceMembers(state, kind).equals(List.of(spare))
                      && tasks.isEmpty(),
                  "Membership correction/retry state");
              if (mode.equals("retry"))
                require(
                    !session.applyFailure().isBlank() && session.issueCount() == 0,
                    "Worker failure details");
              click(members.control("apply"));
              state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
              require(
                  draft.voxelTypeUuids.equals(List.of(spare)),
                  "Retry changed parent before delivery");
              ui.remove().run();
              frames(3);
            }
            boolean applied = List.of("success", "retry", "edit", "empty").contains(mode);
            if (mode.equals("raw")) {
              require(
                  !session.closed() && !session.applying() && session.values().equals(expected),
                  "Stale membership lost choices");
              click(members.control("cancel"));
            }
            require(
                session.closed()
                    && !session.applying()
                    && properties.probeMembershipDialog() == null,
                "Membership terminal modal state");
            require(
                resourceMembers(state, kind).equals(applied ? expected : List.of(spare))
                    && draft.voxelTypeUuids.equals(applied ? expected : List.of(spare)),
                "Membership-only publication");
            require(
                state.draft == draft
                    && draft.id.equals("private-id")
                    && "-".equals(state.seedInputs.text("seedOffset"))
                    && seedVersion == state.seedInputs.version(state),
                "Membership refreshed unrelated private ID/seed input");
            state.document.save(directory.resolve("after.json"));
            if (applied) {
              var accepted = ProjectIO.JSON.valueToTree(state.project());
              state.document.undo();
              state.document.save(directory.resolve("undone.json"));
              require(resourceMembers(state, kind).equals(List.of(spare)), "Membership one Undo");
              state.document.redo();
              require(
                  accepted.equals(ProjectIO.JSON.valueToTree(state.project())), "Membership Redo");
              state.document.save(directory.resolve("redone.json"));
            }
            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(
                        original, Files.readAllBytes(directory.resolve("before.json"))),
                "Membership wrote preferences/original bytes");
            Files.writeString(
                directory.resolve("preferences.json"),
                ProjectIO.JSON.writeValueAsString(state.user));
            cases.add(
                Map.of(
                    "kind",
                    kind,
                    "mode",
                    mode,
                    "applied",
                    applied,
                    "members",
                    resourceMembers(state, kind).size(),
                    "responsiveFrames",
                    12,
                    "privateIdSeedRetained",
                    true,
                    "oneUndoRedo",
                    applied,
                    "preferencesRetained",
                    true,
                    "originalBytesRetained",
                    true));
          } finally {
            properties.close();
            frames(2);
          }
        }
      }
    }
    Files.writeString(
        out.resolve("background-membership.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  static void fixture(ProjectData p) {
    p.project.seed = 5;
    p.project.minY = -2;
    p.project.maxY = 2;
    for (String id : List.of("solid", "spare", "third")) {
      var type = new VoxelType();
      type.id = id;
      p.voxelTypes.add(type);
    }
    var set = new Membership();
    set.id = "members";
    set.voxelTypeUuids.add(p.voxelTypes.get(1).uuid);
    p.voxelSets.add(set);
    var group = new Membership();
    group.id = "group";
    group.voxelTypeUuids.add(p.voxelTypes.get(1).uuid);
    p.voxelGroups.add(group);
    var noise = new Noise();
    noise.id = "field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .125);
    p.noise2D.add(noise);
    var fill = new Layer();
    fill.id = "fill";
    fill.defaults.action = "SET";
    fill.defaults.voxelTypeUuid = p.voxelTypes.getFirst().uuid;
    var initial = new Operation();
    initial.id = "all";
    initial.noiseGeneratorUuid = noise.uuid;
    initial.interpretation = "ALL";
    fill.operations.add(initial);
    var clear = new Layer();
    clear.id = "clear-members";
    clear.defaults.action = "CLEAR";
    clear.defaults.voxelStatePredicate.kind = "IN_SET";
    clear.defaults.voxelStatePredicate.reference = set.uuid;
    var second = new Operation();
    second.id = "matched";
    second.noiseGeneratorUuid = noise.uuid;
    second.interpretation = "ALL";
    clear.operations.add(second);
    p.worldGenerator.layers.addAll(List.of(fill, clear));
  }

  static List<UUID> resourceMembers(EditorState state, String kind) {
    return (kind.equals("set")
            ? state.project().voxelSets.getFirst()
            : state.project().voxelGroups.getFirst())
        .voxelTypeUuids;
  }

  private static void click(float[] at) {
    require(at != null, "Membership control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(1400, 1000);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1000, 850, ImGuiCond.Always);
    properties.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
