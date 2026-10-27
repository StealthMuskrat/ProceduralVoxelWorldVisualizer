# Entered Inspector coordinates and delayed World picks

Validated implementation: `1ba40c9cda1513d66d1232195da81e562acfccb9`.

[Actions run 40528208359](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40528208359) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform reproducibility using Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Raw Linux JUnit remains **769 tests across 97 suites, zero failures/errors/skips**; see [summary](inspector-draft-junit.json). The existing cancellation test now runs for both request kinds; the added coverage is native integration.

The [raw format-3 report](inspector-draft-native.json) records edited (30,2,2), restored (29,2,2), rejection of a late World hit, restoration without work, pending-coordinate cancellation, unchanged Logs and actual native Inspector closure. Both platform logs contain INSPECTOR_COORDINATE_DRAFT_PASS. The [saved fixture](inspector-draft-project.json) retains the three-stage plane/fill/CLEAR project and intentional off-screen failure branch used by the previous coordinate tests.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schemas/fixtures, Python syntax, independent replay of downloaded reports and diff checks passed.

XYZ is an editable Inspector coordinate, while the current trace is an immutable accepted result. A visible notice now names the displayed trace's coordinate when these differ. Use Traced Coordinate restores the three integers without a new trace, camera movement or preview generation. Restoring also cancels/reclaims pending coordinate work. The old trace and bounded Logs history are preserved.

Previously, XYZ edits canceled explicit coordinate work but left a view-bound World pick current. A delayed hit could overwrite entered coordinates or replace the displayed trace after the edit. Every XYZ edit now invalidates both request kinds. Inspector closure similarly invalidates World picks even when no explicit coordinate job is pending, preventing a late hit from reopening the window. Existing coordinate job cancellation/retained-result behavior is unchanged.

InspectorRequestsTest exercises explicit cancellation and a new valid intent for both view-bound and coordinate request kinds. No new JUnit methods are added in this batch; production native interactions provide the additional integration evidence.

WorldLodPickingProbe extends its isolated three-stage fixture after the coordinate lifecycle checks:

- Both editor workers are held while an actual World image click queues a solid fixed hit at (3,0,0). The real Inspect Coordinate control opens the Inspector and actual XYZ typing enters (30,2,2). The untracked World runnable remains queued, then finishes after worker release; its publication must be rejected, preserving typed XYZ and the prior trace at (29,2,2).
- The displayed-trace mismatch notice is submitted. A real Use Traced Coordinate click restores (29,2,2), hides that notice and retains the same TraceSnapshot identity, Logs count/bytes and preview submission timestamp.
- Another entered coordinate is queued through Trace Coordinate. Use Traced Coordinate cancels it and releases the queued Future without another trace publication.
- A delayed World pick remains queued while the actual Inspector title-bar close button is clicked. After workers resume, the window stays closed and the accepted trace/Logs remain unchanged.

The report records edited/restored integers and lifecycle flags; Python independently checks them alongside the existing saved-project, proxy, refinement and coordinate-task observations. Authored JSON, revision and document history remain unchanged.

This is native GL/ImGui widget and production engine evidence at the fixture's default scale. Physical OS input, arbitrary edit/closure/load timing, all layouts/DPI, full GUI acceptance and Windows 11 hardware remain separate. No engine API, project/schema/archive or numerical-generation format changes occur.
