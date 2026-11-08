# Explicit cancellation during parent polling

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, checker, packaging operation or workflow ran. The observed baseline remains **922 tests / 120 suites**. No A01–A36, Windows 11 or release gate advances.

## A false parent result cannot hide cancellation during that poll

CancellationToken.isCancelled previously evaluated `cancelled.get() || parent.getAsBoolean()`. If the explicit flag was false at entry, a parent supplier could call token.cancel and then return false; another thread could also cancel while that supplier was blocked. The current query returned false even though explicit cancellation had already completed. A later query would see the flag, but paths with no later query could finish normally. In particular, WorldContext's cache hit can return a completed chunk immediately after its initial token check, and a valid zero-extent grid returns immediately after its preflight cancellation check.

The query now returns immediately for an already-explicitly-cancelled token, otherwise polls the parent once and rechecks the explicit flag after a normally returning false result. throwIfCancelled inherits the corrected observation. Initial cached chunk requests therefore return their existing cancellation future rather than count a cache hit or expose a successful chunk. Empty grids retain their existing cancellation-check contract before returning; destinations and samplers remain untouched.

The change observes the result of this poll. It does not lock out cancellation after the final flag read or preempt a blocking supplier. No second parent call, lock or callback dispatch is added. A parent's true result remains an unlatched observation, and observing it does not drain previously registered listeners. Explicit cancel retains once-only callback claims and late registration behavior. A supplier that throws still propagates the original RuntimeException/Error even if it explicitly cancelled first; no failure text or suppressed-list mutation is introduced. Thread interruption and validation ordering retain their existing paths.

Public signatures, cache policies, queue/subscriber limits, callback ownership, project/schema/registry/archive formats and field/voxel arithmetic remain unchanged. A normally false parent poll now performs another atomic flag read; runtime cost remains unmeasured. The wider W05 cancellation, admission, region and scheduling audit remains open.

## Deferred module definitions

Six CancellationPollTest definitions cover self-cancellation during boolean/throwing checks; latch-controlled cancellation from another thread before a parent returns false, including immediate late-listener registration; passive parent-only observations without latching or listener draining; original parent fault identity after explicit cancellation; cached rejection across roots 2/4, three retaining cache policies and three async/priority routes with no extra generation/cache hit/admission and healthy retained peers; and seven empty/nonempty 2D/3D grid cases with zero sampler calls and raw-bit-preserved destination sentinels. Retained EMPTY chunks remain readable after closure. None of these definitions has run.

## Deferred independent consumer

CancellationPollConsumer is invoked by the existing engine-JAR-only CancellationOwnershipConsumer command. Self-cancelling and latch-held parents define **four direct query checks, 36 cached rejections and 12 NO_CACHE rejections** across roots 2/4 and the default/INTERACTIVE/BACKGROUND async request routes. Parent calls and callbacks must occur once; explicit terminal state remains, rejected requests consume no new work/cache hit, and healthy cached peers retain identity.

The same parent modes define **14 cancelled grid cases / 42 untouched destination cells**. Seven healthy grid controls define **two exact batch outputs / 19 untouched padding-or-empty cells**. Destination expectations preserve negative zero and a noncanonical NaN payload by raw bits. A passive-parent case and two original parent-fault cases retain the existing observation/failure policy.

Independent `x + 2*z + .25` and `x + 2*z + 4*y + .25` expectations define **54 scalar observations** before/after the rejection replay. Eight retained world chunks define **288 exact signed-position voxel expectations** after context/engine closure, using the known project Y interval and zero-threshold selection, without an engine-generated oracle. Complete authoring JSON remains unchanged. Counts describe future definitions, not observed results.

The future report is `cancellation-ownership/parent-polls/cancellation-poll-report.json`; no report has been generated. Existing manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. Earlier callback/subscriber, parse, world, grid and native replay dimensions remain unchanged. Source review and git whitespace/content/metadata checks are the available verification.
