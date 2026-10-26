# Expanded expression validation

Validated implementation: `7e0bf1df56a3391dc0ee88057aa233028c9387e6`.

Platform evidence: [Actions run 40365559631](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40365559631), pinned Temurin 25.0.4+7 and Gradle 9.2.1. The required command is `clean test build dependencyNotices releaseJars`. Local execution was attempted but the Java launcher lacks `libjli.so`; local schema-family and diff checks passed.

The raw Linux JUnit reports contain 667 tests across 80 suites with zero failures, errors or skips. Six new ExpressionEditSessionTest regressions check private cancellation/staging, complete-resource Apply with one Undo/Redo, incomplete text and rejected-draft preservation, changed/removed source rejection, refreshed/committed/replaced project ownership and exact Unicode/length boundaries. See [JUnit count](expanded-expression-junit.json).

ExpandedExpressionProbe calls the production expression controls/modal in an isolated native host. Real mouse/key events enter two lines, cancel with both the button and Escape, stage only text without document/history publication, Apply and Undo/Redo. The committed formula changes from `x+bias` to `x+\nbias*3`, with bias 2 and exact origin values 2 then 6. See the [native report](expanded-expression-native.json). The external ExpressionRecoveryConsumer loads the saved multiline project using only the engine JAR and checks all 81 X/Z points from -4 through 4 against independent `x+6` arithmetic.

The workflow retains all existing native/rendering/source/reference probes, packaged Windows launcher and cross-platform JAR comparison. Their authoritative run status is linked above. This is scoped development evidence: token-span selection, complete GUI/DPI behavior, Windows 11 hardware, full JVM/CPU matrix and production release acceptance remain separate.
