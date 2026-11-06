# Diagnostic navigation authority

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and release acceptance remain pending; no A01–A36 gate advances.

## Explicit keys and pointer hints

Editor navigation previously reconstructed an input key from any matching `processing`/`coordinateTransforms`/`control` segment in a pointer. That could replace an explicit `inputs/a` target with `inputs/b`, or accept a nested unrelated collection. Binding-leaf focus likewise accepted nested paths and trailing segments. Ordinary parameter focus checked the noise root but did not compare full UUID path tokens with the target's supplied UUIDs.

DiagnosticNavigation now preserves canonical `inputs/…`, `parameters/…` and `algorithm/parameters/…` keys before considering pointer hints. An explicit `parameters/value` remains the parent value route even when a pointer ends in `/value/2`; its existing default vector focus is component zero. An explicit component key remains exact. Ordinary unrelated parameter keys also remain unchanged.

For older structural diagnostics, a missing key, the decoded input name, or its serialized binding-leaf key can still become `inputs/<decoded name>`. Recognized legacy leaf names include the existing `constant` alias. Unqualified keys are therefore a compatibility convention, not an unambiguous canonical identity; callers needing exact authority should use a canonical key. Existing event key/pointer length bounds remain in effect.

## Bounded path interpretation

Pointer hints must begin at an immediate `noise2D`, `noise3D` or `noise` resource. Input hints must use that resource's immediate `processing`, `coordinateTransforms` or `control` collection. Source parameters use immediate `algorithm/parameters`; shared values use immediate `sharedParameters`. Input names decode JSON Pointer slash/tilde escaping exactly.

Both resource and control authority must already exist in the diagnostic/target before their path positions can contribute input or item hints. Full hyphenated UUID tokens, in either hex case, must equal that authority. Nonnegative canonical decimal indices remain historical syntax only: the helper never resolves them against the current document. Signed, padded, negative, abbreviated UUID and overflowing index tokens contribute no hint. This keeps existing links usable after resource/control reorder without adopting a different live object at the old position. It does not prove that a historical numeric path originally referred to the supplied UUID; model enrichment and current-draft availability retain their separate responsibilities.

Binding focus requires the exact canonical named input route and exact decoded input name. A serialized field must be a complete supported leaf; only `value/0`, `value/1` and `value/2` may add a component. Unsupported suffixes, foreign UUIDs and unrelated paths fall back to the binding's current source editor. Ordinary item/shared values accept only a complete value path or one supported component. Raw `value` retains its existing parent-value fallback if the pointer contributes nothing. Source algorithm parameters require a complete parameter path.

No helper looks up or replaces a target UUID, applies a draft or edits a value. Existing project/resource/draft focus ownership, once-only activation, explicit-save transactions and current-draft availability remain. This is scoped editor routing, not a general JSON Pointer validator. Public diagnostic/event components, authored formats, registries, schemas, engine math and native receipts remain unchanged; engine/model/storage gain no editor dependency.

## Deferred verification

Ten DiagnosticRouteAuthorityTest definitions cover canonical/unrelated key preservation; legacy escaped input names and leaf aliases; nested and missing-root paths; matching uppercase UUIDs and historical indices across noise roots/collections; foreign and malformed resource/control tokens; missing authority; exact binding leaves and fallback; canonical parameter/component preservation and unsupported tails; owned component focus and replacement-draft cancellation; and navigation after actual resource/control reorder with a rejected private binding. The final definition preserves exact draft identity/text, committed JSON, revision, history and dirty state.

Existing DiagnosticNavigationTest, NoiseControlRouteTest, PropertyFocusTest, native InputBindingProbe/PropertyFocusProbe and independent arithmetic replay dimensions remain unchanged. These new definitions have not run. Source review and git whitespace/content/metadata checks are the available verification. Runtime/native focus validation, Windows 11 and release acceptance remain outstanding.
