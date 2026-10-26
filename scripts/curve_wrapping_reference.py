"""Independent exact-binary rational fallback oracle; ordinary IEEE results remain historical."""
import argparse, json, math, sys
from fractions import Fraction as F
from pathlib import Path


def legacy(lo, hi, value, mode):
    if lo <= value <= hi:
        return value
    length = hi - lo
    try:
        if mode == 'REPEAT':
            return lo + (value - lo) - length * math.floor((value - lo) / length)
        period = 2 * length
        t = (value - lo) - period * math.floor((value - lo) / period)
        return lo + (t if t <= length else period - t)
    except (ValueError, OverflowError):
        return math.nan


def corpus():
    smallest = math.ulp(0.)
    domains = [(0., 1.), (1., 2.), (-2., -1.), (0., smallest),
               (smallest, 2 * smallest), (0., math.ldexp(1., -1000)),
               (math.ldexp(1., -1000), math.ldexp(1., -999)),
               (1e16, 1e16 + 2), (-1e16 - 2, -1e16), (-8e307, 8e307), (-1e308, 0.)]
    common = [0., smallest, -smallest, .1, -.1, .5, -.5, 1., -1., 4., -4.,
              1e16, -1e16, 1e20, -1e20, 1e100, -1e100, 1e300, -1e300,
              sys.float_info.max, -sys.float_info.max]
    rows = []
    for lo, hi in domains:
        values = common + [lo, hi, math.nextafter(lo, -math.inf),
                           math.nextafter(hi, math.inf), lo / 2 + hi / 2]
        for mode in ['REPEAT', 'MIRROR']:
            for value in dict.fromkeys(values):
                if not math.isfinite(value):
                    continue
                mapped = legacy(lo, hi, value, mode)
                historical = math.isfinite(mapped) and lo <= mapped <= hi
                if not historical:
                    length = F(hi - lo)
                    period = length if mode == 'REPEAT' else 2 * length
                    phase = (F(value) - F(lo)) % period
                    distance = phase if mode == 'REPEAT' else min(phase, period - phase)
                    mapped = float(F(lo) + distance)
                expected = 0. if mapped == lo else 1. if mapped == hi else (mapped - lo) / (hi - lo)
                rows.append(dict(min=lo, max=hi, mode=mode, input=value,
                                 legacyValid=historical, expectedHex=expected.hex()))
    return dict(formatVersion=1, projectFormat=2, processingKind='CURVE', dimensions=[2, 3],
                seed=0, fallbackOracle='exact Fraction of binary64 inputs and rounded binary64 domain span',
                tolerance=2e-13, vectors=rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('path', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = corpus()
    if args.check:
        if json.loads(args.path.read_text()) != data:
            raise AssertionError('Curve wrapping reference differs; regenerate explicitly')
    else:
        args.path.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')
    fallback = sum(not row['legacyValid'] for row in data['vectors'])
    print(f'CURVE_WRAPPING_REFERENCE {len(data["vectors"])} vectors / {fallback} exact-rational fallback cases')


if __name__ == '__main__':
    main()
