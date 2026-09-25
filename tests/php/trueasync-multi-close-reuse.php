<?php
// Standalone reproducer, deliberately outside the passing differential corpus.
// TrueAsync 0.10.0 Windows prints the result then exits with 0xC0000005.
// Omitting unset($multi) instead leaves a libuv timer at shutdown in that binary.
$multi = curl_multi_init();
$easy = curl_init('unsupported-scheme://localhost/');
curl_setopt($easy, CURLOPT_RETURNTRANSFER, true);
curl_multi_add_handle($multi, $easy);
curl_multi_exec($multi, $running);
curl_multi_close($multi);
curl_multi_add_handle($multi, $easy);
curl_multi_exec($multi, $running);
unset($multi);
echo curl_exec($easy) === false, ':', curl_errno($easy);
