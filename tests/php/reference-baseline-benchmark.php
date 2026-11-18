<?php

function next_value(int $value): int {
    return $value + 1;
}

$iterations = intval(getenv('REFERENCE_ITERATIONS'));
$mode = getenv('REFERENCE_MODE');
if ($mode === 'call') {
    $value = 0;
    for ($index = 0; $index < $iterations; ++$index) {
        $value = next_value($value);
    }
    echo $value, "\n";
} elseif ($mode === 'index') {
    $value = [[0]];
    for ($index = 0; $index < $iterations; ++$index) {
        $value[0][0] = next_value($value[0][0]);
    }
    echo $value[0][0], "\n";
} else {
    throw new ValueError('Expected REFERENCE_MODE=call or index');
}
