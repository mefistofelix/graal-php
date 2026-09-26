<?php
trait A{function value(){}} class C{use A{A::value insteadof Missing;}}