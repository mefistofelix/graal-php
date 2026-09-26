<?php
$subject=[1];
function condition(){global $subject;$subject[0]=2;return [0];}
$result=match($subject){condition()=>'wrong',[1]=>[3],default=>'bad'};
echo $result[0],':',$subject[0];
