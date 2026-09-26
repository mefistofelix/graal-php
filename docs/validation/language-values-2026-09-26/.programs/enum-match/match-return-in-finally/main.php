<?php
function choose($n){try{return match($n){1=>[4],default=>throw new Exception('missing')};}finally{echo 'finally:';}}
echo choose(1)[0],':';try{choose(2);}catch(Exception $error){echo 'caught';}
