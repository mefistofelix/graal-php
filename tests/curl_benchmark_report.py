"""Recompute cURL benchmark summaries from retained CSVs; Python standard library only."""

import argparse
import csv
import json
import math
import re
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


FRACTIONS = {"p20": 0.20, "p50": 0.50, "p90": 0.90, "p95": 0.95, "p99": 0.99}


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def distribution(values: list[int | float]) -> dict[str, float]:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("Empty distribution")
    return {
        "samples": len(ordered),
        "mean": statistics.mean(ordered),
        **{name: ordered[math.ceil(fraction * len(ordered)) - 1] for name, fraction in FRACTIONS.items()},
        "max": ordered[-1],
    }


def check_close(actual: float, recorded: str, tolerance: float, label: str) -> None:
    if not math.isfinite(actual) or not math.isfinite(float(recorded)) or abs(actual - float(recorded)) > tolerance:
        raise AssertionError(f"{label}: recomputed {actual}, recorded {recorded}")


def key(row: dict[str, str]) -> tuple[str, int, int]:
    return row["runtime"], int(row["repetition"]), int(row["parked"])


def variability(values: list[float]) -> dict[str, float | list[float]]:
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "trials": values}


def utc(timestamp: str) -> datetime:
    value = datetime.fromisoformat(timestamp)
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def peer_gc_pauses(log: str, start: datetime, end: datetime) -> list[dict]:
    pauses = []
    for line in log.splitlines():
        match = re.match(r"\[([^]]+)\].*GC\(\d+\) Pause .* ([\d.]+)ms$", line)
        if match and start <= utc(match[1]) <= end:
            pauses.append({"reported_end_utc": match[1], "pause_ms": float(match[2])})
    return pauses


def report(directory: Path) -> dict:
    measurements = [row for row in rows(directory / "results.csv") if row["phase"] == "curl"]
    latency_rows = {key(row): row for row in rows(directory / "latency.csv")}
    memory_rows = {key(row): row for row in rows(directory / "memory.csv")}
    peer_log = (directory / "peer-gc.log").read_text(encoding="utf-8") if (directory / "peer-gc.log").exists() else None
    events = defaultdict(dict)
    for row in rows(directory / "events.csv"):
        if row["event"] in events[key(row)]:
            raise AssertionError("Duplicate control event")
        events[key(row)][row["event"]] = row
    groups = defaultdict(list)
    trials = []
    all_latencies = defaultdict(list)
    all_rss = defaultdict(list)
    for measurement in measurements:
        identity = key(measurement)
        runtime, repetition, parked = identity
        suffix = f"{runtime}-{repetition}-{parked}"
        group = runtime, parked
        trial_events = events[identity]
        if set(trial_events) != {"/begin", "/suspended", "/end"}:
            raise AssertionError(f"Missing controls for {suffix}")
        received = [int(trial_events[event]["received_elapsed_ns"]) for event in ("/begin", "/suspended", "/end")]
        if received != sorted(received) or received[0] < 0:
            raise AssertionError(f"Invalid control order for {suffix}")
        check_close(received[-1] / 1e9, measurement["seconds"], 0.000001, f"{suffix} wall-time boundary")
        memory = rows(directory / f"memory-{suffix}.csv")
        timestamps = [int(row["elapsed_ns"]) for row in memory]
        cpu = [int(row["cpu_ns"]) for row in memory]
        if timestamps != sorted(timestamps) or cpu != sorted(cpu) or cpu[0] < 0:
            raise AssertionError(f"Non-monotonic resource counters for {suffix}")
        rss = [int(row["rss_bytes"]) / 1048576 for row in memory]
        commit = [int(row["private_commit_bytes"]) / 1048576 for row in memory]
        if min(rss + commit) < 0:
            raise AssertionError(f"Negative memory counter for {suffix}")
        rss_stats = distribution(rss)
        commit_stats = distribution(commit)
        recorded_memory = memory_rows[identity]
        if len(memory) != int(recorded_memory["samples"]):
            raise AssertionError(f"Memory sample count for {suffix}")
        for quantile in (*FRACTIONS, "max"):
            check_close(rss_stats[quantile], recorded_memory[f"rss_{quantile}_mib"], 0.000501, f"{suffix} RSS {quantile}")
        for quantile in ("p50", "p99", "max"):
            check_close(commit_stats[quantile], recorded_memory[f"commit_{quantile}_mib"], 0.000501, f"{suffix} commit {quantile}")
        check_close(cpu[-1] / 1e9, measurement["cpu_seconds"], 0.000001, f"{suffix} CPU endpoint")
        trial = {
            "runtime": runtime, "repetition": repetition, "parked": parked,
            "requests": int(measurement["requests"]), "seconds": float(measurement["seconds"]),
            "requests_per_second": float(measurement["requests_per_second"]),
            "cpu_us_per_request": float(measurement["cpu_us_per_request"]), "rss_mib": rss_stats,
            "control_observation_delays_ms": {event: (int(row["observed_elapsed_ns"]) - int(row["received_elapsed_ns"])) / 1e6 for event, row in trial_events.items()},
        }
        durations = []
        if identity in latency_rows:
            recorded_latency = latency_rows[identity]
            durations = [int(row["nanoseconds"]) for row in rows(directory / f"latency-{suffix}.csv")]
            interval = int(recorded_latency["sample_every"])
            clients = int(measurement["clients"])
            iterations, remainder = divmod(trial["requests"], clients)
            expected = sum(1 + (iterations - 1 - client % interval) // interval for client in range(clients) if client % interval < iterations)
            if remainder or len(durations) != expected or len(durations) != int(recorded_latency["samples"]) or min(durations) < 0:
                raise AssertionError(f"Latency accounting for {suffix}")
            latency_stats = distribution([value / 1e6 for value in durations])
            for quantile in ("mean", *FRACTIONS, "max"):
                check_close(latency_stats[quantile], recorded_latency[f"{quantile}_ms"], 0.000001, f"{suffix} latency {quantile}")
            trial["latency_ms"] = latency_stats
            all_latencies[group].extend(value / 1e6 for value in durations)
        timeline_path = directory / f"timeline-{suffix}.csv"
        if timeline_path.exists():
            timeline = rows(timeline_path)
            if [int(row["duration_ns"]) for row in timeline] != durations:
                raise AssertionError(f"Timeline and latency disagree for {suffix}")
            clock = rows(directory / f"clock-{suffix}.csv")[0]
            uncertainty = int(clock["uncertainty_ns"])
            anchor = int(clock["host_suspended_elapsed_ns"])
            if anchor != received[1] or uncertainty != int(clock["guest_after_ns"]) - int(clock["guest_before_ns"]):
                raise AssertionError(f"Clock anchor for {suffix}")
            buckets = defaultdict(list)
            for row in timeline:
                start = int(row["guest_start_ns"])
                lower = int(row["start_elapsed_lower_ns"])
                upper = int(row["start_elapsed_upper_ns"])
                if lower != anchor + start - int(clock["guest_after_ns"]) or upper != lower + uncertainty:
                    raise AssertionError(f"Clock bounds for {suffix}")
                bucket = (lower + upper) // 1_000_000_000  # Midpoint, in half-second buckets.
                buckets[bucket].append(int(row["duration_ns"]) / 1e6)
            trial["timeline"] = {
                "clock_uncertainty_ms": uncertainty / 1e6,
                "bins": [{"start_seconds": bucket / 2, "latency_ms": distribution(values)} for bucket, values in sorted(buckets.items())],
                "slowest": sorted(timeline, key=lambda row: int(row["duration_ns"]), reverse=True)[:10],
            }
            log = (directory / f"{suffix}.log").read_text(encoding="utf-8")
            gc = re.findall(r"\[([\d.]+)s\] GC\(\d+\).*? ([\d.]+)ms", log)
            trial["gc_log"] = {
                "events": len(gc), "total_pause_ms": sum(float(duration) for _, duration in gc),
                "max_pause_ms": max((float(duration) for _, duration in gc), default=0),
                "entries": [{"reported_uptime_seconds": float(uptime), "pause_ms": float(duration)} for uptime, duration in gc],
            }
            payload_start = utc(trial_events["/suspended"]["received_utc"])
            payload_end = utc(trial_events["/end"]["received_utc"])
            jit_events = [line for line in log.splitlines() if line.startswith("[engine] opt")]
            payload_jit_events = [line for line in jit_events if "|UTC " in line
                                  and payload_start <= utc(line.split("|UTC ", 1)[1].split("|", 1)[0]) <= payload_end]
            trial["jit_log"] = {
                "tier1_done": sum("opt done" in line and "|Tier 1|" in line for line in log.splitlines()),
                "tier2_done": sum("opt done" in line and "|Tier 2|" in line for line in log.splitlines()),
                "invalidations": sum("opt inval." in line for line in log.splitlines()),
                "events": jit_events,
                "payload_events": payload_jit_events,
                "payload_invalidations": sum("opt inval." in line for line in payload_jit_events),
            }
            if peer_log is not None:
                pauses = peer_gc_pauses(peer_log, payload_start, payload_end)
                trial["peer_gc_log"] = {
                    "events": len(pauses), "total_pause_ms": sum(pause["pause_ms"] for pause in pauses),
                    "max_pause_ms": max((pause["pause_ms"] for pause in pauses), default=0), "entries": pauses,
                }
        trials.append(trial)
        groups[group].append(trial)
        all_rss[group].extend(rss)
    summaries = []
    for (runtime, parked), members in sorted(groups.items()):
        summary = {
            "runtime": runtime, "parked": parked, "trials": len(members),
            "requests_per_second": variability([row["requests_per_second"] for row in members]),
            "seconds": variability([row["seconds"] for row in members]),
            "cpu_us_per_request": variability([row["cpu_us_per_request"] for row in members]),
            "rss_mib_pooled": distribution(all_rss[runtime, parked]),
            "rss_p50_mib_per_trial": variability([row["rss_mib"]["p50"] for row in members]),
        }
        if all_latencies[runtime, parked]:
            summary["latency_ms_pooled"] = distribution(all_latencies[runtime, parked])
            summary["p99_ms_per_trial"] = variability([row["latency_ms"]["p99"] for row in members])
        summaries.append(summary)
    return {
        "method": "Fresh processes; no discarded warmup; nearest-rank pooled quantiles; controls kept separate. Timeline bins use bounded clock-alignment midpoints, not exact event timestamps. Native GC/JIT totals cover the complete child log including shutdown; payload JIT and optional peer GC are filtered between suspended/end control UTC receipts. No exact Native Image GC clock alignment or compiler-worker lifetime tracing. Logs are diagnostics, not causal attribution.",
        "trials": trials, "groups": summaries,
        "completed_trials": len(trials), "payload_requests": sum(row["requests"] for row in trials),
        "latency_samples": sum(len(values) for values in all_latencies.values()),
        "failures": (directory / "failure.txt").read_text(encoding="utf-8") if (directory / "failure.txt").exists() else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(report(args.directory), ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8", newline="\n")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
