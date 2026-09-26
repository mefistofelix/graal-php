<?php
enum State:string {case Ready='ready';case Done='done';}
echo State::from(value:'done')->name,':',State::tryFrom(value:'ready')===State::Ready;
echo ':',State::tryFrom('absent')===null;
