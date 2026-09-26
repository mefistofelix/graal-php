<?php
enum Code:int {case Ok=200;case Missing=404;}
echo Code::Ok->value,':',Code::from(200)===Code::Ok,':',Code::tryFrom(404)===Code::Missing;
echo ':',Code::tryFrom(500)===null;
try{Code::from(500);}catch(ValueError $error){echo ':value';}
