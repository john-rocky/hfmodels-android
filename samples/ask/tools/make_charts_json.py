"""Writes samples/ask/src/main/assets/charts.json from the chart generator's cases.json.

    python3 samples/ask/tools/make_charts_json.py path/to/cases.json

cases.json holds 24 bar charts (id, n, heights, colours, context) with six questions each. The app
asks five of them, in this order: tallest, shortest, count, taller, leftmost. The pair the `taller`
question compares is kept as bar indices (`taller`: {a, b}) so the app can rebuild the question text
from the chart alone; the script checks that the rebuilt text and every expected answer agree with
cases.json before it writes anything.
"""
import json
import re
import sys
from pathlib import Path

KEEP = ['tallest', 'shortest', 'count', 'taller', 'leftmost']
OUT = Path(__file__).resolve().parents[1] / 'src/main/assets/charts.json'


def convert(case):
    by_id = {q['id']: q for q in case['questions']}
    cols, hs = case['colours'], case['heights']
    assert len(set(cols)) == len(cols) == len(hs) == case['n'], case['id']
    m = re.fullmatch(r'Is the (\w+) bar taller than the (\w+) bar\?', by_id['taller']['question'])
    a, b = cols.index(m.group(1)), cols.index(m.group(2))
    assert by_id['taller']['question'] == f'Is the {cols[a]} bar taller than the {cols[b]} bar?'
    assert by_id['taller']['expected'] == int(hs[a] > hs[b]), case['id']
    return dict(
        id=case['id'], n=case['n'], heights=hs, colours=cols, context=case['context'],
        taller=dict(a=a, b=b),
        questions=[dict(id=k, question=by_id[k]['question'], options=by_id[k]['options'], expected=by_id[k]['expected']) for k in KEEP],
    )


def main():
    cases = json.loads(Path(sys.argv[1]).read_text())
    charts = [convert(c) for c in cases]
    OUT.write_text(json.dumps(charts, indent=1) + '\n')
    print('wrote', OUT, len(charts), 'charts,', sum(len(c['questions']) for c in charts), 'questions')


if __name__ == '__main__':
    main()
