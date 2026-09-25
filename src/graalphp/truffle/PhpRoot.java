package graalphp.truffle;

import com.oracle.truffle.api.bytecode.*;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import graalphp.runtime.*;
import static graalphp.runtime.Execution.*;

@GenerateBytecode(languageClass = PhpLanguage.class, enableYield = true,
        enableUncachedInterpreter = true, boxingEliminationTypes = {long.class, boolean.class, double.class})
public abstract class PhpRoot extends RootNode implements BytecodeRootNode {
    protected PhpRoot(PhpLanguage language, FrameDescriptor descriptor) { super(language, descriptor); }
    public String name;
    @Override public String getName() { return name; }
    static Activation activation(VirtualFrame frame) { return (Activation) frame.getArguments()[0]; }
    public enum Append { KEY }

    @Operation @ConstantOperand(type = String.class, name = "name")
    public static final class Variable {
        @Specialization static PhpValues.Location run(VirtualFrame frame, String name) { return activation(frame).variable(name); }
    }
    @Operation
    public static final class Element {
        @Specialization static PhpValues.Location run(PhpValues.Location parent, Object key) {
            try { return key == Append.KEY ? parent.deferredAppend() : parent.element(PhpValues.unwrap(key)); }
            finally { PhpValues.drop(key); }
        }
    }
    @Operation
    public static final class Read {
        @Specialization static Object run(VirtualFrame frame, PhpValues.Location location) {
            try { return activation(frame).track(PhpValues.own(location.read())); }
            catch (java.util.NoSuchElementException error) { throw new PhpError(error.getMessage()); }
        }
    }
    @Operation public static final class QuietRead {
        @Specialization static Object run(VirtualFrame frame, PhpValues.Location location) {
            return activation(frame).track(PhpValues.own(location.readOrNull()));
        }
    }
    @Operation @ConstantOperand(type = String.class, name = "name")
    public static final class Property {
        @Specialization static PhpValues.Location run(VirtualFrame frame, String name, Object receiver) {
            return ObjectModel.property(activation(frame), receiver, name);
        }
    }
    @Operation @ConstantOperand(type = String.class, name = "type") @ConstantOperand(type = String.class, name = "name")
    public static final class StaticProperty {
        @Specialization static PhpValues.Location run(VirtualFrame frame, String type, String name) {
            return ObjectModel.staticProperty(activation(frame), type, name);
        }
    }
    @Operation @ConstantOperand(type = String.class, name = "name")
    public static final class NamedConstant {
        @Specialization @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static Object run(String name) { return ObjectModel.namedConstant(name); }
    }
    @Operation @ConstantOperand(type = String.class, name = "type")
    public static final class CreateObject {
        @Specialization static Object run(VirtualFrame frame, String type) { return ObjectModel.create(activation(frame), type); }
    }
    @Operation @ConstantOperand(type = ObjectModel.ClosureTemplate.class, name = "template")
    public static final class CreateClosure {
        @Specialization static Object run(VirtualFrame frame, ObjectModel.ClosureTemplate template) {
            return ObjectModel.closure(activation(frame), template.function(), template.captures(), template.arrow());
        }
    }
    @Operation public static final class CheckReturn {
        @Specialization static Object run(VirtualFrame frame, Object value) {
            Object checked = ObjectModel.checkType(value, activation(frame).function.returnType());
            if (checked == PhpValues.unwrap(value)) return value;
            PhpValues.drop(value);
            return checked;
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "delta") @ConstantOperand(type = boolean.class, name = "before")
    public static final class Increment {
        @Specialization @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static Object run(int delta, boolean before, PhpValues.Location location) {
            Object previous = location.read();
            Object value = Operations.binary("+", previous, (long) delta);
            location.set(value);
            return before ? value : previous;
        }
    }
    @Operation public static final class NonNull {
        @Specialization static boolean run(Object value) { return PhpValues.unwrap(value) != null; }
    }
    @Operation public static final class IsSet {
        @Specialization static boolean run(Object value) {
            try { return PhpValues.unwrap(value) != null; } finally { PhpValues.drop(value); }
        }
    }
    @Operation public static final class TruthyKeep {
        @Specialization static boolean run(Object value) { return Operations.truth(value); }
    }
    @Operation
    public static final class Lookup {
        @Specialization static Object run(VirtualFrame frame, Object array, Object key) {
            try { return activation(frame).track(PhpValues.element(array, PhpValues.unwrap(key))); }
            finally { PhpValues.drop(array); PhpValues.drop(key); }
        }
    }
    @Operation
    public static final class Write {
        @Specialization static Object run(PhpValues.Location location, Object value) { location.set(PhpValues.unwrap(value)); return value; }
    }
    @Operation
    public static final class Bind {
        @Specialization static Object run(VirtualFrame frame, PhpValues.Location destination, PhpValues.Location source) {
            destination.bind(source); return activation(frame).track(PhpValues.own(destination.read()));
        }
    }
    @Operation public static final class Drop {
        @Specialization static void run(Object value) { PhpValues.drop(value); }
    }
    @Operation public static final class Echo {
        @Specialization static void run(VirtualFrame frame, Object value) {
            write(activation(frame), value);
        }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static void write(Activation activation, Object value) {
            try { activation.request.output.writeBytes(PhpString.bytes(value)); }
            finally { PhpValues.drop(value); }
        }
    }
    @Operation public static final class Truth {
        @Specialization static boolean run(Object value) { try { return Operations.truth(value); } finally { PhpValues.drop(value); } }
    }
    @Operation @ConstantOperand(type = String.class, name = "operator")
    public static final class Binary {
        static boolean arithmetic(String operator) { return operator.equals("+") || operator.equals("-") || operator.equals("*"); }
        @Specialization(guards = "arithmetic(operator)", rewriteOn = ArithmeticException.class)
        static long integers(String operator, long a, long b) {
            return switch (operator) {
                case "+" -> Math.addExact(a, b); case "-" -> Math.subtractExact(a, b); case "*" -> Math.multiplyExact(a, b);
                default -> throw new AssertionError(operator);
            };
        }
        @Specialization(replaces = "integers") static Object values(String operator, Object a, Object b) { return Operations.binary(operator, a, b); }
    }
    @Operation @ConstantOperand(type = String.class, name = "operator")
    public static final class Unary {
        @Specialization @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static Object run(String operator, Object value) {
            try {
                if (operator.equals("!")) return !Operations.truth(value);
                var number = Operations.number(value);
                if (operator.equals("+")) return number;
                if (number instanceof Long integer && integer != Long.MIN_VALUE) return -integer;
                return -number.doubleValue();
            } finally { PhpValues.drop(value); }
        }
    }
    @Operation public static final class ArgumentValue {
        @Specialization static Argument run(Object value) { return new Argument(value, null); }
    }
    @Operation @ConstantOperand(type = String.class, name = "name")
    public static final class ArgumentName {
        @Specialization static Argument run(String name, Argument argument) {
            return new Argument(argument.value(), argument.location(), name);
        }
    }
    @Operation public static final class ArgumentLocation {
        @Specialization static Argument run(VirtualFrame frame, PhpValues.Location location) {
            return new Argument(activation(frame).track(PhpValues.own(location.read())), location);
        }
    }
    @Operation @ConstantOperand(type = String.class, name = "name") @ConstantOperand(type = boolean.class, name = "globalFallback")
    public static final class Invoke {
        @Specialization static Object run(VirtualFrame frame, String name, boolean globalFallback, @Variadic Object[] arguments,
                @Cached IndirectCallNode call) {
            var args = java.util.Arrays.copyOf(arguments, arguments.length, Argument[].class);
            return activation(frame).track(Operations.invoke(activation(frame), name, args, call, globalFallback));
        }
    }
    @Operation @ConstantOperand(type = String.class, name = "kind") @ConstantOperand(type = String.class, name = "name")
    public static final class InvokeMember {
        @Specialization static Object run(VirtualFrame frame, String kind, String name, Object receiver,
                @Variadic Object[] arguments, @Cached IndirectCallNode call) {
            var args = java.util.Arrays.copyOf(arguments, arguments.length, Argument[].class);
            return activation(frame).track(Operations.invokeMember(activation(frame), kind, name, receiver, args, call));
        }
    }
    @Operation public static final class Suspended {
        @Specialization static boolean run(VirtualFrame frame, Object value) {
            boolean suspended = value instanceof NestedCall || value instanceof Scheduler.Effect;
            if (suspended && activation(frame).synchronousCallback) throw new PhpError("A synchronous native callback cannot suspend");
            return suspended;
        }
    }
    @Operation public static final class Resume {
        @Specialization static Object run(VirtualFrame frame, Object value) {
            if (value instanceof Failure failure) throw failure.error();
            return activation(frame).track(value);
        }
    }
    public record ArrayItem(Object key, Object value, boolean reference) {}
    @Operation @ConstantOperand(type = boolean.class, name = "reference")
    public static final class Item {
        @Specialization static ArrayItem run(boolean reference, Object key, Object value) { return new ArrayItem(key, value, reference); }
    }
    @Operation public static final class Array {
        @Specialization static Object run(VirtualFrame frame, @Variadic Object[] items) { return create(activation(frame), items); }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static Object create(Activation activation, Object[] items) {
            try (var scope = new PhpValues.Scope(activation.request.heap)) {
                var array = scope.variable(scope.emptyArray());
                for (var object : items) {
                    var item = (ArrayItem) object;
                    var element = item.key == Append.KEY ? array.append() : array.element(PhpValues.unwrap(item.key));
                    if (item.reference) element.bind((PhpValues.Location) item.value);
                    else element.set(PhpValues.unwrap(item.value));
                    PhpValues.drop(item.key); PhpValues.drop(item.value);
                }
                return activation.track(PhpValues.own(array.read()));
            }
        }
    }
    @Operation public static final class Throw {
        @Specialization static void run(Object value) { if (value instanceof PhpError error) throw error; throw new PhpError(Operations.string(value)); }
    }
    @Operation @ConstantOperand(type = String.class, name = "type")
    public static final class MatchesCatch {
        @Specialization @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static boolean run(String type, Object value) { return value instanceof PhpError error && error.matches(type); }
    }
    @Operation public static final class Unset {
        @Specialization static void run(PhpValues.Location location) { location.unset(); }
    }
    @Operation @ConstantOperand(type = String.class, name = "name")
    public static final class Global {
        @Specialization static void run(VirtualFrame frame, String name) { activation(frame).global(name); }
    }
    @Operation public static final class Poll {
        @Specialization static Object run(VirtualFrame frame) {
            var caller = activation(frame);
            caller.task.checkCancellation();
            if (caller.request.hosted && !caller.synchronousCallback && --caller.task.checkpoints == 0) {
                caller.task.checkpoints = 128;
                return new Scheduler.Cooperate();
            }
            return null;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "reference")
    public static final class OpenCursor {
        @Specialization static Cursor run(VirtualFrame frame, boolean reference, Object source) { return open(activation(frame), reference, source); }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static Cursor open(Activation activation, boolean reference, Object source) {
            var cursor = new Cursor(source, reference); activation.resources.add(cursor); return cursor;
        }
    }
    @Operation public static final class Next {
        @Specialization static boolean run(Cursor cursor, PhpValues.Location value, Object key) { return cursor.next(value, (PhpValues.Location) key); }
    }
    @Operation public static final class CloseCursor {
        @Specialization @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static void run(Cursor cursor) { cursor.close(); }
    }
}
