<?php
class C {}
error_reporting(0); error_clear_last();
echo is_a('C','C') === false, ':';
$error=error_get_last(); echo $error['type'], ':', $error['message'], ':', $error['line'];
echo ':', $error['file'] === __FILE__;
error_clear_last(); echo ':', error_get_last() === null;
