package org.biobuzz.sim.util;

import java.util.List;
import java.util.Map;

/**
 * A tiny JSON writer for messages sent to the browser and for JSON reports.
 *
 * Usage: {@code new JsonOut().beginObject().field("x", 1.5).endObject().toString()}
 * It inserts commas for you.
 */
public final class JsonOut {

    private static final java.math.MathContext SIGNIFICANT_DIGITS = new java.math.MathContext(6);

    private final StringBuilder sb = new StringBuilder(1024);
    /** True when the next value needs a comma in front of it. */
    private boolean needComma;

    public JsonOut beginObject() {
        comma();
        sb.append('{');
        needComma = false;
        return this;
    }

    public JsonOut endObject() {
        sb.append('}');
        needComma = true;
        return this;
    }

    public JsonOut beginArray() {
        comma();
        sb.append('[');
        needComma = false;
        return this;
    }

    public JsonOut endArray() {
        sb.append(']');
        needComma = true;
        return this;
    }

    /** Writes {@code "name":} - follow it with a value, object or array. */
    public JsonOut name(String name) {
        comma();
        string(name);
        sb.append(':');
        needComma = false;
        return this;
    }

    public JsonOut value(double v) {
        comma();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            // 6 significant digits is plenty for display and keeps messages small.
            sb.append(new java.math.BigDecimal(v).round(SIGNIFICANT_DIGITS).stripTrailingZeros().toPlainString());
        }
        needComma = true;
        return this;
    }

    public JsonOut value(long v) {
        comma();
        sb.append(v);
        needComma = true;
        return this;
    }

    public JsonOut value(boolean v) {
        comma();
        sb.append(v);
        needComma = true;
        return this;
    }

    public JsonOut value(String v) {
        comma();
        if (v == null) {
            sb.append("null");
        } else {
            string(v);
        }
        needComma = true;
        return this;
    }

    /** Writes any value made of Maps, Lists, Strings, Numbers and Booleans. */
    @SuppressWarnings("unchecked")
    public JsonOut any(Object v) {
        if (v == null) {
            comma();
            sb.append("null");
            needComma = true;
        } else if (v instanceof Map) {
            beginObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                name(e.getKey()).any(e.getValue());
            }
            endObject();
        } else if (v instanceof List) {
            beginArray();
            for (Object o : (List<Object>) v) {
                any(o);
            }
            endArray();
        } else if (v instanceof Number) {
            value(((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            value((Boolean) v);
        } else {
            value(v.toString());
        }
        return this;
    }

    public JsonOut field(String name, double v) {
        return name(name).value(v);
    }

    public JsonOut field(String name, long v) {
        return name(name).value(v);
    }

    public JsonOut field(String name, boolean v) {
        return name(name).value(v);
    }

    public JsonOut field(String name, String v) {
        return name(name).value(v);
    }

    /** Inserts already-built JSON text as a value. */
    public JsonOut rawValue(String json) {
        comma();
        sb.append(json);
        needComma = true;
        return this;
    }

    private void comma() {
        if (needComma) {
            sb.append(',');
        }
    }

    private void string(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
