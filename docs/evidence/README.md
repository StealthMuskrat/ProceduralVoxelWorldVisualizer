# Verification evidence

- `clean test build dependencyNotices releaseJars`: PASS with 35 JUnit tests on Temurin 25.0.4+7 x64.
- Independent javac/java consumer using only `noise-generator-engine.jar`: PASS.
- Local native smoke: all three engine-backed viewports, explicit save/reload, screenshots with nonblank-frame checks, and exit: PASS on Temurin 25.0.5+8; OpenGL 4.5 Mesa llvmpipe.
- Draft 2020-12 project/user/app schema verification and fixtures: PASS using jsonschema 4.23.0.
- Exact scene hashes at 32³ and 64³: PASS. See [benchmark](BENCHMARK.md).
- Earlier CI run [40045036266](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40045036266): Linux and Windows Server 2025 build, independent consumer, native smoke and Windows packaging passed.
- Packaged launcher and cross-platform JAR identity are checked by the latest CI workflow; inspect that run before claiming them passed.

Raw JUnit results are under `junit/`. `linux-native-smoke.log` and `smoke-project.json` accompany the inspected World screenshot below. These tests do not certify the full A01–A36 specification or Windows 11 hardware.

![Engine-backed world viewport](world.png)
