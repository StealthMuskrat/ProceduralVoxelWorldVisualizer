# Semantic ID validation — unvalidated development

This main-branch source follow-up addresses specification 3.1.3/3.1.6, 13.2.3 and 14.3.4/14.3.7/14.3.9, with A14/A21/A22 coverage definitions. No compilation, JUnit, independent consumer, checker, native probe or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

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
