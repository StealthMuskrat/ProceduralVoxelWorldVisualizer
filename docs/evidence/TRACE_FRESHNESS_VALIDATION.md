# Inspector freshness and historical project scope

Validated implementation: `b42cd3a955fc3ab7092b21965f9e22de4a84d3b9`.

[Actions run 40536933401](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40536933401) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Raw Linux JUnit reports **774 tests across 98 suites, zero failures/errors/skips**; see [summary](trace-freshness-junit.json). Three new unit cases cover exact revision matching, foreign project precedence and captured identity/revision immutability.

The Inspector and retained trace panel now share one freshness classification: current, changed in the same project, or from another project. A changed same-project trace offers Refresh Displayed Trace at its captured coordinate using the current committed project. Refresh preserves entered XYZ, manual preview submission, authored JSON, document revision and Undo history. Historical trace payloads stay immutable; refreshed source links use the new captured revision. Traces from another project remain read-only, with no refresh action; Trace Coordinate explicitly evaluates entered XYZ in the current project.

The isolated native fixture uses real World-image, XYZ-field and button input with the editor executor. Its [raw report](trace-freshness-native.json) and saved [before](trace-freshness-before.json), [after](trace-freshness-after.json) and [empty project](trace-freshness-empty.json) fixtures record:

- A World hit at (3,0,0), occupied final type 1, followed by entered XYZ (7,0,0).
- An actual SET-to-CLEAR project transaction cancels a queued coordinate request without replacing the old snapshot or Logs.
- Refresh evaluates the displayed (3,0,0) to EMPTY while preserving entered (7,0,0), the mismatch notice and the manual preview. The stale layer link is unavailable before refresh and available afterward.
- New Project retains the local historical trace with a foreign-project notice and no refresh button. An explicit coordinate trace evaluates (7,0,0) to EMPTY in the new project without changing its document/history.
- Empty traces reset submitted row/step telemetry and source navigation controls, rather than retaining the preceding nonempty trace's counters.

Both platform logs contain INSPECTOR_FRESHNESS_NATIVE_PASS and INSPECTOR_FRESHNESS_PASS. Independent Python checks saved project identities, SET/CLEAR actions, constant source value, empty resources, exact coordinate/type observations and all lifecycle flags. Existing fixed/streamed/LOD picking, coordinate ownership, queue recovery and draft workflows also pass.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schema families/fixtures, Python syntax, downloaded native report replay and diff checks passed. The pinned CI toolchain supplies compilation, automated/native tests and packaging evidence.

This is scoped native GL/ImGui/editor-executor and engine evidence. Arbitrary scheduling/pressure timing, physical input, full GUI/DPI/load cases and Windows 11 hardware acceptance remain separate. No engine API, schema, archive, project format or exact generation semantics change.
