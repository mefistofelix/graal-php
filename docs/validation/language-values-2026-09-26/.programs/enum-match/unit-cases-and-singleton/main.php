<?php
enum Direction {case North;case South;}
echo Direction::North->name, ':', Direction::North === Direction::North, ':', Direction::North !== Direction::South;
foreach(Direction::cases() as $key=>$value) echo ';',$key,':',$value->name;
echo ':', Direction::North === Direction::cases()[0];
