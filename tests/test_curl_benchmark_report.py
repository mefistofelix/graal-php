"""Deterministic checks for offline cURL report calculations and log parsing."""

import runpy
import unittest
from datetime import datetime, timezone
from pathlib import Path


report = runpy.run_path(str(Path(__file__).with_name("curl_benchmark_report.py")))


class CurlBenchmarkReportTest(unittest.TestCase):
    def test_nearest_rank(self):
        result = report["distribution"]([9, 1, 2, 2, 8])
        self.assertEqual(result, {"samples": 5, "mean": 4.4, "p20": 1, "p50": 2, "p90": 9, "p95": 9, "p99": 9, "max": 9})

    def test_empty_distribution(self):
        with self.assertRaises(ValueError):
            report["distribution"]([])

    def test_trial_variability(self):
        self.assertEqual(report["variability"]([8, 2, 4, 6]), {"median": 5, "min": 2, "max": 8, "trials": [8, 2, 4, 6]})

    def test_rounding_tolerance(self):
        report["check_close"](1.23456, "1.235", 0.000501, "rounding")
        with self.assertRaises(AssertionError):
            report["check_close"](1.23456, "1.236", 0.000501, "changed measurement")

    def test_nonfinite_values_rejected(self):
        for actual, recorded in [(float("nan"), "1"), (1, "nan"), (float("inf"), "inf"), (1, "-inf")]:
            with self.subTest(actual=actual, recorded=recorded), self.assertRaises(AssertionError):
                report["check_close"](actual, recorded, 0.001, "nonfinite")

    def test_timestamp_formats(self):
        expected = datetime(2026, 9, 25, 22, 0, tzinfo=timezone.utc)
        for timestamp in ["2026-09-25T22:00:00Z", "2026-09-25T22:00:00.000+0000", "2026-09-25T22:00:00"]:
            with self.subTest(timestamp=timestamp):
                self.assertEqual(report["utc"](timestamp), expected)

    def test_peer_gc_window_and_completed_pauses(self):
        log = """[2026-09-25T21:59:59.999+0000][1ms][info][gc] GC(0) Pause Young 9M->2M 1.0ms
[2026-09-25T22:00:00.000+0000][2ms][info][gc,start] GC(1) Pause Young (Normal)
[2026-09-25T22:00:00.001+0000][3ms][info][gc] GC(1) Pause Young (Normal) 9M->2M 2.5ms
[2026-09-25T22:00:00.002+0000][4ms][info][gc] GC(2) Concurrent Mark Cycle 0.5ms
[2026-09-25T22:00:01.001+0000][1003ms][info][gc] GC(3) Pause Young 9M->2M 3.0ms
"""
        pauses = report["peer_gc_pauses"](log, report["utc"]("2026-09-25T22:00:00Z"), report["utc"]("2026-09-25T22:00:01Z"))
        self.assertEqual(pauses, [{"reported_end_utc": "2026-09-25T22:00:00.001+0000", "pause_ms": 2.5}])


if __name__ == "__main__":
    unittest.main()
