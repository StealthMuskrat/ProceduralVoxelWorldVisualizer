# Canonical curve controls validation

Validated implementation: `a3285a3b4cb19c29119887b8f322b562ac5c24ad`.

[Actions run 40386563688](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40386563688) uses Temurin 25.0.4+7 and Gradle 9.2.1. The required command is `clean test build dependencyNotices releaseJars`. Local execution was attempted but the Java launcher lacks `libjli.so`; local schema-family and diff checks passed.

Raw Linux JUnit reports contain 698 tests across 86 suites with zero failures, errors or skips. See [JUnit summary](curve-controls-junit.json). Two new CurveModesTest cases check historical keys/defaults, immutable vocabulary, owned registry results and complete ordered help; a new CurveContractTest case independently checks every mode's small arithmetic vectors and alias rejection. Engine/schema/editor/creation defaults now read the same vocabulary. The generated curve-modes registry is drift-checked alongside the schema without changing any accepted serialized key or project version.

CurveControlsProbe uses production controls with native text, mouse and key events. It edits min/max domain and display ranges, rejects an invalid minimum while retaining the last valid curve, changes all 11 mode choices, and edits the selected point's Y. Real context-menu Select All/Clear/Delete/Fit actions preserve endpoints and restore the interior UUID through Undo. Delete/Ctrl+Z/Ctrl+Y run through ShortcutKeys.match with curve focus, proving curve-scoped Delete/Undo/Redo rather than document actions. Curve Apply changes only the draft; one Properties Apply is checked by document Undo/Redo. See the [native report](curve-controls-native.json).

The saved final knots are (-1,0), (0.5,0.75), (2,1), all Linear, with MIRROR and display range [-2,2]. The independent Python check validates the actual saved schema, registry/schema key agreement, all mode/menu/key evidence and exact authored definition. CurveControlsConsumer loads the file with only the engine JAR and checks 363 X/Z points against independent mirrored piecewise-linear arithmetic with 2e-12 tolerance.

All previous native/rendering/source/reference probes, Windows packaged launcher and cross-platform JAR reproducibility comparison remain in the platform workflow; their authoritative status is linked above. This expands scoped GUI evidence but does not certify every control gesture, custom DPI/layout, restart, complete manual acceptance, Windows 11 hardware or production release.
