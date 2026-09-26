<?php
$subject=['a'=>[1,2],'b'=>3];
echo match($subject){['b'=>3,'a'=>[1,2]]=>'order-wrong',['a'=>[1,'2'],'b'=>3]=>'type-wrong',['a'=>[1,2],'b'=>3]=>'exact',default=>'wrong'};
