# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# Copyright (c) 2026 hamshamb
"""Authentication-evidence probe for a live Nexus hosting session.

Connects to the Nexus transport listener exactly like a guest, then speaks the
publicly documented Minecraft handshake + login-start exchange and reports the
server's first login-phase packet:

  0x01 (hello / encryption request)  -> online-mode auth is ENFORCED through the
                                        tunnel: the server demands RSA key exchange
                                        and Mojang session verification.
  0x02 (login success)               -> the server skipped authentication (the
                                        offline-profile path) - the auth-bypass
                                        failure this probe exists to detect.

Usage: python auth_probe.py <host> <port> [capability-token]

When a capability token is given (the M3 code-resolved path), the Nexus admission
preamble is sent before the Minecraft handshake, exactly as a real guest does.
Exit code 0 iff the encryption request was observed.
"""

import socket
import struct
import sys
import uuid

PROTOCOL_VERSION = 776  # Minecraft 26.2, from the client jar's version.json


def write_varint(value: int) -> bytes:
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        out += struct.pack("B", byte | (0x80 if value else 0))
        if not value:
            return out


def read_varint(sock: socket.socket) -> int:
    value = 0
    for i in range(5):
        (byte,) = sock.recv(1) or (None,)
        if byte is None:
            raise EOFError("connection closed while reading varint")
        value |= (byte & 0x7F) << (7 * i)
        if not byte & 0x80:
            return value
    raise ValueError("varint too long")


def packet(packet_id: int, payload: bytes) -> bytes:
    body = write_varint(packet_id) + payload
    return write_varint(len(body)) + body


def mc_string(text: str) -> bytes:
    data = text.encode("utf-8")
    return write_varint(len(data)) + data


def main() -> int:
    host, port = sys.argv[1], int(sys.argv[2])
    capability = sys.argv[3] if len(sys.argv) > 3 else None
    with socket.create_connection((host, port), timeout=10) as sock:
        if capability:
            token = capability.encode("utf-8")
            sock.sendall(b"NXSA" + struct.pack(">H", len(token)) + token)
        # Handshake: protocol version, address, port, next state 2 (login).
        sock.sendall(packet(0x00,
                            write_varint(PROTOCOL_VERSION)
                            + mc_string(host)
                            + struct.pack(">H", port)
                            + write_varint(2)))
        # Login start: name + UUID.
        sock.sendall(packet(0x00,
                            mc_string("NexusAuthProbe")
                            + uuid.uuid4().bytes))

        length = read_varint(sock)
        packet_id = read_varint(sock)
        print(f"server first login packet: id=0x{packet_id:02x} length={length}")

        if packet_id == 0x01:
            print("RESULT: ENCRYPTION REQUEST received - online-mode authentication "
                  "is enforced through the Nexus tunnel.")
            return 0
        if packet_id == 0x02:
            print("RESULT: LOGIN SUCCESS without encryption - the server skipped "
                  "authentication. THIS IS THE AUTH-BYPASS FAILURE.")
            return 2
        if packet_id == 0x00:
            # Disconnect with a JSON/NBT reason (e.g. outdated protocol).
            rest = sock.recv(min(length, 4096))
            print(f"RESULT: DISCONNECT during login: {rest[:512]!r}")
            return 3
        print("RESULT: unexpected packet - inspect manually.")
        return 4


if __name__ == "__main__":
    sys.exit(main())
