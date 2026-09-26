<?php
interface Label {function label():string;}
enum Status:string implements Label {
    case Ready='r';case Done='d';
    public function label():string{return match($this){self::Ready=>'ready',self::Done=>'done'};}
}
function label(Label $value):string{return $value->label();}
echo label(Status::Done),':',Status::Ready instanceof Label,':',Status::Ready instanceof UnitEnum,':',Status::Ready instanceof BackedEnum;
