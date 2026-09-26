<?php
foreach([0,'0',false,null,1,1.0]as$value){echo match($value){0=>'int0','0'=>'string0',false=>'false',null=>'null',1=>'int1',1.0=>'float1'},';';}
