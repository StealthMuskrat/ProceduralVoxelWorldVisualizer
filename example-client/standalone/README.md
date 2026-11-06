# Standalone headless engine consumer

Development implementation; **uncompiled and untested** under the owner's testing pause. These commands are deferred validation instructions, not reports of a successful current build.

This is a separate Gradle application. Its only library dependency is the supplied `noise-generator-engine.jar` file. It does not include the editor's Gradle settings, projects, rendering libraries or native dependencies. It compiles and runs the existing Example and companion Java consumers rather than duplicating engine code or maintaining a second example implementation.

Use the documented full Eclipse Temurin **25.0.4+7 JDK** and **Gradle 9.2.1**. Compilation needs a JDK with javac; the desktop's bundled runtime is not a substitute. The included source repository supplies the pinned Gradle wrapper. Its first use may download the checksum-pinned Gradle distribution; this example has no Maven repositories or remote library dependencies.

## From an extracted distribution

Open a terminal in `examples/independent-engine-client`. The package contains this README, build.gradle, settings.gradle and the flat Java sources. The default dependency is `../../jars/noise-generator-engine.jar`.

Windows, using the included source wrapper:

```bat
..\..\source\gradlew.bat --project-dir . run
```

With Gradle 9.2.1 installed, on either platform:

```sh
gradle --project-dir . run
```

On a Unix-like host, the included wrapper can also be called as `../../source/gradlew --project-dir . run`. The supplied Windows runtime and launcher are not used for this headless example. Java's engine classes and dependencies come only from the engine JAR.

The existing `run.cmd` remains a javac/java alternative on Windows. It writes its output to `output`; the Gradle application defaults to `build/example-output`. An optional `-PexampleOutput="path with spaces"` chooses another directory, resolved relative to this standalone project.

## From a source checkout

After the separate final-stage releaseJars build, run from the repository root:

```sh
./gradlew --project-dir example-client/standalone \
  -PengineJar=../../build/release/jars/noise-generator-engine.jar \
  -PexampleSources=../src/main/java run
```

Windows uses the same arguments on one line:

```bat
gradlew.bat --project-dir example-client/standalone -PengineJar=../../build/release/jars/noise-generator-engine.jar -PexampleSources=../src/main/java run
```

All three path properties are relative to the standalone project, not the terminal's working directory. Supply an absolute `-PengineJar="path to engine.jar"` when using a separately copied artifact. The application sources remain in the original example-client module; this build does not depend on that module's output.

## Deferred verification

`verifyEngineArtifact` runs before Java compilation and can be invoked separately. It requires the engine API class and rejects known editor/render/ImGui/LWJGL/JOML packages, including an accidentally supplied desktop JAR. This is a package-boundary check, not a complete runtime dependency or determinism certification.

The unchanged Example performs loading, compilation, direct sampling, context/chunk/streaming/cancellation and archive checks. Final validation must compile/run this standalone project from both a checkout and an extracted distribution, retain its complete console output and generated examples, and confirm the existing PASS messages and independent arithmetic expectations. No Gradle task, compiler, consumer or artifact checker has run for this addition.
