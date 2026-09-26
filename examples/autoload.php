<?php

$files = [
    AutoloadDemo\Greeting::class => __DIR__ . '/autoload/Greeting.php',
    AutoloadDemo\BaseGreeting::class => __DIR__ . '/autoload/BaseGreeting.php',
];

spl_autoload_register(function($name) use ($files) {
    if (isset($files[$name])) {
        // Loading stays on the current coroutine and may suspend.
        Async\delay(1);
        require $files[$name];
    }
});

echo (new AutoloadDemo\Greeting)->message(), "\n";
