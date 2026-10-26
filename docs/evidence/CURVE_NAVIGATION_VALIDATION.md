# Curve navigation validation

Validated implementation: `5e71e815a37b46f871ed2c2f3031ff493a7de09a`.

[Actions run 40375017093](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40375017093) uses Temurin 25.0.4+7 and Gradle 9.2.1. The required command is `clean test build dependencyNotices releaseJars`. Local execution was attempted but the Java launcher lacks `libjli.so`; local schema-family and diff checks passed.

The raw Linux JUnit reports contain 684 tests across 83 suites with zero failures, errors or skips. See [JUnit summary](curve-navigation-junit.json). Six new CurveViewTest cases supply analytic cursor-anchor/inverse-zoom, captured-pan baseline/rollback, repeated synchronization versus changed-domain reset, nonfinite/overflow/unrepresentable span rejection, centering/Fit and stable zoom-limit checks. Bounds are immutable and rejected navigation preserves the previous state.

CurveNavigationProbe uses the production curve plot. Real wheel input zooms at an off-center delivered pointer position while preserving its world coordinate. A middle drag continues above/right of the plot and stops on release. Another captured pan is rolled back exactly by Escape, with subsequent held-pointer movement ignored. Fit restores configured domain/display ranges. Wheel input during a zero-displacement point drag does not move the view or create authoring history. Cancel leaves the parent draft, document JSON, history bytes and revision unchanged. See [native report](curve-navigation-native.json). The native coordinate oracle uses delivered pointer positions and world-span/pixel conversion, with 1e-10 tolerance.

The saved unchanged three-knot field has domain [-2,2], display range [-3,3] and identity Linear interpolation with Clamp. CurveNavigationConsumer loads it using only the engine JAR and checks 195 X/Z samples against independent `max(-2,min(2,x))` expectations with 2e-12 tolerance, including exact knots and outside-domain inputs.

All prior native/rendering/source/reference probes, packaged Windows launcher and cross-platform JAR reproducibility comparison remain in the platform workflow; their authoritative status is linked above. This scoped evidence does not certify every domain/context-menu or resize/DPI gesture, the full manual GUI matrix, Windows 11 hardware or production release acceptance.
