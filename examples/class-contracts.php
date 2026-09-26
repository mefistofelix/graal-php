<?php
namespace ClassContractsDemo;

interface Greeting {
    public function message(string $name): string;
}

trait DefaultGreeting {
    public function message(string $name): string {
        \Async\delay(1);
        return self::PREFIX . ' ' . $name;
    }
}

abstract class GreetingBase implements Greeting {
    public const string PREFIX = 'Hello';
}

final class Greeter extends GreetingBase {
    use DefaultGreeting {
        message as greet;
    }
}

$greeter = new Greeter;
echo $greeter->greet('from a trait'), "\n";
echo $greeter instanceof Greeting ? 'contract satisfied' : 'unexpected', "\n";
