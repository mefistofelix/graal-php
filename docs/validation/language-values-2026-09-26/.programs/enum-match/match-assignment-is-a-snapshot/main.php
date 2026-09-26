<?php
function condition(){global $value;$value=2;return 1;}
echo match($value=1){condition()=>'one',2=>'two',default=>'none'},':',$value;
