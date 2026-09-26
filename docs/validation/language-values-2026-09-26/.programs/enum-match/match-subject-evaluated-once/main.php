<?php
$calls=0;function subject(){global $calls;$calls++;return 2;}
echo match(subject()){1=>'one',2=>'two',default=>'other'},':',$calls;
