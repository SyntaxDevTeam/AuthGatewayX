"""Verify the Velocity distribution's loader and external runtime contract."""
import base64
import binascii
import json
from pathlib import Path
import sys
from zipfile import ZipFile, BadZipFile

MAIN = 'pl.syntaxdevteam.authgatewayx.velocity.loader.SyntaxLoader'
REQUIRED_ENTRIES = (
    MAIN.replace('.', '/') + '.class',
    'pl/syntaxdevteam/authgatewayx/velocity/AuthGatewayXVelocity.class',
    'pl/syntaxdevteam/authgatewayx/storage/jdbc/JdbcAccountStorage.class',
    'pl/syntaxdevteam/authgatewayx/velocity/libs/libby/VelocityLibraryManager.class',
)
REQUIRED_LIBRARIES = {
    'com.zaxxer:HikariCP', 'org.xerial:sqlite-jdbc',
    'com.mysql:mysql-connector-j', 'org.mariadb.jdbc:mariadb-java-client',
    'org.postgresql:postgresql', 'org.jetbrains.kotlin:kotlin-stdlib',
    'pl.syntaxdevteam:syntaxcore-proxy', 'pl.syntaxdevteam:messageHandler-velocity',
}


def verify(jar):
    with ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate JAR entries')
        for entry in (*REQUIRED_ENTRIES, 'velocity-plugin.json', 'velocity-libraries.txt'):
            if entry not in names:
                raise ValueError(f'Missing required Velocity JAR entry: {entry}')
        descriptor = json.loads(archive.read('velocity-plugin.json'))
        if descriptor.get('id') != 'authgatewayx' or descriptor.get('main') != MAIN:
            raise ValueError(f'Invalid Velocity descriptor id/main: {descriptor}')
        libraries = set()
        for line in archive.read('velocity-libraries.txt').decode('utf-8').splitlines():
            parts = line.split(':')
            if len(parts) != 4 or not all(parts):
                raise ValueError(f'Invalid runtime coordinate: {line}')
            try:
                checksum = base64.b64decode(parts[3], validate=True)
            except binascii.Error as exc:
                raise ValueError(f'Invalid runtime checksum: {line}') from exc
            if len(checksum) != 32:
                raise ValueError(f'Invalid SHA-256 checksum: {line}')
            library = ':'.join(parts[:2])
            if library in libraries:
                raise ValueError(f'Duplicate runtime library: {library}')
            libraries.add(library)
        if "pl.syntaxdevteam:syntaxcore" in libraries:
            raise ValueError("Paper SyntaxCore must not be loaded on Velocity")
        missing = REQUIRED_LIBRARIES - libraries
        if missing:
            raise ValueError(f'Missing runtime libraries: {sorted(missing)}')


def main():
    candidates = sorted(path for path in Path(sys.argv[1]).glob('AuthGatewayX-Velocity-*.jar')
                        if not path.name.endswith(('-plain.jar', '-sources.jar', '-javadoc.jar', '-original.jar')))
    if len(candidates) != 1:
        raise ValueError(f'Expected one Velocity distribution, found: {candidates}')
    verify(candidates[0])
    print(f'Verified Velocity distribution: {candidates[0]}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, BadZipFile) as exc:
        sys.exit(f'Velocity distribution verification failed: {exc}')
