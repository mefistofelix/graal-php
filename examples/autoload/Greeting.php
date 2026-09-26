<?php
namespace AutoloadDemo;

class Greeting extends BaseGreeting {
    public function message(): string { return $this->prefix . ' from autoload'; }
}
