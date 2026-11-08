import base64
import io
import json
import unittest
from zipfile import ZipFile

from verify_velocity_distribution import MAIN, REQUIRED_ENTRIES, REQUIRED_LIBRARIES, verify


class VelocityDistributionTest(unittest.TestCase):
    def artifact(self, main=MAIN, missing=None, libraries=None):
        buffer = io.BytesIO()
        checksum = base64.b64encode(bytes(32)).decode()
        entries = dict.fromkeys(REQUIRED_ENTRIES, b'class fixture')
        entries['velocity-plugin.json'] = json.dumps({'id': 'authgatewayx', 'main': main})
        entries['velocity-libraries.txt'] = libraries if libraries is not None else '\n'.join(
            f'{library}:1.0:{checksum}' for library in sorted(REQUIRED_LIBRARIES))
        entries.pop(missing, None)
        with ZipFile(buffer, 'w') as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        buffer.seek(0)
        return buffer

    def test_external_runtime_distribution(self):
        verify(self.artifact())

    def test_old_entrypoint(self):
        with self.assertRaisesRegex(ValueError, 'descriptor'):
            verify(self.artifact(main='pl.syntaxdevteam.authgatewayx.velocity.AuthGatewayXVelocity'))

    def test_missing_entries(self):
        for entry in (*REQUIRED_ENTRIES, 'velocity-libraries.txt'):
            with self.subTest(entry=entry), self.assertRaisesRegex(ValueError, 'Missing required'):
                verify(self.artifact(missing=entry))

    def test_missing_drivers(self):
        with self.assertRaisesRegex(ValueError, 'Missing runtime libraries'):
            verify(self.artifact(libraries=''))

    def test_invalid_manifest(self):
        for value in ('invalid', 'com.zaxxer:HikariCP:1:!!!', 'com.zaxxer:HikariCP:1:YQ=='):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'Invalid'):
                verify(self.artifact(libraries=value))

    def test_duplicate_library(self):
        line = 'com.zaxxer:HikariCP:1:' + base64.b64encode(bytes(32)).decode()
        with self.assertRaisesRegex(ValueError, 'Duplicate runtime library'):
            verify(self.artifact(libraries=line + '\n' + line))
