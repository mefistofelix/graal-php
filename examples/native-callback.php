<?php
$calls = 0;
$callback = ffi_callback(function($value) use (&$calls) {
    $calls++;
    return $value * 2;
});

// ENV injects the NFI environment; the shim attaches and detaches its native thread.
$result = ffi_call_async(
    'build/graalphp-native.dll',
    'gp_callback_thread',
    '(ENV,(ENV,SINT64):SINT64,SINT64):SINT64',
    $callback,
    21
);
echo await($result), ':', $calls, "\n";
