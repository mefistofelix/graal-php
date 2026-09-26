<?php
$ffi=FFI::cdef('typedef int64_t (*transform)(int64_t, const char *);
int64_t gp_test_walk(transform callback, int64_t seed, int depth, const char *text);
int gp_test_active(void);
int gp_test_finished(void);
', getenv('FFI_TEST_LIBRARY'));
try {
    $ffi->gp_test_walk(function($n,$text){
        echo 'entered:';
        if (true) Async\delay(1);
        try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
        catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
        finally {echo 'UNEXPECTED_FINALLY';}
        return 0;
    },1,8,'C-stack',async:false);
} catch(Throwable $error){echo 'UNEXPECTED_OUTER_CATCH';}
echo 'UNEXPECTED_AFTER';
