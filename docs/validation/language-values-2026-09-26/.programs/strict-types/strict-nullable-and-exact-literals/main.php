<?php
declare(strict_types=1);
function n(?int $n){return $n;}function yes(true $n){return $n;}
echo n(null)===null,':',n(2),':',yes(true);
try{n('2');}catch(TypeError$e){echo ':nullable';}
try{yes(1);}catch(TypeError$e){echo ':literal';}
