<?php
$s='a0c';foreach([0,1,-1,3,-4,'1','01','x',[],null]as$i){
    echo isset($s[$i])?'set:':'no:';echo empty($s[$i])?'empty;':'value;';
}
