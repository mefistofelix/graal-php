<?php
// Semantic oracle only. GraalPHP does not parse this file yet.
if (PHP_MAJOR_VERSION !== 8 || PHP_MINOR_VERSION !== 6) {
    fwrite(STDERR, "The compatibility target requires a PHP 8.6 oracle.\n");
    exit(1);
}
function report(string $name, mixed ...$values): void {
    echo $name, '=', implode(',', $values), "\n";
}
function key_types(array $array): string {
    return implode(',', array_map(fn ($key) => (is_int($key) ? 'i:' : 's:') . $key, array_keys($array)));
}

(function () {
    $a = [1, 2]; $b = $a; $b[0] = 9;
    report('cow', $a[0], $b[0]);
    $b[1] = 8; $a = $a;
    report('self_assignment', $a[0], $a[1]);
})();
(function () {
    $a = [1]; $a[0] = $a;
    report('self_insertion_by_value', $a[0][0]);
})();
(function () {
    $a = [1]; $alias =& $a; $copy = $a; $alias[0] = 7;
    report('array_alias', $a[0], $alias[0], $copy[0]);
    $alias = 4;
    report('replace_aliased_array', $a, $alias, $copy[0]);
})();
(function () {
    $x = 1; $a = []; $a[] =& $x; $b = $a; $b[] = 10; $x = 3;
    report('embedded_reference', $x, $a[0], $b[0], $b[1]);
    $b[0] = 6;
    report('write_embedded_reference', $x, $a[0], $b[0]);
    $ordinary = $x; $ordinary = 12;
    report('dereference_assignment', $x, $ordinary);
})();
(function () {
    $a = [1]; $b = $a; $r =& $a[0]; $r = 2;
    report('reference_after_copy', $a[0], $b[0]);
})();
(function () {
    $a = [1]; $r =& $a[0]; unset($r); $b = $a; $b[0] = 2;
    report('singleton_reference', $a[0], $b[0]);
})();
(function () {
    $x = 1; $a = []; $a[] =& $x; $r =& $a[0]; unset($a[0]); $a[0] = 99; $r = 5;
    report('unset_referenced_element', $x, $r, $a[0]);
})();
(function () {
    $x = 1; $y = 2; $a = []; $a[] =& $x; $b = $a; $b[0] =& $y; $y = 8; $x = 7;
    report('rebind_copied_element', $a[0], $b[0]);
    $x =& $x;
    report('self_bind', $x);
})();
(function () {
    $a = null; $a['x']['y'] = 1; $b = $a; $b['x']['y'] = 2;
    report('nested_cow', $a['x']['y'], $b['x']['y']);
})();
(function () {
    $a = null; $a['x']['y'] = 1; $r =& $a['x']['y']; $b = $a; $b['x']['z'] = 10; $r = 2;
    report('nested_reference', $a['x']['y'], $b['x']['y'], $b['x']['z']);
    $a['x'] = [9]; $r = 3;
    report('reference_survives_parent', $a['x'][0], $b['x']['y'], $r);
})();
(function () {
    $a = null; $a['x']['y'] = 1; $b = $a; $r =& $a['x']['y']; $r = 8;
    report('nested_reference_after_copy', $a['x']['y'], $b['x']['y']);
})();
(function () {
    $a = [[1]]; $r =& $a[0]; $b = $a; $b[0][0] = 8;
    report('array_valued_reference', $a[0][0], $b[0][0], $r[0]);
})();
(function () {
    $a = [1, 2, 3]; $copy = $a;
    foreach ($a as &$v) { $v *= 2; }
    $v = 9;
    report('foreach_reference', $a[0], $a[1], $a[2], $copy[0], $copy[1], $copy[2]);
    unset($v); $b = $a; $b[0] = 20; $b[2] = 30;
    report('foreach_unset_alias', $a[0], $a[2], $b[0], $b[2]);
})();
(function () {
    $a = [1, 2]; foreach ($a as &$v) {} $b = $a; $b[0] = 10; $b[1] = 20;
    report('foreach_lingering_alias', $a[0], $a[1], $b[0], $b[1], $v);
})();
(function () {
    $a = []; $v = 5; foreach ($a as &$v) { $v = 99; }
    report('foreach_empty', $v);
})();
(function () {
    $a = []; $a['8'] = 1; $a[8] = 2; $a['08'] = 3; $a['+8'] = 4; $a['-0'] = 5; $a['9223372036854775808'] = 6;
    report('key_normalization', key_types($a));
    report('key_overwrite', $a[8], $a['08'], $a['+8'], $a['-0'], $a['9223372036854775808']);
})();
(function () {
    $a = []; $a[-5] = 1; $a[] = 2; unset($a[-4]); $a[] = 3;
    report('negative_append', key_types($a));
})();
(function () {
    $a = [1, 2]; unset($a[1]); $a[] = 3; unset($a[0]); $a[0] = 4;
    report('append_and_order', key_types($a));
    $b = $a; $b[] = 5;
    report('copy_append_index', key_types($b));
})();
(function () {
    $a = [PHP_INT_MAX => 1]; $rejected = false;
    try { $a[] = null; } catch (Error $error) { $rejected = true; }
    report('append_overflow', $rejected ? 'true' : 'false');
})();
function &return_element_reference(array &$array): mixed { return $array[0]; }
(function () {
    $a = [1]; $b = $a; unset($b); $a[0] = 2;
    report('unset_array_alias', $a[0]);
    $r =& return_element_reference($a); $r = 7;
    report('return_reference', $a[0], $r);
})();
(function () {
    $x = 1; $a = []; $a[] =& $x; unset($x); $b = $a; $b[0] = 2;
    report('unset_external_alias', $a[0], $b[0]);
})();
(function () {
    $x = 1;
    (function (&$value) { $alias =& $value; $alias = 8; })($x);
    report('scope_release', $x);
})();
(function () {
    $a = [1]; $reference =& $a[0]; unset($a); $reference = 6;
    report('direct_reference_lifetime', $reference);
})();
require __DIR__ . '/mutations.php';
