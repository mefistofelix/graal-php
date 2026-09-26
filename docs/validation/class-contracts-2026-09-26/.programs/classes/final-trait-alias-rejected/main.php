<?php
trait T{function value(){}} class Base{use T{value as final;}} class Child extends Base{function value(){}}