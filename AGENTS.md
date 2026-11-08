# Repository guidance

The authoritative assignment is `docs/SPECIFICATION.md`. This repository is currently a development implementation. Read `docs/DECISIONS.md` and `docs/REQUIREMENTS_AND_TESTS.md` before changing scope or claiming conformance.

- Java 25; pinned CI toolchain Temurin 25.0.4+7; Gradle wrapper 9.2.1.
- Implement substantial, coherent batches. The owner development-phase override below defers GitHub execution; preserve explicit validation status.
- Owner checkpoint policy: commit every completed work batch on `main` before beginning the next batch or ending the turn. Include `[skip ci]`, verify the resulting commit and working-tree status, and explicitly report any work that could not be committed. Preserve the testing pause.
- Owner publication policy: standing permission is granted to publish every completed `main` batch to GitHub after committing. Prefer the connected GitHub plugin. If command-line Git lacks credentials, use the plugin's Git blob/tree/commit tools and a non-forced `update_ref` with the observed expected head; missing CLI credentials alone are not a publication blocker. Do not force-push or dispatch testing workflows; the testing pause remains in force.
- Plugin publication may assign a new commit ID. Verify that its tree SHA exactly matches the local committed tree, retain the original checkpoint/raw commit record in the publication message or recovery manifest, and preserve a local checkpoint ref before aligning clean local `main` with the fetched published history. Verify the final remote head, local head and working-tree status. See `docs/GITHUB_PUBLICATION.md`; report any actual publication failure.
- Owner testing pause: stop all GitHub testing until the program is fully developed. Maintain test definitions for later validation, label new work unvalidated, and do not dispatch workflows during the pause. The workflow is manual-only so main updates do not trigger testing.
- Owner branch policy: keep the complete current project on `main`. Temporary branches may be used for development stages; integrate their work into main and delete them after integration. Do not accumulate completed stage branches or keep the latest implementation only on a development branch. Earlier branch-isolation notes are historical and superseded by this instruction; the testing pause still applies.
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
