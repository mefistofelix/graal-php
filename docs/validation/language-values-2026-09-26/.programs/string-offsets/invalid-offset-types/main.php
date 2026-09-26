<?php
$s='abc';foreach(['x','1.5','1e0','9223372036854775808',[],new stdClass]as$i){
    try{echo $s[$i];}catch(TypeError $e){echo 'type:';}
}
