<?php
interface A{const X=1;} interface B{final const X=1;} class C implements A,B{const X=2;}