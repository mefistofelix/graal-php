<?php
interface Greeter { public function greet(string $name): string; }
class Greeting implements Greeter { public function greet(string $name): string { return 'hello '.$name; } }
function callGreeter(Greeter $value): Greeter { echo $value->greet('world'); return $value; }
echo ':', get_class(callGreeter(new Greeting)), ':', (new Greeting) instanceof Greeter;
