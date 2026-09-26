package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Byte-oriented reads, writes, diagnostics, references and copy-on-write around string offsets. */
public final class StringOffsetsTest {
    private static final List<Case> CASES = List.of(
        new Case("positive-negative-and-numeric-string", """
            $s='abc';foreach([0,1,-1,'1','01','+1',' 1 ']as$i)echo $s[$i],':';
            echo 'xyz'[2],':',('xyz')[-2];
            """),
        new Case("utf8-and-binary-read-bytes", """
            $s=hex2bin('c3a900ff');
            for($i=0;$i<4;$i++)echo bin2hex($s[$i]),':';
            echo bin2hex(hex2bin('ff00')[-2]);
            """),
        new Case("write-is-byte-oriented", """
            $s=hex2bin('c3a9ff');$s[0]='A';$s[-1]=hex2bin('80');
            echo bin2hex($s),':',bin2hex($s[1]);
            """),
        new Case("string-assignment-is-copy-on-write", """
            $a='abc';$b=$a;$b[1]='X';echo $a,':',$b;
            $a=['abc'];$b=$a;$b[0][1]='Y';echo ':',$a[0],':',$b[0];
            """),
        new Case("shared-string-variable-reference", """
            $s='abc';$ref=&$s;$ref[-1]='Z';echo $s,':',$ref;
            """),
        new Case("readonly-enum-string-offset", """
            enum E:string{case A='abc';}
            try{E::A->name[0]='X';}catch(Error $e){echo 'name:';}
            try{E::A->value[0]='X';}catch(Error $e){echo 'value:';}
            echo E::A->name,':',E::A->value;
            """),
        new Case("out-of-range-read-warnings", """
            $s='abc';echo '['.$s[3].']';echo '['.$s[-4].']';
            """),
        new Case("read-offset-cast-warnings", """
            $s='abc';foreach([1.8,1.0,true,null]as$i)echo '['.$s[$i].']';
            """),
        new Case("integer-prefix-warning", """
            $s='abc';echo $s['1x'];
            """),
        new Case("invalid-offset-types", """
            $s='abc';foreach(['x','1.5','1e0','9223372036854775808',[],new stdClass]as$i){
                try{echo $s[$i];}catch(TypeError $e){echo 'type:';}
            }
            """),
        new Case("quiet-offset-probes", """
            $s='a0c';foreach([0,1,-1,3,-4,'1','01','x',[],null]as$i){
                echo isset($s[$i])?'set:':'no:';echo empty($s[$i])?'empty;':'value;';
            }
            """),
        new Case("coalesce-quiet-vs-invalid-type", """
            $s='abc';foreach([0,3,'x',null,1.8]as$i)echo ($s[$i]??'missing'),':';
            try{$value=$s[[]]??'missing';}catch(TypeError $e){echo 'type';}
            """),
        new Case("probe-float-precision-warning", """
            $s='abc';$i=1.8;echo isset($s[$i]),':',empty($s[$i])===false;
            """),
        new Case("write-expands-with-spaces", """
            $s='abc';echo ($s[5]='Z'),':',$s,':',strlen($s);
            """),
        new Case("write-first-byte-and-assignment-result", """
            $s='abc';$input='XY';echo ($s[0]=$input),':',$s,':',$input;
            """),
        new Case("write-negative-out-of-range", """
            $s='abc';echo '['.($s[-5]='XY').']',':',$s;
            """),
        new Case("write-offset-casts", """
            $s='abc';echo ($s[1.8]='Z'),':',$s;
            """),
        new Case("empty-write-is-an-error", """
            $s='abc';try{$s[0]='';}catch(Error $e){echo 'empty:';}echo $s;
            """),
        new Case("coalesce-write-byte-result", """
            $s='abc';echo ($s[1]??='XX'),':',($s[5]??='YZ'),':',$s;
            """),
        new Case("append-reference-unset-are-errors", """
            $s='abc';try{$s[]='X';}catch(Error $e){echo 'append:';}
            try{$ref=&$s[0];}catch(Error $e){echo 'reference:';}
            $x='X';try{$s[0]=&$x;}catch(Error $e){echo 'bind:';}
            try{unset($s[0]);}catch(Error $e){echo 'unset:';}echo $s;
            """),
        new Case("compound-assignment-evaluates-rhs-before-error", """
            $s='abc';function value(){echo 'rhs:';return 'X';}
            try{$s[0].=value();}catch(Error $e){echo 'compound:';}
            try{$s[[]]+=value();}catch(Error $e){echo 'invalid:';}
            try{$s[0]++;}catch(Error $e){echo 'increment:';}echo $s;
            """),
        new Case("nested-string-offset-read-and-write", """
            $s='abc';echo $s[0][0],':';
            try{$s[0][0]='Z';}catch(Error $e){echo 'nested:';}echo $s;
            """),
        new Case("offset-source-can-change-during-rhs", """
            $s='abc';function index(){echo 'key:';return 0;}
            function value(){global $s;$s=[1];echo 'value:';return 'Z';}
            $s[index()]=value();echo $s[0];
            """),
        new Case("binary-array-key-preserved", """
            $key=hex2bin('ff00');$a=[$key=>3];$b=$a;$b[$key]=9;
            echo $a[$key],':',$b[$key],':',count($a);
            foreach($a as $k=>$v)echo ':',bin2hex($k),':',$v;
            """),
        new Case("masked-warning-updates-last-error", """
            error_reporting(0);$s='abc';echo '['.$s[9].']';$last=error_get_last();
            echo ':',$last['type'],':',$last['message'],':',$last['file']===__FILE__,':',$last['line'];
            """),
        new Case("async-offset-assignment", """
            $s='abc';function value(){Async\\delay(1);return 'Z';}
            $s[1]=value();echo $s;
            """,true),
        new Case("async-offset-error-and-finally", """
            $s='abc';function value(){Async\\delay(1);return '';}
            try{$s[0]=value();}catch(Error $e){echo 'error:';}finally{echo $s;}
            """,true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "string-offsets");
    }
}
