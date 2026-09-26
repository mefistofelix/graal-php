<?php
class C{public $value=1;}$object=new C;
function condition(){global $object;$object->value=2;return 1;}
echo match($object->value){condition()=>'one',2=>'two',default=>'none'},':',$object->value;
