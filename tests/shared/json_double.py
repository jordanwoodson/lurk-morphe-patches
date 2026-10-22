"""A small org.json for host JVM tests, following Android's org.json where the extension relies on it.

Android ships org.json in the framework; the JDK has nothing like it. This covers what the settings
code uses: parsing, the get/opt/is accessors with Android's coercions (getString turns any value into
text, optString returns its fallback only for a missing value, optBoolean reads "true"/"false"
strings), insertion-ordered objects and serialization with toString()/toString(indent).
"""

PACKAGE = 'org/json/'

JSON_DOUBLE = {
    PACKAGE + 'JSONException.java': '''package org.json;
public class JSONException extends Exception {
    public JSONException(String message) { super(message); }
}
''',
    PACKAGE + 'JSON.java': '''package org.json;
final class JSON {
    static String toString(Object value) {
        if (value instanceof String) return (String) value;
        return value == null ? null : String.valueOf(value);
    }
    static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String text = (String) value;
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }
    static Integer toInteger(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return (int) Double.parseDouble((String) value); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
    static Object wrap(Object value) {
        if (value == null) return JSONObject.NULL;
        return value;
    }
    static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\\\\""); break;
                case '\\\\': out.append("\\\\\\\\"); break;
                case '/': out.append("\\\\/"); break;
                case '\\n': out.append("\\\\n"); break;
                case '\\r': out.append("\\\\r"); break;
                case '\\t': out.append("\\\\t"); break;
                case '\\b': out.append("\\\\b"); break;
                case '\\f': out.append("\\\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }
    static void write(StringBuilder out, Object value, int indent, int depth) {
        if (value instanceof JSONObject) ((JSONObject) value).write(out, indent, depth);
        else if (value instanceof JSONArray) ((JSONArray) value).write(out, indent, depth);
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == (long) d) out.append((long) d); else out.append(d);
        } else out.append(value);
    }
    static void newline(StringBuilder out, int indent, int depth) {
        if (indent <= 0) return;
        out.append('\\n');
        for (int i = 0; i < indent * depth; i++) out.append(' ');
    }
}
''',
    PACKAGE + 'JSONTokener.java': '''package org.json;
public class JSONTokener {
    private final String in;
    private int pos;
    public JSONTokener(String in) { this.in = in; }
    private int next() {
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c != ' ' && c != '\\n' && c != '\\r' && c != '\\t') return c;
        }
        return -1;
    }
    public Object nextValue() throws JSONException {
        int c = next();
        switch (c) {
            case -1: throw new JSONException("End of input at character " + pos);
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            default:
                pos--;
                return readLiteral();
        }
    }
    private String readString() throws JSONException {
        StringBuilder out = new StringBuilder();
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\\\') { out.append(c); continue; }
            if (pos >= in.length()) break;
            char e = in.charAt(pos++);
            switch (e) {
                case 'u':
                    if (pos + 4 > in.length()) throw new JSONException("Unterminated escape");
                    out.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                case 'n': out.append('\\n'); break;
                case 'r': out.append('\\r'); break;
                case 't': out.append('\\t'); break;
                case 'b': out.append('\\b'); break;
                case 'f': out.append('\\f'); break;
                default: out.append(e);
            }
        }
        throw new JSONException("Unterminated string");
    }
    private Object readLiteral() throws JSONException {
        int start = pos;
        while (pos < in.length() && "{}[]:,\\" \\t\\r\\n".indexOf(in.charAt(pos)) < 0) pos++;
        String literal = in.substring(start, pos);
        if (literal.isEmpty()) throw new JSONException("Expected literal value at character " + start);
        if (literal.equals("null")) return JSONObject.NULL;
        if (literal.equals("true")) return Boolean.TRUE;
        if (literal.equals("false")) return Boolean.FALSE;
        try {
            if (literal.matches("-?\\\\d+")) {
                long value = Long.parseLong(literal);
                return value == (int) value ? (Object) (int) value : (Object) value;
            }
            return Double.parseDouble(literal);
        } catch (NumberFormatException e) {
            throw new JSONException("Unexpected literal " + literal);
        }
    }
    private JSONObject readObject() throws JSONException {
        JSONObject result = new JSONObject();
        int first = next();
        if (first == '}') return result;
        if (first == -1) throw new JSONException("Unterminated object");
        pos--;
        while (true) {
            Object name = nextValue();
            if (!(name instanceof String)) throw new JSONException("Names must be strings");
            if (next() != ':') throw new JSONException("Expected ':' after " + name);
            result.put((String) name, nextValue());
            int c = next();
            if (c == '}') return result;
            if (c != ',' && c != ';') throw new JSONException("Unterminated object at character " + pos);
        }
    }
    private JSONArray readArray() throws JSONException {
        JSONArray result = new JSONArray();
        int first = next();
        if (first == ']') return result;
        if (first == -1) throw new JSONException("Unterminated array");
        pos--;
        while (true) {
            result.put(nextValue());
            int c = next();
            if (c == ']') return result;
            if (c != ',' && c != ';') throw new JSONException("Unterminated array at character " + pos);
        }
    }
}
''',
    PACKAGE + 'JSONObject.java': '''package org.json;
import java.util.*;
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };
    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    public JSONObject() { }
    public JSONObject(String json) throws JSONException {
        Object value = new JSONTokener(json).nextValue();
        if (!(value instanceof JSONObject)) throw new JSONException("Value is not a JSONObject");
        values.putAll(((JSONObject) value).values);
    }
    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) throw new JSONException("Names must be non-null");
        if (value == null) { values.remove(name); return this; }
        values.put(name, value);
        return this;
    }
    public JSONObject put(String name, boolean value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, int value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, long value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, double value) throws JSONException { return put(name, (Object) value); }
    public Object remove(String name) { return values.remove(name); }
    public int length() { return values.size(); }
    public boolean has(String name) { return values.containsKey(name); }
    public boolean isNull(String name) { Object value = values.get(name); return value == null || value == NULL; }
    public Iterator<String> keys() { return new ArrayList<>(values.keySet()).iterator(); }
    public JSONArray names() {
        if (values.isEmpty()) return null;
        JSONArray names = new JSONArray();
        for (String name : values.keySet()) names.put(name);
        return names;
    }
    public Object opt(String name) { return name == null ? null : values.get(name); }
    public Object get(String name) throws JSONException {
        Object value = values.get(name);
        if (value == null) throw new JSONException("No value for " + name);
        return value;
    }
    public String getString(String name) throws JSONException { return JSON.toString(get(name)); }
    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) {
        String result = JSON.toString(opt(name));
        return result != null ? result : fallback;
    }
    public boolean getBoolean(String name) throws JSONException {
        Boolean result = JSON.toBoolean(get(name));
        if (result == null) throw new JSONException("Value at " + name + " is not a boolean");
        return result;
    }
    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) {
        Boolean result = JSON.toBoolean(opt(name));
        return result != null ? result : fallback;
    }
    public int getInt(String name) throws JSONException {
        Integer result = JSON.toInteger(get(name));
        if (result == null) throw new JSONException("Value at " + name + " is not an int");
        return result;
    }
    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) {
        Integer result = JSON.toInteger(opt(name));
        return result != null ? result : fallback;
    }
    public JSONArray getJSONArray(String name) throws JSONException {
        Object value = get(name);
        if (!(value instanceof JSONArray)) throw new JSONException("Value at " + name + " is not a JSONArray");
        return (JSONArray) value;
    }
    public JSONArray optJSONArray(String name) { Object value = opt(name); return value instanceof JSONArray ? (JSONArray) value : null; }
    public JSONObject getJSONObject(String name) throws JSONException {
        Object value = get(name);
        if (!(value instanceof JSONObject)) throw new JSONException("Value at " + name + " is not a JSONObject");
        return (JSONObject) value;
    }
    public JSONObject optJSONObject(String name) { Object value = opt(name); return value instanceof JSONObject ? (JSONObject) value : null; }
    @Override public String toString() { StringBuilder out = new StringBuilder(); write(out, 0, 0); return out.toString(); }
    public String toString(int indent) throws JSONException { StringBuilder out = new StringBuilder(); write(out, indent, 0); return out.toString(); }
    void write(StringBuilder out, int indent, int depth) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            JSON.newline(out, indent, depth + 1);
            JSON.quote(out, entry.getKey());
            out.append(indent > 0 ? ": " : ":");
            JSON.write(out, entry.getValue(), indent, depth + 1);
        }
        if (!values.isEmpty()) JSON.newline(out, indent, depth);
        out.append('}');
    }
}
''',
    PACKAGE + 'JSONArray.java': '''package org.json;
import java.util.*;
public class JSONArray {
    private final List<Object> values = new ArrayList<>();
    public JSONArray() { }
    public JSONArray(String json) throws JSONException {
        Object value = new JSONTokener(json).nextValue();
        if (!(value instanceof JSONArray)) throw new JSONException("Value is not a JSONArray");
        values.addAll(((JSONArray) value).values);
    }
    public int length() { return values.size(); }
    public JSONArray put(Object value) { values.add(value); return this; }
    public JSONArray put(boolean value) { values.add(value); return this; }
    public JSONArray put(int value) { values.add(value); return this; }
    public Object opt(int index) { return index < 0 || index >= values.size() ? null : values.get(index); }
    public Object get(int index) throws JSONException {
        Object value = opt(index);
        if (value == null) throw new JSONException("Index " + index + " out of range");
        return value;
    }
    public boolean isNull(int index) { Object value = opt(index); return value == null || value == JSONObject.NULL; }
    public String getString(int index) throws JSONException { return JSON.toString(get(index)); }
    public String optString(int index) { String result = JSON.toString(opt(index)); return result != null ? result : ""; }
    public JSONObject getJSONObject(int index) throws JSONException {
        Object value = get(index);
        if (!(value instanceof JSONObject)) throw new JSONException("Value at " + index + " is not a JSONObject");
        return (JSONObject) value;
    }
    public JSONObject optJSONObject(int index) { Object value = opt(index); return value instanceof JSONObject ? (JSONObject) value : null; }
    public JSONArray getJSONArray(int index) throws JSONException {
        Object value = get(index);
        if (!(value instanceof JSONArray)) throw new JSONException("Value at " + index + " is not a JSONArray");
        return (JSONArray) value;
    }
    @Override public String toString() { StringBuilder out = new StringBuilder(); write(out, 0, 0); return out.toString(); }
    public String toString(int indent) throws JSONException { StringBuilder out = new StringBuilder(); write(out, indent, 0); return out.toString(); }
    void write(StringBuilder out, int indent, int depth) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            JSON.newline(out, indent, depth + 1);
            JSON.write(out, values.get(i), indent, depth + 1);
        }
        if (!values.isEmpty()) JSON.newline(out, indent, depth);
        out.append(']');
    }
}
''',
}
