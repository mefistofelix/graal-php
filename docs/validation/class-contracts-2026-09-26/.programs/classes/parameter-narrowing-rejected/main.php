<?php
class Animal{} class Cat extends Animal{} interface I{function value(Animal $x);} class C implements I{function value(Cat $x){}}