<?php
enum E:string {case A='1';case B='1.5';}
echo E::from(1.5)->name;
