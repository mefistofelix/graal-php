<?php
echo match(3){1,2,3,=>'group',default=>'other'},':';
try{$value=match(1){};}catch(UnhandledMatchError $error){echo 'empty:';}
try{$value=match(1){2=>'two'};}catch(Error $error){echo 'unhandled';}
