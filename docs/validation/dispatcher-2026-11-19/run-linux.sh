#!/usr/bin/env bash
set -euo pipefail

before=$(realpath "${1:?specify the baseline executable}")
after=$(realpath "${2:?specify the candidate executable}")
export LC_ALL=C
output=$(mktemp -d build/dispatcher-benchmark.XXXXXX)
printf 'runtime,idle_scopes,run,elapsed_s,user_s,system_s,max_rss_kib,output\n' > "$output/results.csv"
sha256sum "$before" "$after" | awk '{print $1}' > "$output/sha256.txt"
cat > "$output/task.php" <<'PHP'
<?php
$idle = intval(getenv('IDLE_SCOPES'));
$scopes = [];
for ($i = 0; $i < $idle; $i++) $scopes[] = Async\Scope::inherit();
for ($i = 0; $i < 32768; $i++) Async\suspend();
echo count($scopes), ':', $i;
PHP

for run in 1 2 3 4 5; do
    order=(before after)
    if ((run % 2 == 0)); then order=(after before); fi
    for idle in 0 2048; do
        for runtime in "${order[@]}"; do
            executable=$before
            if [[ $runtime == after ]]; then executable=$after; fi
            sample="$output/$runtime-$idle-$run"
            start=${EPOCHREALTIME/./}
            IDLE_SCOPES="$idle" /usr/bin/time -f '%U,%S,%M' -o "$sample.time" \
                "$executable" "$output/task.php" > "$sample.out" 2> "$sample.err"
            end=${EPOCHREALTIME/./}
            elapsed=$((end - start))
            if [[ $(<"$sample.out") != "$idle:32768" ]] || [[ -s "$sample.err" ]]; then
                printf 'Unexpected output in %s\n' "$sample" >&2
                exit 1
            fi
            printf '%s,%s,%s,%d.%06d,%s,%s:32768\n' "$runtime" "$idle" "$run" \
                "$((elapsed / 1000000))" "$((elapsed % 1000000))" "$(<"$sample.time")" "$idle" >> "$output/results.csv"
        done
    done
done
printf '%s\n' "$output"
