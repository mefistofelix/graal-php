<?php
declare(strict_types=1);

class Tasks implements IteratorAggregate, Countable {
    private array $values;
    public function __construct(array $values) { $this->values = $values; }
    public function getIterator(): Traversable { return new TaskIterator($this->values); }
    public function count(): int { return count($this->values); }
}

class TaskIterator implements Iterator {
    private array $values;
    private int $position = 0;
    public function __construct(array $values) { $this->values = $values; }
    public function rewind(): void { $this->position = 0; }
    public function valid(): bool { return $this->position < count($this->values); }
    public function current(): mixed {
        Async\delay(1);
        return $this->values[$this->position];
    }
    public function key(): mixed { return $this->position; }
    public function next(): void { $this->position++; }
}

$tasks = new Tasks(['parse', 'execute']);
foreach ($tasks as $key => $value) echo $key, ':', $value, "\n";
$copy = iterator_to_array($tasks, preserve_keys: false);
echo count($tasks), ':', $copy[1], "\n";
