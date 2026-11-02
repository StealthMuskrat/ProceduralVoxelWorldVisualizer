# Project parsing boundary — unvalidated development

This main-branch source follow-up addresses specification 2.6.5/2.6.6, 2.7.6 and 13.1.1, with A02/A05/A22 coverage definitions. No compilation, JUnit, independent consumer, checker, native probe or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

## Complete input and precise rejection

ProjectIO.parse previously read the first JSON value without requiring end-of-input, so an otherwise valid project could conceal a second value or malformed tail. Resource-package and preference readers already required a complete document. Project parsing now uses a per-call ObjectReader with FAIL_ON_TRAILING_TOKENS: one project object and legal surrounding JSON whitespace are accepted; trailing values, comments and malformed trailing text reject the entire input. The shared ObjectMapper configuration is not changed.

The format-version preflight now requires a representable signed integer before comparing it with version 2. Previously, oversized integers could wrap during the preflight comparison and be rejected later by model binding. They now consistently return UNSUPPORTED_VERSION at /formatVersion, as do unsupported representable integers and non-integral/non-integer shapes. Missing/null versions retain MISSING_FIELD; duplicate JSON keys remain INVALID_JSON.

Parser mapping errors and UUID-shape traversal now escape dynamic object-key tokens (~ to ~0, / to ~1). Array indices remain explicit. Diagnostic pointers can therefore locate a rejected unknown field or malformed nested UUID whose parent key contains either character. Root parser failures use the existing slash-root convention. This correction covers parser-generated mapping/UUID paths; it does not claim a new contract for every compiler/editor diagnostic producer.

Failed loads expose no project model. DocumentSession continues to replace its active model, path, lifetime and history only after parsing and validation succeed; failed Open leaves the old checkpoint, dirty state and transaction stacks intact. No public method signature, project format 2, saved JSON, explicit Save, migration or backup behavior changes. The headless loader and desktop Open path share this boundary.

## Deferred definitions

Seven ProjectParseBoundaryTest definitions specify nine trailing-value/text cases, exact model preservation with legal whitespace and signed numeric extremes, nine unsupported version cases including positive/negative/large wrapped values, missing/null/duplicate version contracts, escaped mapping and nested UUID paths that resolve against the rejected tree, and failed-open ownership with Undo/Redo and successful retry. The existing ProjectFileWorkflowTest failed-open matrix additionally includes a trailing object and overflowing version, retaining the private Properties draft, selection, revision and history until success.

ProjectParseConsumer compiles separately against only the engine JAR. Twenty rejected files are checked through ProjectLoader and DocumentSession for exact code/path and full active identity/path/token/checkpoint/history retention, unchanged file bytes and resolvable dynamic paths. Undo/Redo must remain exact; a whitespace-wrapped valid retry replaces the session. Independent expectations then require 42 exact scalar observations and 588 exact signed-position voxels at roots 2/4 across retained/reloaded compiled snapshots, including project-Y EMPTY clipping. Candidate JSON and a report become future artifacts.

Linux/Windows client commands and artifact paths are wired into the existing manual-only workflow without dispatch. Existing native transition probes, broad malformed-file/resource-limit/platform/Windows 11 cases and final acceptance/release gates remain pending. Main remains authoritative; no development branch was created.
