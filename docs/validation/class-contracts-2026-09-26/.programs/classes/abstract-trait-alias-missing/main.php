<?php
trait T{abstract function f();} class C{use T{f as g;} function f(){}}