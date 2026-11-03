"""Deferred compact-ink/exact-buffer receipt replay; no pixel/DPI certification."""
import json
import math
from pathlib import Path
import struct
import sys

report = json.loads((Path(sys.argv[1]) / 'readouts.json').read_text(encoding='utf-8'))
expected = {
    'Small': '1e-9', 'Negative': '-1e-9', 'Large': '1e+10', 'Precise': '1',
    'Signed zero': '-0', 'Invalid': 'NaN', 'Coordinate': '2e+9',
}
assert len(report['cases']) == 6
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, s) for f in (0, 1) for s in range(3)}
identities = {}
for case in report['cases']:
    assert set(case['rows']) == set(expected)
    inputs = {r['id']: r for r in case['inputs']}
    assert len(inputs) == len(expected)
    for name, row in case['rows'].items():
        receipt = inputs[row['inputId']]
        assert receipt['compact'] and not receipt['active']
        assert receipt['nativeFormat'] == '%.17g'
        assert receipt['item'] == row['input']
        key = (case['form'], name)
        identities.setdefault(key, row['inputId'])
        assert identities[key] == row['inputId']
        clip, item = receipt['clip'], receipt['item']
        assert all(math.isfinite(n) for n in clip.values())
        assert clip['width'] >= 0 and clip['height'] >= 0
        assert clip['x'] >= item['x'] and clip['y'] >= item['y']
        assert clip['x'] + clip['width'] <= item['x'] + item['width'] + .01
        assert clip['y'] + clip['height'] <= item['y'] + item['height'] + .01
        assert receipt['painted'] == (receipt['compact'] and receipt['visible'] and clip['width'] > 0 and clip['height'] > 0)
        if receipt['painted']:
            shown = '2000000000.125' if name == 'Coordinate' and case['form'] == 1 else expected[name]
            assert receipt['ink'] == shown
        if name == 'Coordinate' and case['form'] == 1:
            assert receipt['kind'] == 'coordinate'
        else:
            assert receipt['kind'] == 'double'
        source = float(receipt['source'])
        if name == 'Invalid':
            assert math.isnan(source)
        else:
            bits = struct.unpack('!q', struct.pack('!d', source))[0]
            assert bits == case['bits'][name]
assert len(report['focusCases']) == 2
adjacent = struct.unpack('!q', struct.pack('!d', math.nextafter(1.0, math.inf)))[0]
for focus in report['focusCases']:
    exact, idle = focus['exact'], focus['idle']
    assert exact['active'] and not exact['compact']
    assert not idle['active'] and idle['compact'] and idle['ink'] == '1'
    assert exact['id'] == idle['id'] and exact['nativeFormat'] == idle['nativeFormat'] == '%.17g'
    assert focus['bits'] == adjacent
raw = {r['source']: r for r in report['rawCases']}
assert set(raw) == {'+.12345678901234567', '-', '9007199254740993', '.3333333333333333'}
assert raw['+.12345678901234567']['compact'] and raw['+.12345678901234567']['ink'] == '0.12346'
assert raw['.3333333333333333']['compact'] and raw['.3333333333333333']['ink'] == '0.33333'
assert 0 < ((raw['.3333333333333333']['color'] & 0xffffffff) >> 24) < ((raw['+.12345678901234567']['color'] & 0xffffffff) >> 24)
assert not raw['-']['compact'] and not raw['9007199254740993']['compact']
print('Compact scalar ink, exact native activation buffers, raw/integer policy and clipped geometry replay PASS')
