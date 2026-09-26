<?php
class Animal {} class Cat extends Animal {}
interface Handler { function handle(Cat $value): int; }
class H implements Handler { function handle(Animal $value): int { return 42; } }
echo (new H)->handle(new Animal), ':', (new H)->handle(new Cat);
