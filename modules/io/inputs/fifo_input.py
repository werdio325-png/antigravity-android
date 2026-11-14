#!/usr/bin/env python3
"""
fifo_input.py - Named FIFO Pipe input listener. Reads incoming JSON events and publishes to broker.
"""
import os
import sys
import tempfile
import errno

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
IO_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, os.path.join(IO_ROOT, "core_bus"))

import envelope
from event_broker import EventBroker

FUSE_PREFIXES = ("/storage/", "/sdcard/", "/mnt/sdcard/", "/mnt/runtime/")

def get_safe_runtime_dir() -> str:
    """
    Returns a writable directory in internal storage safe for FIFOs and UNIX sockets.
    Avoids FUSE filesystems (/storage/emulated/0) where mkfifo/AF_UNIX fail with PermissionError.
    """
    candidates = [
        os.environ.get("AGY_RUNTIME_DIR"),
        "/data/user/0/dev.continuum.antigravity/files/runtime",
        "/data/user/0/dev.continuum.antigravity.dev/files/runtime",
        os.environ.get("TMPDIR"),
        tempfile.gettempdir(),
    ]
    for c in candidates:
        if c:
            try:
                os.makedirs(c, exist_ok=True)
                if os.path.isdir(c) and os.access(c, os.W_OK):
                    return c
            except Exception:
                pass
    return tempfile.gettempdir()

def get_safe_pipe_path(fifo_path: str) -> str:
    """
    Resolves a safe FIFO path. If the requested path is located on an Android FUSE
    filesystem (e.g. /storage/emulated/0), redirects it to a safe internal runtime directory.
    """
    real_path = os.path.abspath(fifo_path)
    if real_path.startswith(FUSE_PREFIXES):
        safe_dir = get_safe_runtime_dir()
        return os.path.join(safe_dir, os.path.basename(fifo_path))
    return real_path

def write_fifo_nonblocking(fifo_path: str, data: str) -> bool:
    """
    Safely writes data to a named FIFO in non-blocking mode.
    Returns True if data was written, False if no reader is open (ENXIO) or write failed.
    Prevents hangs when no reader process is actively connected to the FIFO.
    """
    safe_path = get_safe_pipe_path(fifo_path)
    if not os.path.exists(safe_path):
        return False
    try:
        fd = os.open(safe_path, os.O_WRONLY | os.O_NONBLOCK)
        try:
            payload = data if data.endswith("\n") else data + "\n"
            os.write(fd, payload.encode("utf-8"))
            return True
        finally:
            os.close(fd)
    except OSError as e:
        if e.errno == errno.ENXIO:
            # No reader listening
            return False
        return False

def run_fifo_listener(fifo_path: str, broker: EventBroker, max_events: int = 0):
    safe_path = get_safe_pipe_path(fifo_path)
    if not os.path.exists(safe_path):
        try:
            os.mkfifo(safe_path)
        except (PermissionError, OSError):
            safe_dir = get_safe_runtime_dir()
            safe_path = os.path.join(safe_dir, os.path.basename(fifo_path))
            if not os.path.exists(safe_path):
                os.mkfifo(safe_path)

    events_read = 0
    try:
        while True:
            # Opening FIFO for reading blocks until a writer connects
            with open(safe_path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        data = envelope.deserialize_event(line)
                        broker.publish(data)
                        events_read += 1
                        if 0 < max_events <= events_read:
                            return events_read
                    except Exception as e:
                        print(f"[FifoInput] Bad event skipped: {e}", file=sys.stderr)
    except KeyboardInterrupt:
        pass
    return events_read

def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print("Usage: fifo_input.py <fifo_path>")
        sys.exit(1)
    
    path = sys.argv[1]
    safe_path = get_safe_pipe_path(path)
    if safe_path != path:
        print(f"[FifoInput] Redirected FUSE path '{path}' to safe path '{safe_path}'")
    broker = EventBroker()
    print(f"[FifoInput] Listening on FIFO: {safe_path}")
    run_fifo_listener(safe_path, broker)

if __name__ == "__main__":
    main()
