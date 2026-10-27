# Coarse World preview feedback and Inspector recovery

Validated implementation: `84cb944b71c4b959d82785617ef4b05532ae3b70`.

[Actions run 40518152767](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40518152767) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform reproducibility using Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Raw Linux JUnit remains **763 tests across 96 suites, zero failures/errors/skips**; see [summary](lod-inspector-junit.json). This batch adds native production-widget observations rather than JUnit methods.

See the [raw native report](lod-inspector-native.json) and [saved isolated project](lod-inspector-project.json). Both proxy cases reported (29,2,2), occupied full-world type 1, coordinate-specific feedback and 11 submitted trace rows. Refinement reported (28,2,2), final EMPTY type 0, no proxy hint and 11 trace rows. Fixed/streamed miss clearing, coordinate recovery, retained Logs and scene-epoch invariance all passed. The workflow independently verifies the three-stage saved project, inclusive half-occupancy arithmetic, rows and lifecycle flags.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schema families/fixtures, Python syntax, independent replay of the downloaded reports and diff checks passed.

A fixed or streamed miss previously opened the Inspector while retaining its prior current trace. Misses now clear only the Inspector's local trace/picked coordinate and show explicit coordinate-recovery instructions. Already published trace payloads remain in bounded Logs history. A later accepted coordinate trace removes the miss/proxy feedback and reports its accepted coordinate in status.

Coarse feedback now includes integer X/Y/Z and explains that an empty exact preview cell is distinct from the full-world trace. In Only Changes, an empty preview cell can be an unchanged occupied voxel. Refinement or explicit Trace Coordinate clears the proxy message on successful publication. Existing request/revision/camera/GPU lifetime guards remain in place.

WorldLodPickingProbe runs in an isolated EditorState inside the existing native GLFW/OpenGL/ImGui probe. Its project has a 3D expression x == 28 ? 1 : -1 and three stages: SET the plane, SET everywhere, then CLEAR the plane. Project Y is 0..31. The production TracePanel is attached, actual World image clicks and Trace Coordinate button events are injected, and wheel events refine the current stream without changing its CPU epoch.

At level 1, exactly half of a 2x2x2 group is occupied, so the inclusive aggregation fills the display cell x=29 even though exact preview occupancy is at x=28. After SET and Only Changes at CLEAR both select (29,2,2) with visible coarse feedback and a full-world occupied trace. A real Trace Coordinate click clears proxy feedback. After wheel refinement, the removal ghost at (28,2,2) is selected without proxy feedback and its full trace is EMPTY. Actual TracePanel rows are submitted for all accepted hits.

An off-center streamed miss and an empty fixed Before view each clear the current Inspector trace, leave recovery feedback and preserve the Logs trace count/bytes. The actual Trace Coordinate button recovers from the streamed miss. JSON, revision and document history remain unchanged. The saved isolated project and raw report accompany the native evidence artifact. Python independently checks stage order, the expression, the half-occupied group, reported coordinates/final values, bounded trace rows and lifecycle observations.

This adds native widget/engine observations rather than new JUnit cases; existing scene/math/lifetime tests remain regression coverage. It does not certify physical OS input, every proxy pattern/threshold/material, occlusion/fog/point-sprite/DPI/load cases, the full Inspector control/acceptance matrix or Windows 11 hardware. No engine, project, archive or schema format changes occur.
