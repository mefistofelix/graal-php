package graalphp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Runtime C declarations. Types stay independent of the NFI binding/cache. */
public final class CDeclarations {
    public record Type(String nfi, boolean callback, boolean string) {}
    public record Parameter(String name, Type type) {}
    public record Function(String name, Type result, List<Parameter> parameters) {
        public String signature() {
            return "(" + String.join(",", parameters.stream().map(p -> p.type.nfi).toList()) + "):" + result.nfi;
        }
    }
    private static final Pattern CALLBACK = Pattern.compile("^(.+?)\\(\\s*\\*\\s*([A-Za-z_][A-Za-z_0-9]*)?\\s*\\)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern FUNCTION = Pattern.compile("^(.+?)\\b([A-Za-z_][A-Za-z_0-9]*)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern NAMED = Pattern.compile("^(.+?[\\s*])([A-Za-z_][A-Za-z_0-9]*)$", Pattern.DOTALL);
    private final Map<String, Type> types = new LinkedHashMap<>();

    public CDeclarations() {
        primitive("void", "VOID");
        primitive("char|signed char|int8_t", "SINT8");
        primitive("unsigned char|uint8_t|_Bool|bool", "UINT8");
        primitive("short|short int|signed short|signed short int|int16_t", "SINT16");
        primitive("unsigned short|unsigned short int|uint16_t", "UINT16");
        primitive("int|signed|signed int|int32_t", "SINT32");
        primitive("unsigned|unsigned int|uint32_t", "UINT32");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        primitive("long|long int|signed long|signed long int", windows ? "SINT32" : "SINT64");
        primitive("unsigned long|unsigned long int", windows ? "UINT32" : "UINT64");
        primitive("long long|long long int|signed long long|signed long long int|int64_t|intptr_t|ptrdiff_t", "SINT64");
        primitive("unsigned long long|unsigned long long int|uint64_t|uintptr_t|size_t", "UINT64");
        primitive("float", "FLOAT"); primitive("double", "DOUBLE");
    }

    private void primitive(String names, String nfi) {
        for (String name : names.split("\\|")) types.put(name, new Type(nfi, false, false));
    }

    public Map<String, Function> parse(String declarations) {
        var functions = new LinkedHashMap<String, Function>();
        String source = declarations.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\r\\n]*", " ").strip();
        if (!source.isEmpty() && !source.endsWith(";")) throw error("Missing ';'");
        for (String statement : source.split(";")) {
            statement = statement.strip();
            if (statement.isEmpty()) continue;
            if (statement.startsWith("typedef ")) {
                var parameter = parameter(statement.substring(8));
                if (parameter.name == null) throw error("typedef requires a name");
                if (types.putIfAbsent(parameter.name, parameter.type) != null) throw error("Duplicate type " + parameter.name);
                continue;
            }
            statement = statement.replaceFirst("^extern\\s+", "");
            var match = FUNCTION.matcher(statement);
            if (!match.matches()) throw error("Unsupported declaration: " + statement);
            Type result = type(match.group(1));
            if (result.string || result.callback) throw error("Pointer returns require CData, which is not implemented yet");
            String name = match.group(2);
            var function = new Function(name, result, parameters(match.group(3)));
            if (functions.putIfAbsent(name, function) != null) throw error("Duplicate function " + name);
        }
        return Map.copyOf(functions);
    }

    private List<Parameter> parameters(String text) {
        if (text.isBlank() || text.strip().equals("void")) return List.of();
        var parts = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth < 0) throw error("Unbalanced parameter declaration");
            if (c == ',' && depth == 0) { parts.add(text.substring(start, i).strip()); start = i + 1; }
        }
        if (depth != 0) throw error("Unbalanced parameter declaration");
        parts.add(text.substring(start).strip());
        var result = new ArrayList<Parameter>();
        var names = new java.util.HashSet<String>();
        for (String part : parts) {
            var parameter = parameter(part);
            if (parameter.type.nfi.equals("VOID")) throw error("void parameter must appear alone");
            if (parameter.name != null && !names.add(parameter.name)) throw error("Duplicate parameter " + parameter.name);
            result.add(parameter);
        }
        return List.copyOf(result);
    }

    private Parameter parameter(String declaration) {
        declaration = declaration.strip();
        var callback = CALLBACK.matcher(declaration);
        if (callback.matches()) {
            var function = new Function("callback", type(callback.group(1)), parameters(callback.group(3)));
            if (function.result.string || function.parameters.stream().anyMatch(p -> p.type.callback)) {
                throw error("Callback pointer ownership is not implemented yet");
            }
            return new Parameter(callback.group(2), new Type(function.signature(), true, false));
        }
        if (known(declaration) != null) return new Parameter(null, type(declaration));
        var named = NAMED.matcher(declaration);
        if (named.matches()) return new Parameter(named.group(2), type(named.group(1)));
        return new Parameter(null, type(declaration));
    }

    private Type known(String declaration) {
        String normalized = declaration.replaceAll("\\b(const|volatile|restrict)\\b", " ").strip().replaceAll("\\s+", " ");
        if (normalized.replace(" ", "").equals("char*")) return new Type("STRING", false, true);
        return types.get(normalized);
    }
    private Type type(String declaration) {
        Type type = known(declaration);
        if (type == null) throw error("Unsupported C type: " + declaration.strip());
        return type;
    }
    private static PhpError error(String message) { return new PhpError("FFI\\ParserException", message); }
}
