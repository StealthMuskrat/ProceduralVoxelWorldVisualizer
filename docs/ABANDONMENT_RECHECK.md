# Shared-generation ownership recheck — unvalidated

W05-C11 covers cooperative cancellation and shared-subscriber isolation in specification 13.4.2, 13.5.7–13.5.8 and context closure in 13.7.4. A generation poll captures its subscribers, then queries their host-owned parent suppliers outside the ownership lock. Previously it could return “still needed” from that stale snapshot after its final subscriber had retired or its context had closed during a parent callback. The next operation could start before another checkpoint noticed the change.

SharedGeneration.abandoned now reconciles closure and the current subscriber list under the existing in-flight lock after parent polling. A closed context or empty owner stops work at that checkpoint, regardless of a stale false parent result or a changed snapshot. An open, nonempty owner keeps the existing conservative policy: a known active subscriber or changed membership preserves work; only an unchanged all-inactive snapshot abandons it. Host parent queries and public completion callbacks remain outside the lock.

Removal still uses `inFlight.remove(position, this)`. A newly admitted generation at the same chunk position therefore keeps its own map entry, even while the retired generation finishes. Future cancellation, explicit token cancellation, caller exceptional completion and context closure retain their existing public terminal outcomes. Healthy peers still receive engine-owned complete chunks. Public signatures, cache policies/budgets, arithmetic, schemas, registries and authored/archive formats do not change. The extra post-poll lock acquisition has no measured performance result.

## Deferred definitions

Four `AbandonmentRecheckTest` definitions cover last-subscriber retirement, context closure, a healthy peer admitted during the poll, and a same-position replacement after retirement. A public full-stride profiler event identifies the first completed world operation (or column prefetch). A latch then holds one parent query, which normally returns false. The controlling thread changes ownership before releasing it. No private reflection, stack matching, injected evaluator or sleep selects the boundary.

Both the module definitions and engine-JAR-only `AbandonmentRecheckConsumer` define this matrix:

| Interleaving | Cases | Required disposition |
| --- | --- | --- |
| Last subscriber: future cancel, token cancel, exceptional completion | 36 | Preserve its public terminal outcome; abandon the old generation before another operation/raw event |
| Context closes during poll | 12 | Cancel the old scope at the same checkpoint; independent context still completes |
| Healthy peer joins before original subscriber withdraws | 12 | Keep the original generation; exactly one target chunk completes |
| New same-position request after the last subscriber retires | 12 | Abandon the old owner; retain and complete the replacement |

The 72 cases combine roots 2/4, NO_CACHE/BOUNDED_MEMORY_CACHE and cached 2D/identity-transform per-voxel 2D/3D paths. Public raw event counts distinguish old extra work from required peer/replacement work. Completion counts, cache ownership and aggregate cache bytes must agree, with zero queued, in-flight, staging or collection work after completion. A request in an independent context on the same one-worker engine supplies completion ordering; the peer/replacement future is also awaited, so queue priority is not a timing assumption.

Each fixture contains two SET/ALL operations and two voxel types. Independent expected output is uniform runtime type 2. After all owning contexts/engines close, the consumer checks 168 retained chunks / 6,048 exact cells, duplicate-free occupied iteration over those same cells, occupied statistics and exact `[0,2]` palettes. Caller project JSON must remain exact. These definitions do not certify all roots/policies, every membership race, full region delivery or throughput; existing wider definitions remain necessary.

The consumer joins `CancellationOwnershipConsumer` and would write `cancellation-ownership/abandonment-recheck/abandonment-recheck-report.json`. Existing manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. No workflow changed or dispatched, earlier report dimensions stay separate, and no new report exists.

## Status

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content/metadata checks add no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. The wider W05 cache, lifecycle, scheduler, region and performance audits remain open. Publication remains pending on local main.
