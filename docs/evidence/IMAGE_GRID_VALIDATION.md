# Bounded 2D grid geometry validation

Validated implementation: `fb27f8257177b5d8ad1542993eb82bb50825bc67`.

[Actions run 40410736124](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40410736124) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. The required local command was attempted but Java lacks `libjli.so`; local schema families and diff checks passed.

Raw Linux JUnit reports contain 713 tests in 88 suites with zero failures, errors or skips; see [JUnit summary](image-grid-junit.json). Four new NoiseImageGridTest cases compare ordinary coordinates against independently enumerated world multiples, verify bounded ordered finite geometry at positive/negative 1e20, 1e300 and Double.MAX_VALUE, check periodic translation/reflection/owned arrays, and reject malformed/overflowing grids.

The historical world-coordinate increment can stop advancing when its spacing is smaller than the center's representable increment. Production drawing now uses at most 128 integer-indexed, origin-relative line positions per axis. It preserves ordinary decade spacing and world-zero alignment. At extreme centers, alignment follows the finite double remainder and is a display approximation. Geometry does not enter sampling/cache keys or project state. The context menu toggles the existing shared grid preference without marking previews dirty.

The existing native editor, bundled/packaged Windows launcher, image row/overlap and engine-only reference checks run in the same workflow. The menu compiles against the production binding; this batch does not claim an automated mouse-click audit of the new toggle or a full extreme-coordinate native GUI scenario. Full manual GUI/DPI, Windows 11 hardware and complete A27 acceptance remain pending.
