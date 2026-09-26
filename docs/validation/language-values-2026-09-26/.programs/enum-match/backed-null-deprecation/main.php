<?php
enum E:int {case Zero=0;}
echo E::tryFrom(null)->name;
