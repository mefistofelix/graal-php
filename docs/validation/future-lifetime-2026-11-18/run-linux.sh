#!/usr/bin/env bash
set -euo pipefail

before=${1:?specify the baseline executable}
after=${2:?specify the candidate executable}
iterations=4096
width=128
expected=$((iterations * (iterations + width - 2)))
export FUTURE_ITERATIONS=$iterations FUTURE_PAYLOAD=$width LC_ALL=C

output=$(mktemp -d build/future-lifetime.XXXXXX)
sha256sum "$before" "$after" > "$output/products.txt"
printf 'runtime,mode,run,iterations,width,elapsed_s,user_s,system_s,max_rss_kib,checksum\n' > "$output/results.csv"
for run in 1 2 3; do
    order=(before after)
    if ((run % 2 == 0)); then order=(after before); fi
    for mode in completed channel spawn; do
        for variant in "${order[@]}"; do
            executable=$before
            if [[ $variant == after ]]; then executable=$after; fi
            sample="$output/$variant-$mode-$run"
            read -r start _ < /proc/uptime
            FUTURE_MODE="$mode" /usr/bin/time -f '%U,%S,%M' -o "$sample.time" \
                "$executable" tests/php/future-lifetime-benchmark.php > "$sample.out" 2> "$sample.err"
            read -r end _ < /proc/uptime
            elapsed=$(awk -v start="$start" -v end="$end" 'BEGIN { printf "%.2f", end - start }')
            if [[ $(<"$sample.out") != "$expected" ]] || [[ -s "$sample.err" ]]; then
                printf 'Unexpected result in %s\n' "$sample" >&2
                exit 1
            fi
            printf '%s,%s,%s,%s,%s,%s,%s,%s\n' "$variant" "$mode" "$run" "$iterations" "$width" \
                "$elapsed" "$(<"$sample.time")" "$expected" >> "$output/results.csv"
        done
    done
done
printf '%s\n' "$output"
