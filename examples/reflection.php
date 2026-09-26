<?php

#[Attribute(Attribute::TARGET_CLASS)]
class Service {
    public $name;
    public function __construct($name) { $this->name = $name; }
}

#[Service('demo')]
class Handler {
    #[ReturnTypeWillChange]
    public function value() { return 42; }
}

$class = new ReflectionClass(Handler::class);
$attribute = $class->getAttributes(Service::class)[0];
$service = $attribute->newInstance();

echo $class->getName(), ':', $service->name, ':', $class->getMethod('value')->getName(), "\n";
