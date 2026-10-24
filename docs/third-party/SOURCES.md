# Upstream notice sources

These notices apply only to their respective third-party components; they do not grant a license to project-owned source.

- LWJGL 3.4.1: https://github.com/LWJGL/lwjgl3/blob/3.4.1/LICENSE.md
- JOML 1.10.8: https://github.com/JOML-CI/JOML/blob/1.10.8/LICENSE
- Dear ImGui 1.89.9: https://github.com/ocornut/imgui/blob/v1.89.9/LICENSE.txt
- GLFW 3.4: https://github.com/glfw/glfw/blob/3.4/LICENSE.md
- stb: https://github.com/nothings/stb/blob/master/LICENSE
- Kotlin 1.8.20: https://github.com/JetBrains/kotlin/tree/v1.8.20/license
- Gradle 9.2.1: https://github.com/gradle/gradle/blob/v9.2.1/LICENSE
- Mesa: https://gitlab.freedesktop.org/mesa/mesa/-/tree/mesa-26.2.4/licenses (the complete upstream license directory is retained; source mirror: https://github.com/chaotic-cx/mesa-mirror/tree/mesa-26.2.4/licenses). The driver archive's readme is also preserved.
- LLVM: upstream Apache-2.0 with LLVM exceptions text from https://github.com/llvm/llvm-project/blob/llvmorg-21.1.8/llvm/LICENSE.TXT. This is a notice source, not a claim that every bundled driver component uses that LLVM binary version.
- zlib: https://github.com/madler/zlib/blob/v1.3.1/LICENSE; notice source, not binary version certification.

Jackson and imgui-java notices were extracted from the exact Maven JARs. FastNoiseLite's notice remains in its vendored Java source. A bundled Temurin runtime retains its own `legal/` tree. The Windows packager preserves upstream driver notice paths rather than flattening colliding filenames.
