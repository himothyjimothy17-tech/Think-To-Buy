package org.biobuzz.sim.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Easy, typed access to a parsed config object.
 *
 * Example: {@code cfg.num("chassis.massLb")} walks into "chassis" and returns
 * "massLb" as a double. A missing key throws an error that names the key and
 * the file, so config typos are easy to find.
 */
public final class Cfg {

    private final Map<String, Object> map;
    private final String where;

    public Cfg(Map<String, Object> map, String where) {
        this.map = map;
        this.where = where;
    }

    public Map<String, Object> raw() {
        return map;
    }

    public boolean has(String path) {
        return find(path) != MISSING;
    }

    public double num(String path) {
        Object v = require(path);
        if (!(v instanceof Double)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be a number but is " + v);
        }
        return (Double) v;
    }

    public double num(String path, double defaultValue) {
        return has(path) ? num(path) : defaultValue;
    }

    public int integer(String path) {
        return (int) Math.round(num(path));
    }

    public String str(String path) {
        Object v = require(path);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be text but is " + v);
        }
        return (String) v;
    }

    public String str(String path, String defaultValue) {
        return has(path) ? str(path) : defaultValue;
    }

    public boolean bool(String path) {
        Object v = require(path);
        if (!(v instanceof Boolean)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be true/false but is " + v);
        }
        return (Boolean) v;
    }

    public boolean bool(String path, boolean defaultValue) {
        return has(path) ? bool(path) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public Cfg obj(String path) {
        Object v = require(path);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be an object {...}");
        }
        return new Cfg((Map<String, Object>) v, where + " > " + path);
    }

    /** A list of objects, e.g. the "motors" array. */
    @SuppressWarnings("unchecked")
    public List<Cfg> objList(String path) {
        Object v = require(path);
        if (!(v instanceof List)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be a list [...]");
        }
        List<Cfg> out = new ArrayList<>();
        int i = 0;
        for (Object o : (List<Object>) v) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException(where + ": \"" + path + "[" + i + "]\" should be an object");
            }
            out.add(new Cfg((Map<String, Object>) o, where + " > " + path + "[" + i + "]"));
            i++;
        }
        return out;
    }

    /** A list of numbers, e.g. AprilTag IDs. */
    @SuppressWarnings("unchecked")
    public List<Double> numList(String path) {
        Object v = require(path);
        if (!(v instanceof List)) {
            throw new IllegalArgumentException(where + ": \"" + path + "\" should be a list [...]");
        }
        List<Double> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            out.add((Double) o);
        }
        return out;
    }

    public Iterable<String> keys() {
        return map.keySet();
    }

    private static final Object MISSING = new Object();

    @SuppressWarnings("unchecked")
    private Object find(String path) {
        Object cur = map;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map) || !((Map<String, Object>) cur).containsKey(part)) {
                return MISSING;
            }
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }

    private Object require(String path) {
        Object v = find(path);
        if (v == MISSING) {
            throw new IllegalArgumentException(where + ": missing setting \"" + path + "\"");
        }
        return v;
    }

    /**
     * Returns a copy of {@code base} with {@code override} laid on top.
     * Objects are merged key by key; everything else is replaced.
     * This is how design variants (config/variants/*.jsonc) change a few values.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> out = deepCopy(base);
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object existing = out.get(e.getKey());
            if (existing instanceof Map && e.getValue() instanceof Map) {
                out.put(e.getKey(), deepMerge((Map<String, Object>) existing, (Map<String, Object>) e.getValue()));
            } else {
                out.put(e.getKey(), deepCopyValue(e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                out.add(deepCopyValue(o));
            }
            return out;
        }
        return v;
    }
}
