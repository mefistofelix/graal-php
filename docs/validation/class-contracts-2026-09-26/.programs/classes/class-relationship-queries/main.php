<?php
error_reporting(0);
interface I {} class Base implements I {} class Child extends Base {} trait T {}
echo is_a(new Child,'I'), ':', is_subclass_of(new Child,'Base'), ':', is_subclass_of(new Child,'Child') === false;
echo ':', is_a('Child','I') === false, ':', is_a('Child','I',true), ':', is_subclass_of('Child','I');
echo ':', is_a('T','T',true) === false, ':', is_a(new Child,'mixed') === false;
