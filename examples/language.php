<?php
namespace Demo;

class BaseCounter {
    protected int $value = 0;
    public static int $created = 0;

    public function __construct(int $initial = 1) {
        $this->value = $initial;
        self::$created++;
    }

    public function add(int $amount = 1): int {
        return $this->value += $amount;
    }

    public function callback() {
        return fn(int $amount): int => $this->add($amount);
    }
}

class Counter extends BaseCounter {
    public function add(int $amount = 1): int {
        return parent::add($amount * 2);
    }
}

function sum(int ...$values): int {
    $total = 0;
    foreach ($values as $value) $total += $value;
    return $total;
}

$counter = new Counter(10);
$alias = $counter;
$callback = $counter->callback();
echo $callback(3), ':', $alias->add(), ':', Counter::$created, "\n";

$captured = [1, 2];
$reference = 4;
$closure = function(int $extra = 2) use ($captured, &$reference): int {
    $captured[0] = 90;
    return $reference += $extra;
};
$captured[0] = 20;
echo $closure(), ':', $closure(3), ':', $reference, ':', $captured[0], "\n";

$array = [];
$array[] = count($array);
$array[] = count($array);
$index = 0;
$array[$index++] += 5;
echo $array[0], ':', $array[1], ':', $index, "\n";

$total = 0;
for ($i = 0; $i < 6; $i++) {
    if ($i == 2) continue;
    $total += $i;
}
do { $total--; } while ($total > 10);
$fallback = $missing['key'] ?? 'fallback';
$missing['key'] ??= 8;
echo $total, ':', $fallback, ':', isset($missing['key']), ':', empty($missing['other']), "\n";
echo sum(1, 2, 3), ':', (0 ?: 7), ':', (true ? 8 : 9), "\n";
