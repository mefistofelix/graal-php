<?php
try { if (true) { class Broken extends Missing {} } } catch (Error $error) { echo 'missing:'; }
echo class_exists('Broken', false) === false;
