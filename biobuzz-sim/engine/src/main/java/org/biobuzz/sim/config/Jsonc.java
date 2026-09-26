package org.biobuzz.sim.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON files that may contain // and /* comments (".jsonc").
 *
 * WHY: our config files need comments that cite the game manual, and plain
 * JSON doesn't allow comments. This tiny reader keeps the project free of
 * outside libraries.
 *
 * Result types: objects -> LinkedHashMap, arrays -> ArrayList,
 * numbers -> Double, strings -> String, true/false -> Boolean, null -> null.
 */
public final class Jsonc {

    private final String text;
    private final String sourceName;
    private int pos;

    private Jsonc(String text, String sourceName) {
        this.text = text;
        this.sourceName = sourceName;
    }

    /** Parses a JSONC document. {@code sourceName} is only used in error messages. */
    public static Object parse(String text, String sourceName) {
        Jsonc p = new Jsonc(text, sourceName);
        p.skipSpaceAndComments();
        Object value = p.readValue();
        p.skipSpaceAndComments();
        if (p.pos < p.text.length()) {
            throw p.error("unexpected text after the end of the document");
        }
        return value;
    }

    private Object readValue() {
        skipSpaceAndComments();
        if (pos >= text.length()) {
            throw error("unexpected end of file");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expectWord("true"); return Boolean.TRUE;
            case 'f': expectWord("false"); return Boolean.FALSE;
            case 'n': expectWord("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipSpaceAndComments();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpaceAndComments();
            if (peek() != '"') {
                throw error("expected a \"key\" in quotes");
            }
            String key = readString();
            skipSpaceAndComments();
            if (peek() != ':') {
                throw error("expected ':' after key \"" + key + "\"");
            }
            pos++;
            map.put(key, readValue());
            skipSpaceAndComments();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}' in object");
            }
            skipSpaceAndComments();
            if (peek() == '}') { // allow a trailing comma
                pos++;
                return map;
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipSpaceAndComments();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipSpaceAndComments();
            char c = peek();
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']' in array");
            }
            skipSpaceAndComments();
            if (peek() == ']') { // allow a trailing comma
                pos++;
                return list;
            }
        }
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // opening quote
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = text.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("string never closed");
    }

    private Double readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number '" + text.substring(start, pos) + "'");
        }
    }

    private void expectWord(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of file");
        }
        return text.charAt(pos);
    }

    private void skipSpaceAndComments() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (text.startsWith("//", pos)) {
                int end = text.indexOf('\n', pos);
                pos = end < 0 ? text.length() : end + 1;
            } else if (text.startsWith("/*", pos)) {
                int end = text.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw error("/* comment never closed");
                }
                pos = end + 2;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(pos, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException(sourceName + " line " + line + ", column " + col + ": " + message);
    }
}
