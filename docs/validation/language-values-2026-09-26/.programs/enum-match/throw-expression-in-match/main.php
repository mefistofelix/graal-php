<?php
function choose($value){return match($value){1=>'one',default=>throw new Exception('missing')};}
echo choose(1),':';try{choose(2);}catch(Exception $error){echo $error->getMessage();}finally{echo ':finally';}
