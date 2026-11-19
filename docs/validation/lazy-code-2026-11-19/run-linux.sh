#!/usr/bin/env bash
set -euo pipefail

before=$(realpath "${1:?specify the baseline executable}")
after=$(realpath "${2:?specify the candidate executable}")
export LC_ALL=C
output=$(mktemp -d build/lazy-code-benchmark.XXXXXX)
printf 'runtime,unused_files,run,elapsed_s,user_s,system_s,max_rss_kib,output\n' > "$output/results.csv"
sha256sum "$before" "$after" | awk '{print $1}' > "$output/sha256.txt"

for unused in 0 512; do
    project="$output/root-$unused"
    mkdir -p "$project/lib"
    cat > "$project/main.php" <<'PHP'
<?php
$path = 'lib/used.php';
require_once $path;
echo loaded_value();
PHP
    printf '%s\n' '<?php function loaded_value() { return 27; }' > "$project/lib/used.php"
    for ((index=1; index<=unused; index++)); do
        printf -v module 'Unloaded%04d' "$index"
        cat > "$project/lib/$module.php" <<PHP
<?php
class $module {
    private int \$value = $index;
    public function build(int \$rows = 3): array {
        \$result = ['sum' => 0, 'rows' => []];
        for (\$i = 0; \$i < \$rows; \$i++) {
            \$result['rows'][] = [\$i, \$this->value, \$i + \$this->value];
            \$result['sum'] += \$i + \$this->value;
        }
        return \$result;
    }
}
PHP
    done
done

for run in 1 2 3 4 5 6 7 8 9 10; do
    order=(before after)
    if ((run % 2 == 0)); then order=(after before); fi
    for unused in 0 512; do
        for runtime in "${order[@]}"; do
            executable=$before
            if [[ $runtime == after ]]; then executable=$after; fi
            sample="$output/$runtime-$unused-$run"
            start=${EPOCHREALTIME/./}
            /usr/bin/time -f '%U,%S,%M' -o "$sample.time" \
                "$executable" "$output/root-$unused/main.php" > "$sample.out" 2> "$sample.err"
            end=${EPOCHREALTIME/./}
            elapsed=$((end - start))
            if [[ $(<"$sample.out") != 27 ]] || [[ -s "$sample.err" ]]; then
                printf 'Unexpected output in %s\n' "$sample" >&2
                exit 1
            fi
            printf '%s,%s,%s,%d.%06d,%s,27\n' "$runtime" "$unused" "$run" \
                "$((elapsed / 1000000))" "$((elapsed % 1000000))" "$(<"$sample.time")" >> "$output/results.csv"
        done
    done
done
printf '%s\n' "$output"
