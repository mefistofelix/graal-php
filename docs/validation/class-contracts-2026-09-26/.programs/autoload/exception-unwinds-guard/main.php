<?php
$calls = 0;
spl_autoload_register(function($name) use (&$calls) {
    if (++$calls === 1) throw new Exception('loader failed');
    eval('class ' . $name . ' {}');
});
try { class_exists('Loaded'); } catch (Exception $error) { echo 'caught:'; }
echo class_exists('Loaded'), ':', $calls;
