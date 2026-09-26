<?php
class C{}$a=new C;$b=new C;
echo match([$a]){[$b]=>'wrong',[$a]=>'same'};
