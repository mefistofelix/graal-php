<?php
$iterator = new ArrayIterator(['first' => 1, 'second' => 2]);
foreach ($iterator as $key => $value) {
    echo $key, ':', $value, "\n";
}
$clone = clone $iterator;
$clone->append(3);
$clone->seek(2);
echo $clone->key(), ':', $clone->current(), "\n";
echo count($iterator), "\n";
