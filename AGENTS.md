# Repository guidance

The authoritative assignment is `docs/SPECIFICATION.md`. This repository is currently a development implementation. Read `docs/DECISIONS.md` and `docs/REQUIREMENTS_AND_TESTS.md` before changing scope or claiming conformance.

- Java 25; pinned CI toolchain Temurin 25.0.4+7; Gradle wrapper 9.2.1.
- Implement substantial, coherent batches. The owner development-phase override below defers GitHub execution; preserve explicit validation status.
- Owner development-phase instruction (2026-10-28): stop all GitHub testing until the program is fully developed. Keep implementation on a development branch; do not open PRs or update main while those events trigger CI. Maintain test definitions for later validation, and label new work unvalidated.
- When validation resumes, run `./gradlew clean test build dependencyNotices releaseJars` (Windows: `gradlew.bat`).
- Keep engine/model/storage independent of editor, native, ImGui and OpenGL dependencies.
- Preserve deterministic results across chunks, contexts, worker order and visual settings.
- Resource references are UUIDs. Visible IDs are case-sensitive NFC strings. Placements inherit missing settings live; never replace absent overrides with resolved copies.
- Project changes are explicit-save transactions. User preferences do not dirty the project.
- All generated snapshots are immutable. Failed/cancelled generation never publishes incomplete chunks.
- Update schema, registries, API and relevant contract tests together. Add independent mathematical expectations and metamorphic cases for semantic changes.
- Native smoke: `java --enable-native-access=ALL-UNNAMED -jar build/release/jars/ProceduralVoxelCreator.jar --smoke`. It explicitly creates test resources, writes screenshots and exits; normal startup must remain empty.
- Do not mark Windows 11 hardware or an A01–A36 gate passed without observed evidence. A native smoke test is not every GUI acceptance scenario.
- No open-source license grant has been supplied for project-owned source. Retain third-party notices and do not invent owner licensing terms.
