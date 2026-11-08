"""Bounded loopback-only Minecraft LOGIN admission benchmark; Python standard library."""
import argparse
import collections
import concurrent.futures
import json
import math
from pathlib import Path
import socket
import struct
import time
import uuid


def varint(value):
    value &= 0xffffffff
    output = bytearray()
    while True:
        part = value & 0x7f
        value >>= 7
        output.append(part | (0x80 if value else 0))
        if not value:
            return bytes(output)


def string(value):
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def packet(payload):
    return varint(len(payload)) + payload


def read_varint(sock):
    value = 0
    for offset in range(5):
        part = sock.recv(1)
        if not part:
            raise EOFError("Connection closed")
        value |= (part[0] & 0x7f) << (7 * offset)
        if not part[0] & 0x80:
            return value
    raise ValueError("Invalid VarInt")


def attempt(port, protocol, name):
    start = time.monotonic_ns()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
            sock.settimeout(2)
            hello = b"\x00" + varint(protocol) + string("127.0.0.1") + struct.pack(">H", port) + b"\x02"
            # Modern LOGIN_START (1.20.2+): name and UUID; no credentials or encryption response.
            sock.sendall(packet(hello) + packet(b"\x00" + string(name) + uuid.uuid4().bytes))
            length = read_varint(sock)
            if not 1 <= length <= 65536:
                raise ValueError("Invalid packet length")
            packet_id = read_varint(sock)
            outcome = {0: "disconnect", 1: "encryption_request", 2: "login_success",
                       3: "compression_negotiation"}.get(packet_id, "unexpected_packet")
            # Stop at first response, including compression. Never enter PLAY or send commands.
    except socket.timeout:
        outcome = "timeout"
    except ConnectionRefusedError:
        outcome = "connection_refused"
    except EOFError:
        outcome = "closed"
    except (OSError, ValueError):
        outcome = "transport_error"
    return outcome, time.monotonic_ns() - start


def discover_protocol(port):
    """One bounded local status query; retain only the protocol number from the response."""
    with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
        sock.settimeout(2)
        hello = b"\x00" + varint(-1) + string("127.0.0.1") + struct.pack(">H", port) + b"\x01"
        sock.sendall(packet(hello) + packet(b"\x00"))
        packet_length = read_varint(sock)
        if not 2 <= packet_length <= 65536 or read_varint(sock) != 0:
            raise ValueError("Invalid local status packet")
        length = read_varint(sock)
        if not 1 <= length <= packet_length - 2:
            raise ValueError("Invalid local status length")
        data = bytearray()
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise EOFError("Local status connection closed")
            data.extend(chunk)
        return int(json.loads(data)["version"]["protocol"])


def bounded_integer(low, high):
    def parse(value):
        number = int(value)
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f"Expected {low}..{high}")
        return number
    return parse


def run(port, protocol, connections=20, concurrency=2, rate=2, pattern="reconnect"):
    # Enforce limits for callers importing this module, too. No destination override is exposed.
    for value, low, high in ((port, 1, 65535), (connections, 1, 200),
                             (concurrency, 1, 8), (rate, 1, 20)):
        if not low <= value <= high:
            raise ValueError("Outside benchmark limits")
    if pattern not in ("reconnect", "username-burst"):
        raise ValueError("Unknown pattern")
    if protocol is None:
        protocol = discover_protocol(port)
    if not 764 <= protocol <= 100000:
        raise ValueError("Modern LOGIN_START requires protocol 764 (1.20.2) or newer")
    started = time.monotonic()
    deadline = started + 45
    jobs = []
    run_id = uuid.uuid4().hex[:6]
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        for index in range(connections):
            # At most concurrency outstanding sockets/tasks; there is no unbounded future queue.
            if index >= concurrency:
                jobs[index - concurrency].result()
            target = started + index / rate
            if target >= deadline or time.monotonic() >= deadline:
                break
            time.sleep(max(0, target - time.monotonic()))
            # No catch-up burst after a slow response: maintain spacing between submissions.
            name = f"Agx{run_id}" if pattern == "reconnect" else f"Agx{run_id}{index:03}"
            jobs.append(executor.submit(attempt, port, protocol, name))
            started = max(started, time.monotonic() - index / rate)
        resolved = [job.result() for job in jobs]
    durations = sorted(duration / 1_000_000 for _, duration in resolved)
    def percentile(fraction):
        return durations[max(0, math.ceil(len(durations) * fraction) - 1)] if durations else None
    return {"scope": "loopback LOGIN first response; excludes PLAY, password login and Mojang verification",
            "port": port, "protocol": protocol, "pattern": pattern, "submitted": len(resolved),
            "rate_limit_per_second": rate, "maximum_concurrency": concurrency,
            "outcomes": dict(collections.Counter(outcome for outcome, _ in resolved)),
            "latency_ms": {"p50": percentile(.5), "p95": percentile(.95), "p99": percentile(.99)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=bounded_integer(1, 65535))
    parser.add_argument("--protocol", type=bounded_integer(764, 100000), help="Defaults to one local status query")
    parser.add_argument("--connections", type=bounded_integer(1, 200), default=20)
    parser.add_argument("--concurrency", type=bounded_integer(1, 8), default=2)
    parser.add_argument("--rate", type=bounded_integer(1, 20), default=2)
    parser.add_argument("--pattern", choices=("reconnect", "username-burst"), default="reconnect")
    parser.add_argument("--output", type=Path, default=Path("build/reports/benchmarks/local-login.json"))
    args = parser.parse_args()
    report = run(args.port, args.protocol, args.connections, args.concurrency, args.rate, args.pattern)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    # All transport failures are an invalid measurement, not successful guard validation.
    return 0 if any(report["outcomes"].get(kind, 0) for kind in (
        "disconnect", "encryption_request", "login_success", "compression_negotiation")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
