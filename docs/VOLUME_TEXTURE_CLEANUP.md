# Volume texture cleanup ownership — unvalidated

W06-C04 covers render-thread texture ownership in 1.4.1, bounded upload cleanup in 14.1.3/14.1.5 and retention before replacement publication in 15.5.3. VolumeTextureUpload previously let candidate deletion replace the original host-write/copy failure and reset the successful-transfer counters. clear() could stop after a failed candidate deletion without attempting resident deletion. close() could also leave its direct staging-buffer reference attached after a deletion fault.

Candidate cancellation now detaches its metadata, encoded data and plan before calling Backend.delete. advance() preserves the original RuntimeException or Error when cancellation also fails, attaching a distinct cleanup failure as suppressed and avoiding self-suppression. A finally block restores that advance call's successful destination-write and copy counts. Cumulative counters already count only normally returned backend calls; partial effects inside a throwing backend call remain unknowable. A transfer failure before publication retains the previous resident and version.

clear() captures and detaches both old texture owners before the first backend call. It attempts the candidate first and the resident second even if the first deletion throws. The first fault retains identity; a distinct second fault is suppressed without reading host exception text. A deletion callback sees no old resident or candidate ownership. Newly staged work belongs to the callback's new lifetime and is not part of the captured old cleanup. close() first blocks new work and releases its staging-buffer reference in finally, even if clear() throws.

A throwing delete may fail before or after releasing its texture. The uploader attempts each detached handle once and reports the fault; it cannot guarantee physical release after a backend failure. Repeating clear()/close() does not blindly retry those names, which a backend may already have reused. Replacing a pending candidate through stage() still stops before new allocation if retiring that candidate fails; a subsequent explicit retry may proceed.

## Deferred definitions

Six VolumeTextureCleanupTest definitions use an independent CPU float-array backend and exact x + 10y + 100z + base + 0.25 texels. They define:

- Host-write failure after 50,700 successful bytes, preserving original cause, last/cumulative counters, previous resident/version and exact retained samples across RuntimeException/Error and before/after-release cleanup faults.
- GPU-copy failure after one successful 36-byte layer, preserving copy/total counters and the original resident samples while suppressing the candidate cleanup fault.
- Identical transfer/deletion Throwable identity without illegal self-suppression.
- clear()/close() attempting both captured handles across before/after-release and identical/distinct faults, with detached ownership, no repeated deletion and closed admission.
- Reentrant staging from a deletion callback, with only the old handles retired and the new exact texture later committed.
- Failed old-candidate retirement preventing replacement allocation, followed by an explicit successful retry.

The raw sample constructor uses identity encoding for these finite magnitudes; expected texels do not come from the uploader or VolumeData readback. The fixture distinguishes successful backend release from failed-before-release physical ownership rather than asserting that throwing deletion always frees resources. These definitions do not create a GL context, measure direct-buffer reclamation or certify driver behavior.

## Remaining boundary and status

Successful replacement publishes the new resident/version before deleting the previous resident. The subsequent [W06-C05 handoff fix](VOLUME_RETIREMENT_HANDOFF.md) addresses that post-publication deletion fault in source: GlViewport observes the committed version in finally and Viewports accepts matching metadata before reporting a recoverable retirement fault. This does not make all advance() failures imply retention of the old resident. Native execution, independent acceptance faults, allocation/deletion, driver recovery, pressure, scheduling and performance inventories remain open.

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, encoding/math, budgets, schemas, registries and authored formats remain unchanged. Source remains on local main with publication pending.
