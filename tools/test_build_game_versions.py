import tempfile
import unittest
from pathlib import Path
from build_game_versions import game_versions


class GameVersionsTest(unittest.TestCase):
    def read(self, text):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'VersionChecker.kt'
            source.write_text(text)
            return game_versions(str(source))

    def test_reads_only_target_set(self):
        self.assertEqual(self.read('''
            SemanticVersion(1, 0, 0)
            private val SUPPORTED_VERSIONS: Set<SemanticVersion> = setOf(
                SemanticVersion(26, 1, 0), // normalized
                SemanticVersion(26, 1, 2),
                /* SemanticVersion(99, 0, 0), */
                SemanticVersion(26, 1, 0),
            )
            SemanticVersion(2, 0, 0)
        '''), ['26.1', '26.1.2'])

    def test_invalid_sources_fail_closed(self):
        for text in ('', 'val SUPPORTED_VERSIONS = setOf()',
                     'val SUPPORTED_VERSIONS = setOf(SemanticVersion(26, 1, 0)',
                     'val SUPPORTED_VERSIONS = setOf(otherVersions)',
                     'val SUPPORTED_VERSIONS = setOf(SemanticVersion(26, 1, 0), other)'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.read(text)

    def test_proxy_has_no_backend_targets(self):
        self.assertEqual(game_versions(''), [])

    def test_missing_file_fails(self):
        with self.assertRaises(OSError):
            game_versions('/nonexistent/VersionChecker.kt')

