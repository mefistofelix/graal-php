"""Cold CLI comparison. Run from the root with two JARs or with PHP and GraalPHP executables."""
import csv
import ctypes
from ctypes import wintypes
import hashlib
from itertools import product
import os
from pathlib import Path
import subprocess
import sys
import time

class Memory(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("faults", wintypes.DWORD),
                ("peak_rss", ctypes.c_size_t), ("rss", ctypes.c_size_t),
                ("remaining", ctypes.c_size_t * 6)]

java = Path("tools/graalvm-25.4.4.1.1+1.1/bin/java.exe")
script = Path(__file__).with_name("walk.php")
products = [Path(arg) for arg in sys.argv[1:]]
if len(products) != 2:
    raise SystemExit("Expected two CLI products")
writer = csv.writer(sys.stdout, lineterminator="\n")
writer.writerow(["variant", "product_sha256", "items", "layout", "run", "wall_ms", "cpu_ms", "peak_working_set_mib", "checksum"])
for size, layout in product((256, 1024), ("dense", "hole")):
    for run in range(1, 4):
        for variant in ([0, 1] if run % 2 else [1, 0]):
            binary = products[variant]
            digest = hashlib.sha256(binary.read_bytes()).hexdigest()
            if binary.suffix == ".jar":
                command = [java, "--enable-native-access=ALL-UNNAMED", "-jar", binary]
            elif binary.name.lower() == "php.exe":
                command = [binary, "-n"]
            else:
                command = [binary]
            start = time.perf_counter()
            child = subprocess.Popen([*command, script],
                                     env={**os.environ, "ARRAY_ITEMS": str(size), "ARRAY_LAYOUT": layout},
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            handle = wintypes.HANDLE(int(child._handle))
            memory = Memory()
            peak = 0
            while True:
                if ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), ctypes.sizeof(memory)):
                    peak = max(peak, memory.peak_rss)
                state = ctypes.windll.kernel32.WaitForSingleObject(handle, 10)
                if state == 0:
                    break
                if state != 258:
                    raise ctypes.WinError()
            elapsed = time.perf_counter() - start
            stdout, stderr = child.communicate()
            if child.returncode or stdout.strip() != str(size * (size - 1) - (2 if layout == "hole" else 0)).encode():
                raise RuntimeError((child.returncode, stdout, stderr))
            times = [ctypes.c_ulonglong() for _ in range(4)]
            if not ctypes.windll.kernel32.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                raise ctypes.WinError()
            cpu = (times[2].value + times[3].value) / 10000
            writer.writerow([("before", "after")[variant], digest, size, layout, run, round(elapsed * 1000, 2),
                             round(cpu, 2), round(peak / 1048576, 2), int(stdout)])
            sys.stdout.flush()
