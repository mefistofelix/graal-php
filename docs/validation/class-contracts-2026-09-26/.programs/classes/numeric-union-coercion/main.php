<?php
function numeric(int|float $value) { echo $value === 1.5 ? 'float' : 'other'; }
numeric('1.5');
try { numeric('not-numeric'); } catch(TypeError $error) {echo ':type';}
