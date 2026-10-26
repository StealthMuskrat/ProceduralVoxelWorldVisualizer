# Shared warp fields and modes

DOMAIN_WARP coordinate transforms and WARP_SAMPLE post-source items use one immutable headless WarpPlan and WarpRegistry. They change procedural sample coordinates; they never approximate displacement by manipulating a previously sampled scalar. The public samplers, previews, World uses, references and trace replay compile the same definitions.

Project format remains 2. Processing registry version 5 references the new version-1 warp registry. The model's existing parameter and typed-input maps carry all settings; no secondary editor-only evaluator or runtime profile fallback was added.

## Source and displacement contracts

Warp Algorithm selects any dimension-supported canonical source algorithm. Its `source.<parameter>` keys retain the source registry's numeric types, raw engine ranges, numeric enum values and supported visibility. `frequency` is shared with the top-level warp controls; `source.frequency` is rejected to avoid two competing authorities. Known typed inactive source settings remain stored, following the source registry. Algorithm sources expose their applicable fractal, cellular, analytic or SDF controls. These are distinct from the outer warp-octave controls.

Each active axis independently selects:

| Component | Meaning |
| --- | --- |
| Algorithm | Canonical selected algorithm; historical decorrelation offsets per axis |
| Stage Value | Scalar `warpX`, `warpY`, or `warpZ` binding, evaluated as a numeric value in its existing stage context |
| Sampled Field | `displacementX`, `displacementY`, or `displacementZ`, reevaluated at each actual warp query |

A component cannot have both a stage value and a sampled-field binding. Post-source fields may select Source Field, an enabled earlier Scalar field prefix or a Noise Generator Field. Coordinate-stage fields must reference another generator; source and processing prefixes are unavailable before source evaluation. Self/later/disabled dependencies and generator cycles reject. Typed coordinates, shared parameters and stage-value controls retain their existing rules. Cross-dimensional field references use explicit XY/XZ/YZ projection and inserted slice.

Sampled component fields receive `query * octaveScale * frequency`, applying their own transforms exactly once and retaining their own UUID/runtime/offset seed. Internal algorithm components instead receive `(query + axisOffset) * octaveScale`; the algorithm applies frequency according to its canonical semantics. Offsets are X=(0,0,0), Y=(31.1,-17.3,7.7), Z=(-11.2,43.8,-19.4). Two-dimensional displacement uses X/Z, ignores internal Y offsets and rejects a sampled Y component. Post-source warps retain the unmodified incoming ambient Y for the selected target, preserving historical Y-aware field prefixes; explicit Coordinates2D still inserts Y=0. Coordinate stages retain their historical Y=0 publication. Existing ignored coordinate-stage numeric warpY values remain retained for compatibility.

Numeric stage-value bindings ignore query scaling, frequency and axis order. They may reuse an earlier result/shared parameter/reference value; choosing a sampled field is the explicit way to request resampling. There is no implicit value-to-field conversion.

## Octaves and coordinate domains

Warp octaves are 1..16, with positive finite lacunarity and signed finite gain. Octave o has scale `lacunarity^o` and amplitude `amplitude * gain^o`. There is no amplitude normalization or output clamp.

| Mode | Query domain |
| --- | --- |
| INDEPENDENT | Every octave samples the original incoming warp position; its simultaneous component displacement accumulates into the result |
| PROGRESSIVE | Every octave samples the position accumulated by preceding octaves; all active components in that octave use the same position |
| SEQUENTIAL | Octaves sample accumulated positions and update components immediately in the explicit XZ/ZX or 3D axis order |

The target field is evaluated once at the final displaced position. WARP_SAMPLE starts from incoming generator coordinates or its explicit typed Coordinates input, then invokes the selected target field/prefix, including that field's transforms once. DOMAIN_WARP starts at its incoming coordinate-stage location or an earlier explicit coordinate snapshot; subsequent coordinate transforms execute in authored order.

Zero amplitude does not execute displacement queries. Zero octave amplitude (including zero gain) skips that octave. Controls and references still validate before evaluation. Nonfinite displacement, coordinate scaling, amplitude products and accumulated coordinates fail at the executing warp boundary; octave scale/gain overflow rejects during compilation. Per-thread primitive result scratch is owned by each compiled item. Estimated displacement field and numeric generator-reference work is multiplied by octave count and included with target work; nested sampled prefixes retain the existing 1,000,000-work and depth limits.

## Seeds and compatibility

Historical absent controls mean OPEN_SIMPLEX_2, one independent octave, frequency 0.01, amplitude 8, zero seed offset and existing axis offsets. Historical stage-value bindings retain their meaning. The algorithm seed remains `mix(generatorSeed + Double.doubleToLongBits(seedOffset))`; finite signed offsets and signed zero retain their historical IEEE-bit interpretation. This control is not advertised as an exact integer-64 seed offset. The containing generator's project/runtime seed, UUID and long resource offset remain canonical.

SAME reuses the algorithm seed for every outer octave. INCREMENT keeps octave zero unchanged and uses `mix(baseSeed + octave)` for later algorithm octaves. It does not change referenced field seeds. A canonical algorithm's own fractal seed behavior remains its source contract.

Unknown/invalid choices, active algorithm ranges, malformed fields, conflicting component bindings and unsupported WARP_SAMPLE input names now reject with exact authored control keys. This stricter rejection of previously ignored or ambiguous inputs is intentional. Disabled item settings remain stored under the existing disabled-item rules.

## Authoring and validation scope

Properties supplies shared searchable algorithm/mode/axis/source-enum choices, slider/text controls, explicit component source selectors, typed field resource/prefix navigation, optional Coordinates and private parent Apply/Undo. Supported source and octave controls are conditional; invalid drafts remain drawable for repair. Creation presets only affect future items. The common processing order preset uses a 3D order; creating a new 2D WARP_SAMPLE removes Y while retaining relative X/Z order. Existing definitions are never rewritten.

The independent Python corpus defines 432 arithmetic vectors across both dimensions, all modes/orders, octave counts, signed/zero amplitudes and frequencies. JUnit and the engine-only consumer replay them through both compiler stages (864 stage cases each). Native authoring uses real numeric/searchable input: at (2,3), referenced dx=z and dz=x with target x+10z initially produce 55. Amplitude 2 with sequential XZ yields x=8,z=19 and result 198; one Apply/Undo/Redo preserves these exact values. Native visits both stages in 2D/3D and rejects zero octaves without losing the draft. Testing is deferred until the full batch is implemented. Full GUI/DPI/hardware acceptance remains separate.
