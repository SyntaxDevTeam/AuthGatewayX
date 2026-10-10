import socket
import threading
import unittest
from benchmark_local_login import attempt, discover_protocol, packet, read_varint, run, string, varint


class LocalLoginBenchmarkTest(unittest.TestCase):
    def test_protocol_discovery_uses_local_status_response(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            server.settimeout(3)
            failures = []
            def respond():
                try:
                    with server.accept()[0] as client:
                        client.settimeout(2)
                        for _ in range(2):
                            remaining = read_varint(client)
                            while remaining:
                                data = client.recv(remaining)
                                if not data:
                                    raise EOFError()
                                remaining -= len(data)
                        client.sendall(packet(b'\x00' + string('{"version":{"protocol":999}}')))
                except Exception as failure:
                    failures.append(failure)
            worker = threading.Thread(target=respond)
            worker.start()
            self.assertEqual(999, discover_protocol(server.getsockname()[1]))
            worker.join(4)
            self.assertFalse(worker.is_alive())
            self.assertEqual([], failures)

    def test_actual_loopback_login_and_disconnect_response(self):
        failures = []
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            server.settimeout(3)
            port = server.getsockname()[1]
            def respond():
                try:
                    with server.accept()[0] as client:
                        client.settimeout(2)
                        for _ in range(2):
                            remaining = read_varint(client)
                            while remaining:
                                data = client.recv(remaining)
                                if not data:
                                    raise EOFError()
                                remaining -= len(data)
                        client.sendall(packet(b'\x00\x02{}'))
                except Exception as failure:
                    failures.append(failure)
            worker = threading.Thread(target=respond)
            worker.start()
            outcome, latency = attempt(port, 999, "SyntheticUser")
            worker.join(4)
            self.assertFalse(worker.is_alive())
            self.assertEqual([], failures)
            self.assertEqual("disconnect", outcome)
            self.assertGreater(latency, 0)

    def test_limits_apply_to_imported_entrypoint(self):
        for options in ({"connections": 201}, {"concurrency": 9}, {"rate": 21}, {"pattern": "unknown"}):
            with self.assertRaises(ValueError):
                run(25565, 999, **options)

    def test_multibyte_varint(self):
        self.assertEqual(b"\x80\x01", varint(128))


if __name__ == "__main__":
    unittest.main()
