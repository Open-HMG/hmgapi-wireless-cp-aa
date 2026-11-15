package hmgapi.mods.wireless.dipo;

import java.util.*;

/** Small JSON transport codec; no Android or third-party dependencies. */
public final class Json {
    private final String text;
    private int pos, depth;
    private Json(String text) { this.text = text; }
    public static Object parse(String text) {
        Json p = new Json(text);
        Object value = p.value(); p.space();
        if (p.pos != text.length()) throw p.bad("Trailing input");
        return value;
    }
    private IllegalArgumentException bad(String s) { return new IllegalArgumentException(s + " at " + pos); }
    private void space() { while (pos < text.length() && " \r\n\t".indexOf(text.charAt(pos)) >= 0) pos++; }
    private boolean take(char c) { space(); if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
    private void need(char c) { if (!take(c)) throw bad("Expected " + c); }
    private Object value() {
        space(); if (++depth > 64) throw bad("JSON too deep");
        try {
            if (pos == text.length()) throw bad("Missing value");
            char c = text.charAt(pos);
            if (c == '"') return string();
            if (c == '{') {
                pos++; Map<String,Object> m = new LinkedHashMap<String,Object>();
                if (take('}')) return m;
                do { space(); String key = string(); need(':'); if (m.containsKey(key)) throw bad("Duplicate key"); m.put(key, value()); } while (take(','));
                need('}'); return m;
            }
            if (c == '[') {
                pos++; List<Object> a = new ArrayList<Object>();
                if (take(']')) return a;
                do { a.add(value()); } while (take(','));
                need(']'); return a;
            }
            for (String word : new String[]{"true", "false", "null"}) {
                if (text.startsWith(word, pos)) { pos += word.length(); return word.equals("null") ? null : Boolean.valueOf(word); }
            }
            int start = pos;
            if (c == '-') pos++;
            if (pos == text.length() || !Character.isDigit(text.charAt(pos))) throw bad("Invalid number");
            if (text.charAt(pos) == '0') pos++;
            else while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            boolean decimal = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                decimal = true; pos++; int digits = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                if (digits == pos) throw bad("Missing fraction");
            }
            if (pos < text.length() && "eE".indexOf(text.charAt(pos)) >= 0) {
                decimal = true; pos++;
                if (pos < text.length() && "+-".indexOf(text.charAt(pos)) >= 0) pos++;
                int digits = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                if (digits == pos) throw bad("Missing exponent");
            }
            String n = text.substring(start, pos);
            if (!decimal) return Long.valueOf(n);
            double d = Double.parseDouble(n);
            if (!Double.isFinite(d)) throw bad("Nonfinite number");
            return d;
        } finally { depth--; }
    }
    private String string() {
        if (pos >= text.length() || text.charAt(pos++) != '"') throw bad("Expected string");
        StringBuilder s = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return s.toString();
            if (c < 32) throw bad("Control character");
            if (c != '\\') { s.append(c); continue; }
            if (pos == text.length()) throw bad("Incomplete escape");
            c = text.charAt(pos++);
            int index = "\"\\/bfnrt".indexOf(c);
            if (index >= 0) s.append("\"\\/\b\f\n\r\t".charAt(index));
            else if (c == 'u' && pos + 4 <= text.length()) { s.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); pos += 4; }
            else throw bad("Invalid escape");
        }
        throw bad("Unterminated string");
    }
    public static Map<String,Object> object(Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<String,Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder s = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) {
                if (c == '"' || c == '\\') s.append('\\').append(c);
                else if (c < 32) s.append(String.format("\\u%04x", (int)c));
                else s.append(c);
            }
            return s.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringBuilder s = new StringBuilder();
        if (value instanceof Map) {
            s.append('{'); boolean comma = false;
            for (Object item : ((Map<?,?>)value).entrySet()) {
                Map.Entry<?,?> e = (Map.Entry<?,?>)item;
                if (comma) s.append(','); comma = true;
                s.append(write(e.getKey().toString())).append(':').append(write(e.getValue()));
            }
            return s.append('}').toString();
        }
        s.append('['); boolean comma = false;
        Iterable<?> items;
        if (value instanceof Iterable) items = (Iterable<?>)value;
        else {
            List<Object> a = new ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) a.add(java.lang.reflect.Array.get(value, i));
            items = a;
        }
        for (Object item : items) { if (comma) s.append(','); comma = true; s.append(write(item)); }
        return s.append(']').toString();
    }
}
