<?php
class Base{function f():static{return $this;}} class C extends Base{function f():self{return $this;}}