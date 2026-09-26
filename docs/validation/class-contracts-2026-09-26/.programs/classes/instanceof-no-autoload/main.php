<?php
class C {} $calls=0;
spl_autoload_register(function($name)use(&$calls){$calls++;});
$object=new C; $name='C';
echo $object instanceof C, ':', $object instanceof $name, ':', $object instanceof Missing ? 'T':'F', ':', $calls;
$name='int'; echo ':', 1 instanceof $name ? 'T':'F';
