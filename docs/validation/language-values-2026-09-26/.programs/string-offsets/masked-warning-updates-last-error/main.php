<?php
error_reporting(0);$s='abc';echo '['.$s[9].']';$last=error_get_last();
echo ':',$last['type'],':',$last['message'],':',$last['file']===__FILE__,':',$last['line'];
