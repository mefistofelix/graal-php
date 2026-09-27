<?php
declare(strict_types=1);

function child(): Generator {
    $received = yield 'first' => 1;
    yield 2;
    return $received;
}

function values(): Generator {
    try {
        $result = yield from child();
        echo 'child-return:', $result, "\n";
        yield 'done';
        return $result + 1;
    } finally {
        echo "cleanup\n";
    }
}

$generator = values();
echo $generator->key(), ':', $generator->current(), "\n";
echo $generator->send(7), "\n";
$generator->next();
echo $generator->current(), "\n";
$generator->next();
echo $generator->getReturn(), "\n";
