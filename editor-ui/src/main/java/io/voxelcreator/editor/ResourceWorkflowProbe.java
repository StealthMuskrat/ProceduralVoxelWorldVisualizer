package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Isolated native picker, membership and export reference using explicitly generated resources. */
public final class ResourceWorkflowProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    Path output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 900);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        if (!state.change(
            p -> {
              for (int i = 0; i < 2000; i++) {
                var t = new VoxelType();
                t.id = String.format(Locale.ROOT, "type-%05d", i);
                p.voxelTypes.add(t);
              }
              var set = new Membership();
              set.id = "catalog";
              set.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
              p.voxelSets.add(set);
              for (int dimension : List.of(2, 3)) {
                var n = new Noise();
                n.id = "shared-field";
                n.dimension = dimension;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", dimension == 2 ? .625 : .25);
                (dimension == 2 ? p.noise2D : p.noise3D).add(n);
              }
            })) throw new AssertionError(state.issues);
        UUID first = state.project().voxelTypes.getFirst().uuid,
            last = state.project().voxelTypes.getLast().uuid,
            owner = state.project().voxelSets.getFirst().uuid;
        state.select("set", owner);
        ((Membership) state.draft).id = "pending-id";
        var before = ProjectIO.JSON.valueToTree(state.project());
        Object parent = state.draft;
        long history = state.document.historyBytes(), revision = state.revision;
        var picker = new ResourcePicker(state);
        var chosen = new AtomicReference<UUID>();
        var callbacks = new AtomicInteger();
        Runnable choice =
            () ->
                picker.draw(
                    "Voxel type",
                    "voxel",
                    chosen.get(),
                    "None",
                    id -> {
                      chosen.set(id);
                      callbacks.incrementAndGet();
                    });
        picker.probeOpen();
        for (int i = 0; i < 4; i++) frame(choice);
        int unfilteredPickerRows = picker.submitted();
        if (unfilteredPickerRows < 1 || unfilteredPickerRows > 30)
          throw new AssertionError("Unbounded full picker catalog");
        io.addInputCharactersUTF8("type-01999");
        for (int i = 0; i < 4; i++) frame(choice);
        int pickerRows = picker.submitted();
        if (pickerRows != 1 || callbacks.get() != 0)
          throw new AssertionError("Picker drew invalid rows or activated without selection");
        float[] center = picker.optionCenter(last);
        io.addMousePosEvent(center[0], center[1]);
        frame(choice);
        io.addMouseButtonEvent(0, true);
        frame(choice);
        io.addMouseButtonEvent(0, false);
        frame(choice);
        if (callbacks.get() != 1 || !last.equals(chosen.get()))
          throw new AssertionError("Native picker click did not select exactly once");
        if (!before.equals(ProjectIO.JSON.valueToTree(state.project()))
            || parent != state.draft
            || history != state.document.historyBytes()
            || revision != state.revision) throw new AssertionError("Picker changed the document");
        var dialog = new MembershipDialog(state, (Membership) state.draft);
        for (int i = 0; i < 3; i++) frame(() -> dialog.draw());
        int unfilteredMembershipRows = dialog.submitted();
        if (unfilteredMembershipRows < 1 || unfilteredMembershipRows > 30)
          throw new AssertionError("Unbounded full membership catalog");
        io.addInputCharactersUTF8("type-01999");
        for (int i = 0; i < 3; i++) frame(() -> dialog.draw());
        int membershipRows = dialog.submitted();
        if (membershipRows != 1) throw new AssertionError("Membership filter lost its exact match");
        io.addKeyEvent(ImGuiKey.Escape, true);
        var cancelled = new AtomicBoolean();
        frame(() -> cancelled.set(dialog.draw()));
        io.addKeyEvent(ImGuiKey.Escape, false);
        frame(() -> {});
        if (!cancelled.get()
            || !before.equals(ProjectIO.JSON.valueToTree(state.project()))
            || parent != state.draft)
          throw new AssertionError("Native membership Cancel changed state");
        var edit = new MembershipEditSession(state, (Membership) state.draft);
        edit.set(last, true);
        if (!edit.apply(state)) throw new AssertionError(state.issues);
        if (!state.project().voxelSets.getFirst().id.equals("catalog")
            || !((Membership) state.draft).id.equals("pending-id"))
          throw new AssertionError("Membership apply committed or lost the drafted ID");
        state.document.undo();
        state.revision++;
        if (!state.project().voxelSets.getFirst().voxelTypeUuids.equals(List.of(first)))
          throw new AssertionError("One Undo failed to restore membership");
        state.document.redo();
        state.revision++;
        var selected = new LinkedHashSet<>(List.of(owner, state.project().noise2D.getFirst().uuid));
        var export = new ResourceExportPanel(state, selected);
        for (int i = 0; i < 4; i++) frame(export::draw);
        int exportRows = export.submitted();
        if (exportRows < 1 || exportRows > 30)
          throw new AssertionError("Export choices were not clipped");
        long prepared = export.preparations();
        export.filter("catalog");
        for (int i = 0; i < 3; i++) frame(export::draw);
        if (export.submitted() != 1 || export.preparations() != prepared || prepared != 1)
          throw new AssertionError("Export filtering rebuilt the payload");
        var payload = export.prepare().resources();
        if (payload.voxelTypes.size() != 2
            || payload.voxelSets.size() != 1
            || payload.noise2D.size() != 1
            || !payload.noise3D.isEmpty())
          throw new AssertionError("Wrong export dependency closure");
        var packageFiles = new ResourcePackageFiles();
        var projectFile = output.resolve("active-project.pvproj.json");
        new ProjectIO().save(projectFile, state.project());
        byte[] projectBytes = Files.readAllBytes(projectFile);
        boolean protectedProject = false;
        try {
          packageFiles.write(projectFile, payload, projectFile);
        } catch (java.io.IOException expected) {
          protectedProject = true;
        }
        if (!protectedProject || !Arrays.equals(projectBytes, Files.readAllBytes(projectFile)))
          throw new AssertionError("Export replaced the active project file");
        var packageFile = output.resolve("selection.pvresources.json");
        packageFiles.write(packageFile, payload, projectFile);
        if (!ProjectIO.JSON
            .valueToTree(payload)
            .equals(ProjectIO.JSON.valueToTree(packageFiles.read(packageFile))))
          throw new AssertionError("Strict package file round trip changed resources");
        if (ResourcePackageFiles.suggestedLocation(projectFile).equals(projectFile))
          throw new AssertionError("Package default still targets the active project");
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("activeProjectProtected", true);
        report.put("packageRoundTrip", true);
        report.put("packageDefaultDistinct", true);
        report.put("catalogTypes", 2000);
        report.put("pickerRows", pickerRows);
        report.put("unfilteredPickerRows", unfilteredPickerRows);
        report.put("unfilteredMembershipRows", unfilteredMembershipRows);
        report.put("pickerCallbacks", callbacks.get());
        report.put("membershipRows", membershipRows);
        report.put("exportRows", exportRows);
        report.put("filteredExportRows", export.submitted());
        report.put("preparations", prepared);
        report.put("exportedTypeIds", payload.voxelTypes.stream().map(t -> t.id).toList());
        report.put("exportedMembers", payload.voxelSets.getFirst().voxelTypeUuids.size());
        report.put("activeId", state.project().voxelSets.getFirst().id);
        report.put("draftId", ((Membership) state.draft).id);
        Files.writeString(
            output.resolve("resource-workflows.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "RESOURCE_WORKFLOWS_NATIVE_PASS actual picker mouse selection / bounded filtered lists"
                + " / modal Escape Cancel / membership-only Apply / one Undo and Redo / pending ID"
                + " preservation / cached export closure");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void frame(Runnable draw) {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(800, 600, ImGuiCond.Always);
    ImGui.begin("Resource Workflow Reference");
    draw.run();
    ImGui.end();
    ImGui.render();
  }
}
