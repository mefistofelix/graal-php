<?php
class Animal {} class Cat extends Animal {}
interface Factory { function make(): Animal; }
class CatFactory implements Factory { function make(): Cat { return new Cat; } }
echo get_class((new CatFactory)->make());
