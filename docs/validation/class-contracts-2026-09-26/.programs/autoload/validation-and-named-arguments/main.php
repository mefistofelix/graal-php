<?php
try { spl_autoload_register(callback: 'undefined_loader'); } catch (TypeError $error) { echo 'callback:'; }
try { spl_autoload_unregister('undefined_loader'); } catch (TypeError $error) { echo 'unregister:'; }
try { class_exists(); } catch (ArgumentCountError $error) { echo 'count:'; }
try { spl_autoload_functions(1); } catch (ArgumentCountError $error) { echo 'arity:'; }
try { class_exists(unknown: 'Missing'); } catch (Error $error) { echo 'name:'; }
echo class_exists(autoload: false, class: 'Missing') === false;
