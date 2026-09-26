<?php
$value=1;function condition(){global $value;$value=2;return 1;}
echo match($value){condition()=>'one',2=>'two',default=>'none'},':',$value;
