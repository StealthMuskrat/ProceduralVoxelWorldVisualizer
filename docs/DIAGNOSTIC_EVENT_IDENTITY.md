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

## Timestamp observation and publication ownership — unvalidated

EditorEvents previously removed a repeated group before calling its supplied Clock. A failing clock could remove the retained group while leaving a stale cached View. A reentrant clock could also publish, acknowledge or evict the same group, after which the outer call restored stale identity/count/action state. These are source-level failure paths in the public clock-injection constructor; this follow-up adds no observed runtime evidence.

Publication now obtains a non-null Instant before removing a group, allocating its next ID or applying retention. RuntimeExceptions and Errors from Clock.instant propagate unchanged, and a null timestamp rejects before store mutation. A failed outer observation leaves existing entries, IDs, recency, eviction and cached-view state unchanged except for independent operations performed by that clock callback. The clock remains synchronous under the existing store lock; no timeout, callback isolation or blocking bound is added.

After observation succeeds, the outer publication reads the latest retained group. Reentrant repeats contribute their counts and retain their current stable ID. Reentrant resolution/dismissal remains authoritative before the new occurrence restores unread/visible state using the ordinary explicit action-required rule. A group actually evicted by a callback starts a new ID/count when subsequently republished; the outer call cannot resurrect the evicted record. If a callback publishes another event and then throws, that nested publication remains; the rejected outer call neither rolls it back nor consumes its own new ID. Existing caller-supplied timestamp values and non-monotonic wall-clock behavior are unchanged.

Eight new EditorEventPublicationTest definitions cover failed RuntimeException/Error repeats with exact entry/view preservation and later retry; an overflowing real offset Clock without eviction/ID consumption; null timestamps for repeat/new events; reentrant repeated counts/identity; acknowledgement/resolution/dismissal visibility; eviction without resurrection; failure after nested publication; and concurrent accepted/rejected repeats. The concurrent definition specifies 256 worker attempts plus the initial success: 128 worker rejections and 129 retained successful occurrences. These are unexecuted expectations, not measured concurrency evidence.

All eight definitions remain **uncompiled and untested**. Earlier identity/navigation definitions, native receipts, independent replay dimensions, engine math, public component shapes and authored formats remain unchanged. No compiler, tests, probe, consumer, checker, packaging, update request or workflow ran. Source review and git whitespace/content/metadata checks are the available verification; the observed 922-test/120-suite baseline and all A01–A36/native/Windows 11/release gates do not advance.
