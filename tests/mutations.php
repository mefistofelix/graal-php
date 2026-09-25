<?php
// Included by reference.php after its PHP 8.6 version guard.
(function () {
    $a = [1, 2]; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { $a[] = 3; }
        $v *= 10;
    }
    report('foreach_append', ...$seen, ...$a);
})();
(function () {
    $a = [1, 2, 3]; $seen = [];
    foreach ($a as $k => &$v) {
        $seen[] = $v;
        if ($k === 0) { unset($a[1]); }
    }
    report('foreach_delete_next', ...$seen);
})();
(function () {
    $a = [1, 2]; $seen = [];
    foreach ($a as $k => &$v) {
        $seen[] = $v;
        unset($a[$k]);
        $v *= 10;
    }
    report('foreach_delete_current', ...[...$seen, count($a), $v]);
})();
(function () {
    $a = [1, 2, 3]; $seen = [];
    foreach ($a as $k => &$v) {
        $seen[] = $v;
        if ($k === 0) { unset($a[1]); $a[1] = 4; }
    }
    report('foreach_reinsert', ...$seen);
})();
(function () {
    $a = [1, 2, 3]; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { $a = [8, 9]; }
    }
    report('foreach_replace_array', ...$seen, ...$a);
})();
(function () {
    $a = [1, 2, 3]; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { unset($a); }
    }
    report('foreach_unset_array', ...[...$seen, isset($a) ? 1 : 0]);
})();
(function () {
    $a = [1, 2, 3]; $seen = []; $warning = false;
    set_error_handler(function (int $severity, string $message) use (&$warning): bool {
        if ($severity !== E_WARNING || !str_starts_with($message, 'foreach() argument')) {
            throw new ErrorException($message, 0, $severity);
        }
        $warning = true;
        return true;
    });
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { $a = 8; }
    }
    restore_error_handler();
    report('foreach_replace_scalar', ...[...$seen, $a, $warning ? 1 : 0]);
})();
(function () {
    $a = [1, 2]; $b = [8, 9]; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { $a =& $b; }
    }
    report('foreach_rebind_array', ...$seen, ...$a, ...$b);
})();
(function () {
    $a = [1, 2, 3]; $b = []; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { $b = $a; }
        if ($v === 2) { $a[] = 4; }
        $v *= 10;
    }
    report('foreach_copy_then_write', ...$seen, ...$a, ...$b);
})();
(function () {
    $a = [1, 2]; $seen = [];
    foreach ($a as &$v) {
        foreach ($a as &$w) {
            $seen[] = "$v:$w";
            if ($v === 1 && $w === 1) { $a[] = 3; }
        }
    }
    report('foreach_nested_mutation', ...$seen);
})();
(function () {
    $a = [1, 2]; $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v === 1) { unset($a[0], $a[1]); $a[] = 3; }
    }
    report('foreach_clear_append', ...$seen, ...$a);
})();
(function () {
    $a = [1, 2]; $seen = [];
    foreach ($a as &$v) { $seen[] = $v; break; }
    $v = 7;
    report('foreach_break_alias', ...$seen, ...$a);
})();
(function () {
    $a = [1, 2];
    try { foreach ($a as &$v) { throw new Exception('stop'); } }
    catch (Exception $exception) { $v = 7; }
    report('foreach_exception_alias', ...$a);
})();
(function () {
    $a = ['value' => 1]; $a['self'] =& $a;
    $a['self']['value'] = 2;
    report('cycle_self', $a['value'], $a['self']['self']['value']);
    $b = $a; $b['value'] = 3;
    report('cycle_cow', $a['value'], $b['value'], $b['self']['value']);
    $b['self']['value'] = 4;
    report('cycle_cow_reference', $a['value'], $b['value'], $b['self']['value']);
})();
(function () {
    $a = ['value' => 1]; $b = ['value' => 2];
    $a['b'] =& $b; $b['a'] =& $a;
    $a['b']['a']['value'] = 7;
    report('cycle_mutual', $a['value'], $b['a']['value'], $a['b']['value']);
    unset($a['b']);
    $b['a']['value'] = 8;
    report('cycle_break_edge', $a['value'], $b['a']['value']);
})();
(function () {
    $x = 1; $a = []; $a['self'] =& $a; $a['x'] =& $x;
    unset($a); gc_collect_cycles();
    $b = [&$x]; unset($x); $c = $b; $c[0] = 2;
    report('cycle_release_external_reference', $b[0], $c[0]);
})();
(function () {
    $a = ['value' => 1]; $a['self'] =& $a; $r =& $a['self'];
    unset($a); gc_collect_cycles();
    $r['value'] = 9;
    report('cycle_live_reference', $r['self']['value']);
})();
