# Bounded diagnostic history and exact event identity

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and release acceptance remain pending; no A01–A36 gate advances.

## Capture exact targets before retaining history

Target.diagnostic previously clipped logical keys to 256 UTF-16 units and paths to 2,048 before their first use. A clipped `inputs/<name>…` could equal a different real input key, and a clipped pointer no longer represented the original structural path. Current Validation Results and inline diagnostic navigation now receive the exact logical key and pointer from the diagnostic factory. Existing UUID/logical-key authority and draft guards still apply; this does not authorize replaying historical indices.

History publication remains bounded. A target within both limits retains all its fields exactly. If either key or pointer exceeds its limit, the retained target keeps only project/resource UUIDs: control and parameter become null, pointer becomes empty and settings becomes false. The resulting Go To Problem action can reveal the current resource through its normal draft guard, but cannot activate an invented truncated field. Oversized project-only settings targets have no actionable settings destination. The original current diagnostic remains available independently; history does not retain the oversized key/path elsewhere.

| Publication input | Retained destination |
| --- | --- |
| Parameter ≤256 and pointer ≤2,048 UTF-16 units | Exact supplied target |
| Either target string exceeds its limit, resource UUID present | Resource-level target, without control/key/path |
| Either target string exceeds its limit, no resource UUID | Project context only; no settings/control action |

This is a deliberately coarser historical link, not exact field navigation for oversized destinations. Target, Entry, View and Diagnostic public component shapes are unchanged. Direct Target construction remains exact; bounded retention occurs only in EditorEvents.publish.

## Group original identities before presentation changes

The former SHA-256 fingerprint covered only the original message. Different long codes, keys or pointers could therefore become one event after clipping, sharing occurrence counts and read/action/dismiss state. Publication now fingerprints the original normalized code/message plus the complete original target before applying display bounds or resource fallback. Severity and subsystem remain exact grouping-key components. Target presence, every UUID, parameter, pointer and settings scope participate; equal fallback targets alone cannot merge distinct events.

Each string uses a signed 32-bit length prefix, with -1 for null, followed by its raw UTF-16 code units in big-endian order. Target presence and settings are separate one-byte markers. Framing distinguishes adjacent field boundaries and null/empty values. Raw units also distinguish lone surrogates that a UTF-8 replacement encoder would otherwise collapse. Fingerprinting streams into one digest without building a combined payload or allocating an input-sized byte array. The digest and bounded presentation alone remain in the store. Null code/message retain their existing UNSPECIFIED/No details supplied defaults.

Codes still retain at most 96 UTF-16 units and messages at most 8,192, using the existing ellipsis presentation. Clipping now backs up when its cut would split an existing supplementary-character pair. It does not normalize caller strings or repair already malformed Unicode. The fingerprint is private, in-memory grouping state; no persisted hash, log schema, engine API or project/User Config format changes.

Existing synchronized publication, immutable cached views, stable IDs for exact repeats, timestamps, saturated counts, recency/eviction, independent read/resolve/dismiss state and no-per-event-UI-callback policy remain. Grouping differences after a retained prefix now receive separate IDs and independent acknowledgement. The store retains neither a diagnostic nor an unbounded original Target reference.

## Deferred verification

Ten EditorEventIdentityTest definitions cover exact long diagnostic capture and a clipped-name alias; distinct long codes; long parameter suffixes and original control UUIDs; oversized pointer/resource and project-settings fallback without focus; framed boundaries/nulls/settings scope; original lone-surrogate message suffixes; supplementary-character display clipping; exact limits and null defaults; separate acknowledgement/recurrence/eviction for equal presentations; and four concurrent destination groups with 64 exact occurrences apiece. The concurrent definition describes 256 publications, not observed runtime evidence.

Existing EditorEventsTest, diagnostic navigation/selection definitions, native receipts and independent engine arithmetic replay dimensions remain unchanged. No new definition has run. Source review and git whitespace/content/metadata checks are the available verification. Remaining runtime/concurrency/native focus validation, Windows 11 and final release acceptance remain outstanding.
