<?php
enum E {case A;case a;}
echo e::A->name, ':', E::a->name, ':', E::A !== E::a;
try {echo E::Missing;}catch(Error $error){echo ':missing';}
