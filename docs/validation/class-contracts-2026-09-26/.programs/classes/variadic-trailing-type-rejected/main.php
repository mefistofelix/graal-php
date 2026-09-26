<?php
interface I{function f(int ...$x);} class C implements I{function f(int $a=0,string ...$x){}}