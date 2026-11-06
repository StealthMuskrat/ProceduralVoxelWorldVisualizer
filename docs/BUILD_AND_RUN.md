# Build and run

Pinned inputs: Eclipse Temurin 25.0.4+7 x64, Gradle 9.2.1, Jackson 2.18.3, JOML 1.10.8, LWJGL 3.4.1 and imgui-java 1.89.0. Tests use JUnit 5.11.4. Gradle dependency verification records external artifact SHA-256 values; the wrapper distribution checksum is pinned. FastNoiseLite source is vendored with its original MIT notice.

Run `gradlew.bat clean test build releaseJars` on Windows, or `./gradlew clean test build releaseJars` on Linux. JARs are in `build/release/jars`. Application launch: `java --enable-native-access=ALL-UNNAMED -jar build/release/jars/ProceduralVoxelCreator.jar`.

Windows target: Windows 11 x64 with OpenGL 3.0 or newer and 2 GiB heap allowance in the launcher. Hardware and minimum performance certification remain outstanding. The Actions Windows development distribution supplies a Java runtime and Mesa software OpenGL; native driver inputs are checksum pinned and notices accompany the package. Mesa can be substantially slower than a hardware driver; no frame-rate claim is made.

The development ZIP is produced by `python scripts/package_release.py --runtime build/windows-runtime --mesa-notices build/mesa-notices --output build/packages`. Supply the runtime already smoke-tested with the JARs. Launch `windows/launcher/ProceduralVoxelCreator.cmd`. The package includes exact source, JARs, examples, fixtures, test reports and SHA256SUMS.txt. It is labeled Development until all acceptance gates pass.

Independent artifact verification, without Gradle:

```sh
mkdir -p build/independent
javac -cp build/release/jars/noise-generator-engine.jar -d build/independent example-client/src/main/java/io/voxelcreator/example/*.java
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.Example build/independent/output
```

On Windows the classpath separator is `;`. This consumer uses only the engine JAR, not editor/render modules.

The unvalidated [standalone Gradle consumer](../example-client/standalone/README.md) provides a separate build with only the engine JAR as a file dependency. Its own settings prevent including the repository's editor/render projects. From a checkout, supply the documented engine-JAR and source paths; extracted distributions include build.gradle, settings.gradle and instructions beside the existing Java sources. The precompile artifact-boundary check and all compilation/run/package commands remain deferred under the testing pause. Track remaining work in [W05-C01](REMAINING_WORK.md#confirmed-gap-and-source-change).

The explicit `--smoke` application argument creates test resources, saves and reloads a test project, visits all three engine-backed tabs, writes screenshots and exits after 480 frames. It must print THREE_ENGINE_VIEWPORTS_PASS and DESKTOP_SMOKE_COMPLETED. Normal startup creates no resources. Smoke evidence is not a substitute for every interactive acceptance scenario.

The `voxel.configDir` system property overrides the independent preference directory for testing. The normal directory is `.procedural-voxel-creator` under the user home. Projects save only on explicit commands; there is no autosave or recent-project history.

Schema checks: install `scripts/requirements-validation.txt` and run `python scripts/verify_schemas.py`. Exact world regression hashes are checked with `scripts/verify_artifacts.py`. The CI reproducibility job compares independently built Linux and Windows JAR SHA-256 manifests.

The unvalidated [renderer startup recovery](RENDERER_RECOVERY.md) contains per-viewport shader initialization failures and presents Retry Renderer in the affected tab while retaining project editing and healthy tabs. The main OpenGL 3.0/GLFW/ImGui context is still required. This is source implementation awaiting final-stage validation, not new launch/hardware evidence. See the [completion roadmap](DEVELOPMENT_ROADMAP.md) for the original release objective and current testing pause.

The [unvalidated packaging contract](DETERMINISTIC_PACKAGING.md) retains the existing command/output paths while fixing member metadata/order, checksum text and independent-example CRLF/directory ownership. ZIP members are stored without compression to avoid host compression-library differences. SHA256SUMS.txt covers all staged files except itself; BUNDLED_BINARIES_SHA256SUMS.txt inventories supplied runtime files separately. Assembly prepares complete replacements before publication, rolls back ordinary rename failures, and retains recovery copies if restoration fails. The documented final-stage unittest command and all actual assembly/launcher/reproducibility checks remain deferred under the owner pause.

An optional explicit project filename can follow the desktop JAR or bundled Windows command, for example `java --enable-native-access=ALL-UNNAMED -jar jars/ProceduralVoxelCreator.jar "C:/projects/example.pvproj.json"`. The [unvalidated explicit launch integration](EXPLICIT_PROJECT_LAUNCH.md) prepares it through background Open before the first frame, with cancellation and a retained-path retry dialog on failure. Launching without a filename remains empty; this is not automatic reopening or project history. Actual launcher/Windows 11 and new startup checks remain deferred.
