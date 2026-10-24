# Scheduling and cache admission

The headless engine now uses a bounded fair priority queue for asynchronous chunk generation. GenerationPriority exposes INTERACTIVE, NORMAL and BACKGROUND. Existing async methods and four-argument RegionRequest constructors default to NORMAL. New overloads accept a priority, and the five-argument RegionRequest carries priority for every chunk scheduled by that stream or collection.

```java
world.generateChunkAsync(position, GenerationPriority.INTERACTIVE);
world.generateChunkAsync(position, token, GenerationPriority.BACKGROUND);
var request = new RegionRequest(bounds, RegionRequest.FailurePolicy.CONTINUE, RegionRequest.DeliveryOrder.COMPLETION,
                                8, GenerationPriority.BACKGROUND);
```

## Queued service and sharing

When all lanes contain work, the seven-dispatch service pattern is INTERACTIVE, INTERACTIVE, NORMAL, INTERACTIVE, BACKGROUND, INTERACTIVE, NORMAL. An empty lane falls back to the highest nonempty lane. Within a lane, active world contexts take round-robin turns; each context retains FIFO ordering within that lane. Continuous interactive work therefore cannot permanently starve admitted normal/background work. With fixed finite queued scopes, each nonempty lane receives its scheduled opportunities and each scope receives a turn. This is a dispatch-count guarantee, not a wall-clock latency guarantee.

A higher-priority subscriber can promote a queued shared request without creating another generation task or changing other subscribers' futures. Promotion stays in effect until dispatch even if that higher-priority subscriber subsequently cancels. Running work is never preempted. Synchronous generation can still steal its queued shared job and run inline, including on a saturated engine worker. Executor startup tasks and inline work bypass queued ordering; fairness applies to admitted queued tasks. Renderer-specific mesh/preview queues retain their existing separate policies.

The global queue has the configured fixed capacity. Priority does not bypass that bound or evict already accepted work; a full queue rejects a new job with the existing overload behavior. Admission under unlimited retrying caller load is not a starvation guarantee for rejected requests. Cross-context caches, seeds and cancellation remain separate, and scheduling order never changes exact voxel results.

`NoiseGeneratorEngine.schedulerStats()` returns a read-only queue snapshot: size/capacity, queued scopes, counts by priority, dequeued/removed/rejected counts and successful promotions. Dequeued includes queue extraction/draining; removed includes explicit removal for cancellation or inline stealing. These are queue counters rather than total engine task counts. The Profiler displays this snapshot alongside existing engine metrics. The policy vocabulary is published in `registries/scheduler-policy.json`.

## Cancellation and ownership

Explicit CancellationToken.cancel now notifies registered subscribers immediately. `onCancel(Runnable)` returns a disposable Registration. Callbacks run on the cancelling thread, outside the token listener lock; ordinary callback exceptions do not prevent other callbacks. Registrations detach when their request finishes. An already-cancelled token invokes a new callback immediately. Parent BooleanSuppliers remain cooperative/polled, and closing a registration concurrently with a cancellation cannot retract a callback already collected for dispatch.

Cancellation completes only that subscriber. When a queued flight has no remaining subscribers, it is removed and capacity becomes available immediately. A running abandoned flight retires its ownership entry so later requests cannot attach to work that already observed abandonment. Host parent suppliers and completion callbacks run outside the in-flight ownership lock; a throwing parent fails its subscriber while another healthy subscriber can still receive the complete shared chunk. Context closure cancels its own subscribers/streams, releases its cache and leaves other contexts unaffected.

Cancelling collectRegionAsync now cancels its internal stream and releases collection admission after cleanup, including while workers are busy. Complete snapshots delivered before cancellation remain valid. Incomplete staged results never publish. Region submission failures remain generation-phase failures under CONTINUE/FAIL_FAST, and cancellation/closure at the scheduling boundary completes without trying to read an empty pending list. Consumers retain their original acknowledgement/backpressure and no-automatic-retry contracts.

## Distinct cache policies

| Policy | Retention and eviction |
|---|---|
| NO_CACHE | No retained completed chunks. |
| MEMORY_CACHE | Retains within the global engine budget; cacheBytes is ignored. Eligible for automatic reclamation. |
| BOUNDED_MEMORY_CACHE | Enforces cacheBytes with local LRU eviction; also eligible for global reclamation. |
| USER_MANAGED | Retains only if local/global admission fits. No implicit eviction, including unpinned entries. Caller uses explicit evict/clear and pin/unpin. |

A generated snapshot still returns successfully when it cannot be retained. CacheStats.retentionRejected records budget-related retention misses. Pinning requires USER_MANAGED and an already retained chunk; pinning an unretained result returns false. Explicit evict rejects pinned entries, clear preserves them, and context closure releases all pins/cache ownership. Unpin permits later explicit removal; it does not opt a user-managed cache into automatic eviction.

Cache accounting now includes the immutable snapshot estimate plus a conservative 128-byte per-entry allowance for cache bookkeeping. CacheStats.bytes and EngineMetrics.cacheBytes include this allowance. CacheStats.automaticEvictions counts local/global pressure removals, excluding explicit evict/clear/close. The prior five-argument CacheStats constructor remains available; record-pattern/reflection clients must account for its new components. RegionRequest likewise retains its prior constructor but adds a record component.

When staging, cache or collection admission needs room, the engine can reclaim automatic caches across contexts before rejecting. It rotates the starting context and removes each eligible context's least-recently-used entries until admission fits or eligible entries are exhausted. USER_MANAGED entries remain owned by their caller and can cause a memory rejection even when some are unpinned. Impossible requests and collection requests exceeding the collection cap reject without flushing caches. Retained snapshots held by clients remain immutable after eviction.

Reclamation runs outside the global admission monitor. Cache retention reserves globally outside its local cache lock, then rechecks local capacity and closure before publication. This avoids a cross-context cache lock cycle. Global byte counters can briefly include a reservation not yet published into a context cache; failed/closed publication releases it. These are conservative admission estimates, not exhaustive JVM heap measurement or ownership of snapshots held outside engine/collection leases.

## Evidence and remaining scope

Deterministic queue tests cover weighted service, per-scope FIFO/round robin, promotion, duplicate runnable support, bounds, removal, blocking wakeups/timeouts and draining. Engine integration covers shared promotion, explicit-token capacity release, independent cancellation, context closure, overload cleanup, asynchronous collection cancellation, host supplier failures and legacy constructors. Cache tests cover all policies, LRU, pin/clear/evict, global automatic reclamation, protected user-managed ownership, immutable snapshots, impossible collection admission, concurrent retain/reclaim and balanced closure accounting. The independent engine consumer uses both request priorities and region priority and inspects scheduler stats; native smoke opens the real Profiler.

These checks do not certify every scheduler/consumer race, workload-specific throughput or hardware responsiveness. Full unified renderer scheduling, priority admission reservations, preemption, global optimal LRU, detailed profiler captures and Windows 11 hardware acceptance remain separate work.
