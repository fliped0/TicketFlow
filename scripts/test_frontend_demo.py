"""Checks unknown non-idempotent admin creation recovery, without a database."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('frontend_demo', ROOT / 'scripts/frontend-demo.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UnknownCreationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=ROOT / '.tools')
        assert Path(self.directory.name).resolve().is_relative_to((ROOT / '.tools').resolve())
        self.addCleanup(self.directory.cleanup)
        self.demo = object.__new__(module.Demo)
        self.demo.path = Path(self.directory.name)
        self.demo.file = self.demo.path / 'manifest.json'
        self.demo.state = {'pending': 'example:eventId'}
        self.item = {'name': 'example'}
        self.posts = []
        self.demo.call = lambda *args: self.posts.append(args)

    def ensure(self):
        return self.demo.ensure(self.item, 'eventId', 'event', '/admin/events', {'name': 'example'}, '/admin/events', lambda row: row['name'] == 'example')

    def test_unknown_unique_creation_is_reconciled_without_post(self):
        self.demo.listing = lambda _: [{'name': 'example', 'id': '123'}]
        self.assertEqual(self.ensure(), '123')
        self.assertEqual(self.posts, [])
        self.assertNotIn('pending', self.demo.state)

    def test_unknown_missing_or_ambiguous_creation_is_never_reposted(self):
        for rows in ([], [{'name': 'example', 'id': '1'}, {'name': 'example', 'id': '2'}]):
            self.demo.listing = lambda _, rows=rows: rows
            with self.assertRaises(RuntimeError):
                self.ensure()
            self.assertEqual(self.posts, [])
            self.assertEqual(self.demo.state['pending'], 'example:eventId')

    def test_new_post_failure_preserves_intent_before_send(self):
        self.demo.state = {}
        def interrupted(*args):
            self.assertEqual(json.loads(self.demo.file.read_text())['pending'], 'example:eventId')
            raise RuntimeError('response lost')
        self.demo.call = interrupted
        with self.assertRaises(RuntimeError):
            self.ensure()
        self.assertEqual(self.demo.state['pending'], 'example:eventId')

    def test_concurrent_manifest_lock_rejects_second_writer_and_releases(self):
        with self.demo.lock():
            with self.assertRaises(RuntimeError):
                with self.demo.lock():
                    self.fail('Concurrent writer acquired lock')
        with self.demo.lock():
            pass


if __name__ == '__main__':
    unittest.main()
