# Independent source-noise reference

`FastNoiseLite.h` is the unmodified upstream FastNoiseLite 1.1.1 C++ header:
https://github.com/Auburn/FastNoiseLite/blob/v1.1.1/Cpp/FastNoiseLite.h

SHA-256: `47a29750dfe13d15651febd235f3c26b1cc57af7c6e26583b838fd7ab5215dc2`.
The original MIT copyright and license are retained in the header. This does not supply a license grant for project-owned code.

The harness compiles separately from Java and never loads the engine or desktop. It instantiates the upstream double-coordinate templates, passes float-compatible source controls and folds the 64-bit seed to signed 32 bits explicitly. Build on x64 GCC with:

```
g++ -std=c++20 -O2 -fwrapv -fno-fast-math -ffp-contract=off reference/fastnoiselite/reference.cpp -o build/algorithm-reference/reference
build/algorithm-reference/reference > build/algorithm-reference/cpp-vectors.tsv
```

`-fwrapv` defines the signed hash arithmetic to match Java's 32-bit wrap; contraction/fast-math remain disabled. The corpus contains 3,264 cases across the five upstream source families, both dimensions, four full-width seeds, six signed/boundary coordinates, two frequencies, all four source-fractal modes, and every cellular distance/return combination. The final column is the raw 64-bit representation of the widened float result.

The Java consumer checks numerical agreement with this independently maintained implementation under the declared cross-language tolerance (`2e-6 + 2e-6*abs(reference)`). It reports exact-bit matches and maximum error separately. C++ agreement is not a claim of bit identity between language implementations. Separate Java raw-bit digests must match between the pinned Linux/Windows JVM runs. Analytic and coordinate-hash fixtures use independent Python formulas and exact raw-bit checks. The certified CPU/JVM matrix remains incomplete.
