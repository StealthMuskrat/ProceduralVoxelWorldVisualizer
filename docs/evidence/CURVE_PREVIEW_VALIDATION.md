# Numerical curve preview validation

Validated implementation: `bb3ff32ecacef25a57fa58b98d892074ed13e23b`.

[Actions run 40377773898](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40377773898) uses Temurin 25.0.4+7 and Gradle 9.2.1. The required command is `clean test build dependencyNotices releaseJars`. Local execution was attempted but the Java launcher lacks `libjli.so`; local schema-family and diff checks passed.

Raw Linux JUnit reports contain 689 tests across 84 suites with zero failures, errors or skips. See [JUnit summary](curve-preview-junit.json). Five new CurvePreviewTest cases cover exact finite raw observations/endpoints, partial EXTEND overflow with later valid observations, tiny-domain REPEAT/MIRROR arithmetic failures, normalized sampling over a large finite span, invalid views and owned snapshots. The display is bounded to 513 observations, with a maximum 256-character first-failure message; invalid values are gaps, with no connecting segment across them.

CurvePreviewProbe uses the production plot with a valid Linear curve `(0,0)` to `(1,1e308)`, EXTEND, and a small display range independent of authored output. Native wheel input zooms out and exposes extension overflow. The Linux native report records 329 failed observations and retains valid observations between them. Fit restores the domain and clears failures; another zoom-out followed by Cancel preserves the exact parent draft, document JSON, revision and history. See [native report](curve-preview-native.json).

The external CurvePreviewConsumer loads the saved project using only the engine JAR. It checks 99 finite X/Z vectors from X=-1 through 1 against the raw `x*1e308` function using normalized 2e-14 tolerance, and checks six X=+/-4 failures for exact resource/item/coordinate context. Thus display recovery neither clamps the engine's huge raw values nor hides its numerical failures. ScalarCurve and engine evaluation were not changed.

The platform workflow retains all previous native/rendering/source/reference probes, packaged Windows launcher and cross-platform JAR reproducibility comparison; their authoritative status is linked above. Numeric display recovery catches arithmetic/argument errors, not unrelated programming failures. Full manual GUI/DPI/mode coverage, Windows 11 hardware and production release acceptance remain separate.
