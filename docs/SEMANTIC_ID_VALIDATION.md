# Semantic ID validation — unvalidated development

## Editor text preservation follow-up — unvalidated

The W01 identity audit found a separate edit-path gap (W01-C06, 3.1.2/3.1.4/3.1.6/3.2.4): F2's 256-byte ImString clipped an existing longer valid ID before any edit, and ordinary Properties text used a fixed 16,384-byte buffer. A UTF-8 boundary could also split a multibyte character. Resource creation stopped accepting text at its fixed buffer size. These were editor limits, not canonical-ID validation rules.

TextBuffers.editable enables resizing before loading the initial value and keeps the native InputText resize callback enabled for typing/paste. Resource creation, inline rename and ordinary UiFields text use it; ordinary fields include resource/item/shared-parameter IDs and pinned labels/groups. The explicitly bounded expression callback retains its existing capacity and character policy. The implementation follows the pinned [ImString 1.89.0](https://github.com/SpaiR/imgui-java/blob/v1.89.0/imgui-binding/src/main/java/imgui/type/ImString.java) and [InputText binding](https://github.com/SpaiR/imgui-java/blob/v1.89.0/imgui-binding/src/main/java/imgui/ImGui.java), whose default fixed buffer clips bytes and whose isResizable flag controls both set and native growth.

Initial capacities remain allocation hints. There is no new model ID-length limit, normalization, trimming, reference rewrite or automatic publication. Existing validation still rejects noncanonical IDs at commit. Properties text remains private until Apply, F2 uses the existing guarded transaction, and unchanged IDs add no document delta. Project format, engine semantics, UUIDs, User Config and the archive lexical contract remain unchanged.

Three deferred TextBuffersTest definitions cover exact existing ASCII/Unicode values across both former limits, repeated growth/shortening, native-resize configuration, independent buffers and unmodified invalid raw text. VoxelIdentityProbe adds three long-ID cases: 257 ASCII characters, a multibyte boundary after 255 ASCII characters, and 2,049 repetitions of é界😀 (18,441 UTF-8 bytes). Each accepts an unchanged inline ID, appends in inline and Properties inputs, checks private staging, applies, and records Undo/Redo; these cases include native growth beyond initial capacities. The independent reader reconstructs all 24 whole-project expectations from fixed strings and permits only the target ID to differ; existing 2,051-dependent, preferences and six-layout checks remain. Resource creation paste, other resource kinds, physical keyboard/clipboard and Windows 11/DPI checks remain explicit execution work.

All additions remain uncompiled and untested. No compiler, Gradle, JUnit, native probe, consumer, Python/schema/packaging checker, package, update request or workflow ran. Only source review and Git whitespace/content checks are available; baseline remains 922 tests / 120 suites and no acceptance gate advances.

## Shared semantic validation contract

The original model/storage source follow-up addresses specification 3.1.3/3.1.6, 13.2.3 and 14.3.4/14.3.7/14.3.9, with A14/A21/A22 coverage definitions. No compilation, JUnit, independent consumer, checker, native probe or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

## One lexical rule

Project validation already rejected resource IDs containing edge whitespace or ISO control characters, but VoxelRegistry and archive dictionaries checked only blankness and NFC. Standalone storage callers could therefore create and read semantic names that an editable project would reject.

ResourceIds.isCanonical is a shared, side-effect-free check for nonempty NFC text, no edge whitespace as defined by String.strip, and no ISO control code points. Validator retains its existing INVALID_ID code, JSON paths, messages and case-sensitive namespace checks. VoxelRegistry applies the same lexical rule before its existing exact EMPTY reservation, duplicate-name and UUID checks. Archive versions 1 and 2 reject invalid dictionary text with its zero-based index, and identify duplicate dictionary entries by index. Index zero must still be exact EMPTY.

No caller trims, normalizes or folds case. Canonical accented/CJK/emoji names, internal spaces, case-distinct names and lowercase empty remain legal. The generic shape helper accepts EMPTY; the owning voxel namespace reserves it, while the archive requires it at index zero. Runtime IDs, exact semantic lookup and UUID references retain their meanings. The descriptive storage registry now publishes these lexical/comparison/index-zero rules.

Public storage signatures, project format 2, binary versions 1/2, modified-UTF encoding, dictionaries, payload encoding and coverage layout are unchanged. The public ResourceIds helper is additive. Previously accepted noncanonical standalone registry/dictionary names now reject, including invalid unused archive dictionary entries. Canonical unused names may still remain unresolved; only a chunk requiring a missing semantic ID rejects decoding, without changing the supplied registry. No migration or repair is introduced.

## Deferred definitions

Three ResourceIdsTest definitions specify accepted Unicode/case/internal-space text, rejected null/empty/edge-whitespace/control/non-NFC text, unchanged project diagnostics and input ownership, and separate namespace uniqueness/EMPTY reservation.

Five SemanticIdContractTest definitions specify standalone registry rejection without source mutation, immutable exact text/UUID lookup, independently encoded invalid dictionaries in both versions at indices zero and one (48 cases), duplicate/misplaced EMPTY dictionaries, and actual Unicode/case-sensitive archive remapping with EMPTY/absent coverage and missing-ID rejection. The raw headers are constructed from the documented wire layout rather than generated through the production archive writer.

SemanticIdConsumer compiles separately against only the engine JAR. It saves/loads an exact canonical project and compiles its registry, verifies independence from later source edits, writes/reads both archive formats and checks 144 independent patterned/EMPTY cell expectations after reversing runtime registry order. It defines 14 rejected registries, 24 independently encoded rejected dictionaries, exact saved bytes and dictionary text, missing case-sensitive ID reporting and unresolved unused canonical dictionaries. Reports, projects and valid/invalid archive fixtures are retained as future artifacts.

Linux/Windows commands and artifact paths are wired into the existing manual-only workflow without dispatch. Broader Unicode/platform/filesystem fault, GUI identity workflows, Windows 11 and final acceptance/release gates remain pending. Main remains authoritative; no development branch was created.
