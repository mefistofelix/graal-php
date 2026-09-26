<?php
function defineLocal() { class LocalClass {} }
if (false) { class Absent {} }
echo class_exists('LocalClass', false) === false, ':', class_exists('Absent', false) === false, ':';
defineLocal(); if (true) { class Present extends LocalClass {} }
echo get_class(new Present);
