<?php
$library = 'build/graalphp-native.dll';
$pending = ffi_call_async($library, 'gp_crc32', '(STRING):SINT64', 'hello');
echo 'async=', await($pending), "\n";
echo 'sqlite=', ffi_call($library, 'gp_sqlite_version', '():STRING'), "\n";
echo 'libuv=', ffi_call($library, 'gp_uv_version', '():STRING'), "\n";
echo 'regex=', ffi_call($library, 'gp_regex_match', '(STRING,STRING):SINT32', '^hello [a-z]+$', 'hello world'), "\n";
echo 'crc32=', ffi_call($library, 'gp_crc32', '(STRING):SINT64', 'hello'), "\n";
function twice($value) { return $value * 2; }
$callback = ffi_callback('twice');
echo 'callback=', ffi_call($library, 'gp_callback', '((SINT64):SINT64,SINT64):SINT64', $callback, 21), "\n";
function row($value) { echo 'query=', $value, "\n"; return 0; }
graal_assert(ffi_call($library, 'gp_sqlite_scalar', '(STRING,(SINT64):SINT64):SINT32', 'SELECT 6 * 7', ffi_callback('row')) == 0);
