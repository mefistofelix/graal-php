<?php

class Arguments implements Iterator {
    public $position = 0;

    public function rewind(): void { $this->position = 0; }
    public function valid(): bool { return $this->position < 2; }
    public function current(): mixed {
        Async\delay(1);
        return $this->position === 0 ? 2 : 5;
    }
    public function key(): mixed { return $this->position === 0 ? 'b' : 'a'; }
    public function next(): void { $this->position++; }
}

function total($a, $b, $scale = 1) {
    return ($a + $b) * $scale;
}

echo total(...new Arguments, scale: 3), "\n";

$base = ['name' => 'graalphp', 7];
$merged = ['name' => 'runtime', ...$base, 'ready' => true];
foreach ($merged as $key => $value) {
    echo $key, '=', $value, ';';
}
echo "\n";
