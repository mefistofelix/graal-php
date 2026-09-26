<?php
namespace Library;enum State:string {case Ready='r';}
namespace App;use Library\State as Status;
echo Status::Ready->name,':',Status::from('r')===Status::Ready,':',Status::Ready instanceof Status;
