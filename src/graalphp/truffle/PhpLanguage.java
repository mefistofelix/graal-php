package graalphp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

@TruffleLanguage.Registration(id = "php", name = "GraalPHP", version = "0.1.0",
        defaultMimeType = "application/x-httpd-php", characterMimeTypes = "application/x-httpd-php",
        dependentLanguages = "nfi", contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class PhpLanguage extends TruffleLanguage<PhpContext> {
    private static final ContextReference<PhpContext> CONTEXT = ContextReference.create(PhpLanguage.class);
    @Override protected PhpContext createContext(Env env) { return new PhpContext(this, env); }
    @Override protected void disposeContext(PhpContext context) { context.close(); }
    @Override protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) { return true; }
    @Override protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        var unit = PhpCompiler.compile(this, source);
        return new RootNode(this) {
            @Override public Object execute(VirtualFrame frame) { return CONTEXT.get(this).execute(unit); }
            @Override public String getName() { return source.getName(); }
        }.getCallTarget();
    }
}
