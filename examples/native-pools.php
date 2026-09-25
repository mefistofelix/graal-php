<?php
FFI::definePool('sqlite', min: 0, max: 1, queueCapacity: 32);
FFI::definePool('blocking', min: 1, max: 4, queueCapacity: 64);
$db = FFI::cdef('
    int sqlite3_libversion_number(void);
    int gp_sqlite_scalar(const char *sql, int64_t (*result)(int64_t));
', 'builtin:sqlite3');
$result = 0;
$db->gp_sqlite_scalar('select 6 * 7', function($value) use (&$result) {
    $result = $value;
    return $value;
}, async: true, pool: 'sqlite');
echo 'sqlite=', $db->sqlite3_libversion_number(), ', result=', $result, "\n";
echo 'workers=', FFI::poolSize('sqlite'), "\n";
Async\delay(1);
