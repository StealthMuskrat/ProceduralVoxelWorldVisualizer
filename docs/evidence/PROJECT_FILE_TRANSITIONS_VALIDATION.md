# Owned project file transitions

Validated implementation: `59b34d5b50f3ae79467bd551357cfe4df6805b79`.

[Actions run 40559883689](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40559883689) passed Linux, Windows and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Both platform builds passed `clean test build dependencyNotices releaseJars`, the prior native workflows, bundled/packaged launcher checks and the new PROJECT_FILES_NATIVE_PASS / PROJECT_FILES_PASS checks. Raw Linux JUnit contains **807 tests across 104 suites, zero failures/errors/skips**; see [summary](project-files-junit.json).

Previously, project path construction could throw outside file error handling, and a failed Save As retained the pending New/Open/Exit action. Retrying later could unexpectedly execute that action. Project commands now use one owned transition controller and shared production guard/path dialogs. Failed or canceled saves abort the action. Malformed import/export path construction also moved inside its existing error handler. See [behavior contract](../PROJECT_FILE_TRANSITIONS.md).

Nine controller tests cover every intent, preservation of draft/project/selection/path/revision/history on cancel/failure, malformed/blank/missing paths, failed replacement/temp cleanup, one-time successful transitions, failed-open retention and retry, command ownership and clean transitions. The native fixture uses real ImGui mouse, key and text events against the production ProjectFileDialog with real filesystem reads/writes. The [raw 20-case report](project-files-native.json) records:

- All nine New/Open/Exit × Save/Don't Save/Cancel choices. Save first writes seed 41 and the committed `owned` noise ID, preserving the unapplied `unapplied` draft. New creates an empty clean project; Open commits the separate seed-99 candidate; Exit invokes its callback exactly once. Cancel leaves the entire prior session untouched.
- All three Cancel actions after the guard's Save opens its path dialog. Guard and path Escape also abort. Competing commands cannot replace an existing guard.
- A nonempty destination directory rejects each required Save As. Its sentinel remains unchanged. Fixing the path saves seed 41, closes the dialog and invokes neither replacement nor exit. The original transition stays aborted.
- Blank, missing and malformed Open attempts retain the active dirty document, path, selected UUID, same draft object, revision and history. A subsequent valid Open commits once and clears prior selection/multi-selection/draft/history.
- Failed save to an existing named destination reports the error and aborts Exit. The session stays dirty and the destination sentinel remains intact.
- Save As cancellation preserves the clean document's saved path. Immediately requesting the same dialog again before an idle draw reopens it correctly. A subsequent clean Exit bypasses the guard.

The [saved source document](project-files-saved.json) and [incoming candidate](project-files-incoming.json) retain the distinct committed seed/resource values. All six Save/retry outputs and failure sentinels are available in the run's linux-evidence artifact. Independent Python reads actual saved JSON, recomputes the 20 expected final states/callback counts and verifies retained destination contents and absence of temporary save siblings. Local replay of downloaded artifacts passed every prior picking/navigation workflow and the new check.

The first source run was superseded while code review corrected immediate same-kind dialog reopening. The final run passed unchanged assertions. No native/controller assertion was weakened.

Required local Gradle was attempted but the local Java launcher cannot load libjli.so (exit 127). Local formatting, schemas/fixtures, Python syntax, diff checks and downloaded-report replay passed. Pinned CI supplies compilation and automated/native/package evidence.

This is scoped production-dialog and file-I/O evidence. The fixture counts Exit callbacks; Desktop wires that callback to GLFW and routes native close to the same controller. Physical window-manager close, physical keyboard/mouse input, every filesystem permission/atomic-move fault, the complete GUI/DPI matrix and Windows 11 hardware acceptance remain separate. No engine API, schema, archive, project format or model computation changed. A05 remains PARTIAL.
