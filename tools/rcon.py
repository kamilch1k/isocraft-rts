"""Send commands to a Minecraft server over RCON and print the replies.

    python rcon.py "time set day"
    python rcon.py -f commands.txt          # one command per line, blanks and # ignored

ponytail: raw sockets, no library. RCON is a four-field packet - length, id, type, payload - and
implementing it is smaller than the argument about which pip package to depend on.

This exists because the alternatives do not work. Piping commands to the server's stdin prepends a
UTF-8 BOM under PowerShell, so the first command arrives as "<BOM>time" and is rejected; redirecting
stdin from a file makes the console handler error on EOF. RCON is also the only one of the three
that returns the server's answer, which is what makes verification possible: /data get block reads
Create's own block entities back, so throughput is measured rather than guessed at.
"""
import argparse
import pathlib
import select
import socket
import struct
import sys

TYPE_LOGIN = 3
TYPE_COMMAND = 2


class Rcon:
    def __init__(self, host="127.0.0.1", port=25575, password="isorts", timeout=10.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self._next_id = 0
        self._send(TYPE_LOGIN, password)
        packet_id, _ = self._recv()
        if packet_id == -1:
            raise SystemExit("rcon: authentication failed")

    def _send(self, packet_type, body):
        self._next_id += 1
        payload = struct.pack("<ii", self._next_id, packet_type) + body.encode("utf8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self._next_id

    def _read_exactly(self, count):
        data = b""
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise SystemExit("rcon: connection closed")
            data += chunk
        return data

    def _recv(self):
        length = struct.unpack("<i", self._read_exactly(4))[0]
        body = self._read_exactly(length)
        packet_id, _ = struct.unpack("<ii", body[:8])
        return packet_id, body[8:-2].decode("utf8", errors="replace")

    def command(self, text):
        self._send(TYPE_COMMAND, text)
        # A single command can answer in several packets; drain whatever arrives promptly.
        reply = ""
        while True:
            _, part = self._recv()
            reply += part
            if not select.select([self.sock], [], [], 0.15)[0]:
                break
        return reply.strip()

    def close(self):
        self.sock.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("commands", nargs="*", help="commands to run, in order")
    ap.add_argument("-f", "--file", help="file of commands, one per line")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="isorts")
    ap.add_argument("-q", "--quiet", action="store_true", help="only print non-empty replies")
    args = ap.parse_args()

    commands = list(args.commands)
    if args.file:
        for line in pathlib.Path(args.file).read_text(encoding="utf8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                commands.append(line)
    if not commands:
        sys.exit("nothing to send")

    rcon = Rcon(args.host, args.port, args.password)
    try:
        for command in commands:
            reply = rcon.command(command)
            if reply:
                print(f"{command}\n  -> {reply}")
            elif not args.quiet:
                print(command)
    finally:
        rcon.close()


if __name__ == "__main__":
    main()
