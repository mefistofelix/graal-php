<?php
$native = FFI::cdef('int memcmp(const void *first, const void *second, size_t count);
    size_t strlen(const char *text);', getenv('FFI_TEST_CRT'));
$buffer = $native->new('char[5]');
$second = $native->new('uint8_t[5]');
FFI::memset($buffer, 65, 4);
FFI::memcpy($second, $buffer, 5);
echo get_class($buffer), '|', FFI::sizeof($buffer), '|';
echo FFI::string($buffer), '|', bin2hex(FFI::string($second, 5)), '|';
echo $native->memcmp($buffer, $second, 5), '|', $native->strlen($buffer), '|';
FFI::memcpy($buffer, hex2bin('008001ff00'), 5);
echo bin2hex(FFI::string($buffer, 5));
