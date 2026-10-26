# Exact 2D image row cache validation

Validated implementation: `99c9ca490a4d83d143952ed0c8fe40d277066af0`.

[Actions run 40392549589](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40392549589) runs Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Local execution was attempted but the Java launcher lacks `libjli.so`; all local schema families and diff checks passed.

Raw Linux JUnit reports contain 705 tests in 87 suites, with zero failures, errors or skips. The seven new cases cover original coordinate arithmetic at fractional/large/signed-zero inputs, exact row reuse, source/axis/step/force invalidation, LRU and byte/zero budgets, immediate budget reduction, failed/cancelled row ownership and stale-source concurrency. See [JUnit summary](image-rows-junit.json).

The production headless reference records eight 256-by-256 views: initial, recolor, vertical pan, return, horizontal pan, zoom, force and source change. Their sampled-row counts are 256, 0, 1, 0, 256, 256, 256, 256. Independent Python recomputes every raw-double digest, packed-color digest and 64-bin histogram, checks unchanged raw digests through recolor/return, and checks declared retained bytes against the 4 MiB ceiling. See [raw reference report](image-rows-reference.json). A separately compiled consumer with only the engine JAR reloads the original/changed saved projects and checks 524,288 exact sample bits across those views.

The native editor smoke separately waits for actual asynchronous completion and GL publication, then requires zero newly sampled rows for recolor, one for a matching one-step vertical pan, and zero for returning to the original view. This exercises the integrated production viewport rather than only the cache component.

The cache owns raw rows, uses exact coordinate bits, caps retention, runs field callbacks outside locks and prevents stale generations from populating newer sources. Successful completed rows can survive cancelled work; partial rows/images are not published. Existing last-valid preview behavior is retained.

This is bounded row reuse, not a full world-tile cache: horizontal/sub-row translations can resample. Full A27 acceptance, all manual GUI/DPI combinations, Windows 11 hardware performance and production sign-off remain pending. No hardware speedup claim follows from the numerical/work-count evidence.
