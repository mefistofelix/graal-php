<?php
function subject(){Async\delay(1);return [1,2];}
function condition($n){Async\delay(1);return [1,$n];}
function result(){Async\delay(1);return [9];}
echo (match(subject()){condition(0)=>'wrong',condition(2)=>result(),default=>'bad'})[0];
