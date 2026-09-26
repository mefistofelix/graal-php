<?php
declare(strict_types=1);$task=Async\spawn(function(int $n){return $n;},'2');
echo Async\await($task);
