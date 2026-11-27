#!/usr/bin/env python3
"""Tests for check.py: the fixture passes, and each break of the contract is caught.

    python docs/site/tools/test_check.py
"""
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check  # noqa: E402

FIXTURE = os.path.join(HERE, 'fixture.html')


class CheckTest(unittest.TestCase):
    def setUp(self):
        fd, self.tmp = tempfile.mkstemp(dir=HERE, prefix='_t', suffix='.html')
        os.close(fd)
        self.nav = check.load_nav()[1]
        with open(FIXTURE, encoding='utf-8') as f:
            self.src = f.read()

    def tearDown(self):
        os.remove(self.tmp)

    def run_on(self, src, exempt=False):
        rel = os.path.relpath(self.tmp, check.SITE).replace(os.sep, '/')
        src = src.replace('data-page="tools/fixture"', f'data-page="{rel[:-5]}"')
        with open(self.tmp, 'w', encoding='utf-8') as f:
            f.write(src)
        return check.Page(self.tmp, self.nav, exempt=exempt).check()

    def errors(self, old, new, count=1):
        self.assertEqual(self.src.count(old), count, f'fixture text not found as expected: {old!r}')
        p = self.run_on(self.src.replace(old, new))
        return ' | '.join(m for _, m in p.errors)

    def test_fixture_passes(self):
        p = check.Page(FIXTURE, self.nav).check()
        self.assertEqual(p.errors, [])
        self.assertEqual(len(p.gaps), 1)
        self.assertEqual(len(p.shots), 1)
        self.assertEqual(len(p.diagrams), 1)

    def test_breaks_are_caught(self):
        cases = [
            ('<h2 id="callouts">', '<h2>', 'needs an id'),
            ('<h2 id="callouts">', '<h2 id="Callouts">', 'lower case'),
            ('<h2 id="tabs">', '<h2 id="steps">', 'used twice'),
            ('<h2 id="tables">Tables</h2>', '<h4 id="tables">Tables</h4>', 'h4 appears only'),
            ('data-lang="yaml"', 'data-lang="yml"', 'needs data-lang'),
            ('<aside class="callout tip">', '<aside class="callout hint">', 'one kind'),
            ('<p class="callout-title">Purging cannot be undone</p>', '<p>Purging cannot be undone</p>',
             'starts with p.callout-title'),
            ('<p>Text between callouts.</p>\n      <aside class="callout danger">', '<aside class="callout danger">',
             'back to back'),
            ('<p class="step-title">Start the demo.</p>', '<p class="step-title">Start the demo</p>', 'full stop'),
            ('data-tab="curl" data-label="curl">\n          <div', 'data-tab="curl" data-label="Curl">\n          <div',
             'data-label="curl"'),
            ('<section class="tab" data-tab="console" data-label="Console">\n          <p>Select',
             '<section class="tab" data-tab="python" data-label="Python">\n          <p>Select', 'not a value'),
            ('<div class="table-wrap">\n        <table>\n          <caption>', '<div>\n        <table>\n          <caption>',
             'only appears as a component'),
            ('<p>Text between callouts.</p>', '<p style="color:red">Text between callouts.</p>', 'no inline style', 5),
            ('<span class="badge demo">', '<span class="badge beta">', 'one kind'),
            ('<b class="ui">Catalog</b> <b class="ui">orders</b>', '<b class="ui">Catalog</b> &gt; <b class="ui">orders</b>',
             'separators'),
            ('href="#callouts"', 'href="#nowhere"', 'no such id'),
            ('href="../start/console-tour.html"', 'href="../start/nonexistent.html"', 'not in the site map'),
            ('href="https://iceberg.apache.org/spec/"', 'href="/spec/"', 'rooted at /'),
            ('<pre><code>demo/demo.sh up</code></pre>', '<pre><code>$ demo/demo.sh up</code></pre>', 'no prompts'),
            ('<pre><code>demo/demo.sh up</code></pre>', '<pre><code>\ndemo/demo.sh up</code></pre>', 'blank first line'),
            ('<rect class="d-node" x="20"', '<rect class="d-node" fill="red" x="20"', 'd-* classes'),
            ('aria-labelledby="fig-architecture-t fig-architecture-d"', 'aria-labelledby="x"', 'aria-labelledby'),
            ('data-shot-user="presenter"', '', 'needs data-shot-user'),
            ('data-shot-route="#c=lakehouse', 'data-shot-route="#/c/lakehouse', 'href() form'),
            ('<title>Every component · nuna docs</title>', '<title>Every component</title>', '<title> must be'),
            ('<script src="../assets/docs.js" defer></script>', '<script src="../assets/docs.js"></script>', 'defer'),
            ('data-root="../"', 'data-root=""', 'data-root'),
            ('<dt>Read by</dt><dd>the server</dd>\n          <dt>Restart</dt>', '<dt>Restart</dt><dd>Needed</dd>\n          <dt>Read by</dt>',
             'this order'),
            ('<h4 id="post-grants-body">', '<h4 id="post-grants-request">', 'h3 id plus'),
            ('<span class="method" data-method="PUT">PUT</span>', '<span class="method" data-method="PUT">POST</span>',
             'same text'),
            ('<summary>How this page was checked</summary>', '<summary>Sources</summary>', 'How this page was checked'),
            ('<li>An ordered list item.</li>', '<li>An ordered list item.', 'never closed'),
            ('<p>Text under an h3,', '<p><ul><li>x</li></ul>Text under an h3,', 'inside a <p>'),
            ('<a href="#callouts">the callouts below</a>', '<a href="#callouts">here</a>', 'say where it goes'),
            ('<h2 id="if-it-fails">If it fails</h2>', '<h2 id="failures">If it fails</h2>', 'fixed id'),
        ]
        for case in cases:
            old, new, want = case[:3]
            count = case[3] if len(case) > 3 else 1
            with self.subTest(want=want, old=old[:40]):
                self.assertIn(want, self.errors(old, new, count))

    def test_tail_is_required_for_a_guide_in_the_site_map(self):
        # outside tools/, a guide must end with See also and How this page was checked
        src = self.src
        start = src.index('      <section class="see-also">')
        end = src.index('    </article>')
        p = self.run_on(src[:start] + src[end:])
        self.assertTrue(any('ends with section.see-also' in m for _, m in p.errors))
        p = self.run_on(src[:start] + src[end:], exempt=True)
        self.assertFalse(any('ends with section.see-also' in m for _, m in p.errors))

    def test_wording_warnings(self):
        p = self.run_on(self.src.replace('<p>Text between callouts.</p>', '<p>Simply click here → done (D45).</p>', 1))
        w = ' | '.join(m for _, m in p.warnings)
        for want in ('hype word "Simply"', '"click"', 'arrow', 'decision row "D45"'):
            self.assertIn(want, w)


if __name__ == '__main__':
    unittest.main(verbosity=1)
