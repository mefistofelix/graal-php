<?php
foreach ([1, false, null, []] as $name) {
    try { new $name; } catch (Throwable $error) { echo get_class($error), ':'; }
}
class Existing {} $object = new Existing; echo get_class(new $object), ':';
try { class_exists([]); } catch (TypeError $error) { echo 'name:'; }
try { class_exists('Existing', []); } catch (TypeError $error) { echo 'flag'; }
