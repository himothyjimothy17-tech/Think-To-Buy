package org.biobuzz.sim.core;

import org.biobuzz.sim.opmode.OpModeRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * TeamCode's live-tunable numbers: every {@code public static} field that is
 * NOT final and is a number, boolean or String, in any TeamCode class.
 *
 * This is the same convention FTC Dashboard uses, so the robot code stays
 * plain Java that runs unchanged on the real robot. (A {@code static final}
 * constant can't be tuned: javac copies its value into every place that uses it.)
 *
 * Names look like "Shooter.KP". Design variants can set them too:
 *   { "teamcode": { "Shooter.HOOD_MODE": true } }
 */
public final class Tunables {

    private static Map<String, Field> fields;
    private static Map<String, Object> defaults;

    private Tunables() {
    }

    /** All tunable fields, by name (sorted). */
    public static synchronized Map<String, Field> all() {
        if (fields == null) {
            Map<String, Field> found = new TreeMap<>();
            for (String cn : OpModeRegistry.scanClassNames()) {
                Class<?> c;
                try {
                    c = Class.forName(cn, true, Tunables.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                if (!Modifier.isPublic(c.getModifiers()) || c.getEnclosingClass() != null) {
                    continue;
                }
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isPublic(m) && Modifier.isStatic(m) && !Modifier.isFinal(m) && supported(f.getType())) {
                        found.put(c.getSimpleName() + "." + f.getName(), f);
                    }
                }
            }
            fields = found;
            defaults = snapshot();
        }
        return fields;
    }

    private static boolean supported(Class<?> t) {
        return t == double.class || t == float.class || t == int.class || t == long.class || t == boolean.class
                || t == String.class;
    }

    public static Object get(String name) {
        Field f = all().get(name);
        if (f == null) {
            throw new IllegalArgumentException("No tunable TeamCode field " + name + " (it must be public static, not final)");
        }
        try {
            return f.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sets a field from a number, boolean or text value. */
    public static void set(String name, Object value) {
        Field f = all().get(name);
        if (f == null) {
            throw new IllegalArgumentException("No tunable TeamCode field " + name + " (it must be public static, not final). "
                    + "Known: " + all().keySet());
        }
        try {
            Class<?> t = f.getType();
            String s = String.valueOf(value);
            if (t == double.class) {
                f.setDouble(null, value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(s));
            } else if (t == float.class) {
                f.setFloat(null, value instanceof Number ? ((Number) value).floatValue() : Float.parseFloat(s));
            } else if (t == int.class) {
                f.setInt(null, value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : Integer.parseInt(s));
            } else if (t == long.class) {
                f.setLong(null, value instanceof Number ? Math.round(((Number) value).doubleValue()) : Long.parseLong(s));
            } else if (t == boolean.class) {
                f.setBoolean(null, value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(s));
            } else {
                f.set(null, s);
            }
        } catch (IllegalAccessException | NumberFormatException e) {
            throw new IllegalArgumentException("Can't set " + name + " to " + value + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String n : all().keySet()) {
            out.put(n, get(n));
        }
        return out;
    }

    /** Puts every field back to the value it had when the classes loaded. */
    public static void restoreDefaults() {
        all();
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            set(e.getKey(), e.getValue());
        }
    }

    public static Map<String, Object> defaults() {
        all();
        return defaults;
    }

    /** Applies "Class.FIELD=value" text (command line). */
    public static void setFromText(String assignment) {
        int eq = assignment.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("Expected Class.FIELD=value, got " + assignment);
        }
        set(assignment.substring(0, eq).trim(), assignment.substring(eq + 1).trim());
    }
}
