<?php
spl_autoload_register(function($name){echo $name,':'; if($name==='I')eval('interface I{}'); else if($name==='T')eval('trait T{}'); else eval('class Base{}');});
class C extends Base implements I {use T;}
echo (new C) instanceof I;
