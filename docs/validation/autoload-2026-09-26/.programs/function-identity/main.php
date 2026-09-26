<?php
function Loader($name) {}
function Other($name) {}
spl_autoload_register('LoAdEr'); spl_autoload_register('Other');
spl_autoload_register('loader', prepend: true);
foreach (spl_autoload_functions() as $callback) echo $callback, ':';
echo spl_autoload_unregister('LOADER'), ':', spl_autoload_unregister('Loader'), ':', count(spl_autoload_functions());
