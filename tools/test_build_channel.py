import unittest

from build_channel import build_channel


class BuildChannelTest(unittest.TestCase):
    def test_supported_versions(self):
        for version, expected in {
            "1.0.0-R0.1-SNAPSHOT": "snapshot",
            "1.0.0-SNAPSHOT": "snapshot",
            "1.0.0-dev": "dev",
            "1.0.0-R0.1-DEV": "dev",
            "1.0.0-WIP": "wip",
            "1.0.0-R0.1-WIP": "wip",
            "1.0.0": "release",
            "1.0.0-R0.1": "release",
        }.items():
            with self.subTest(version=version):
                self.assertEqual(expected, build_channel(version))

    def test_unknown_versions_do_not_publish_as_release(self):
        for version in ("", "unspecified", "deadbeef1234", "1.0.0-RC1", "1.0.0-SNAPSHOT-287", "1.0.0-DEV-SNAPSHOT", "1.0.0\n"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                build_channel(version)
