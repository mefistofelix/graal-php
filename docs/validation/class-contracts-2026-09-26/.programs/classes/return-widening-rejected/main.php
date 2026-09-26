<?php
class Animal{} class Cat extends Animal{} interface I{function value():Cat;} class C implements I{function value():Animal{return new Animal;}}