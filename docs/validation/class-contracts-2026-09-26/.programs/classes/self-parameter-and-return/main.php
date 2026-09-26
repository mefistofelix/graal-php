<?php
interface I { function identity(self $value): self; }
class C implements I { function identity(I $value): self { return $this; } }
echo get_class((new C)->identity(new C));
