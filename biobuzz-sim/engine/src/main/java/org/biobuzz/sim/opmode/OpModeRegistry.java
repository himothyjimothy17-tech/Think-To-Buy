package org.biobuzz.sim.opmode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds every OpMode in TeamCode, the same way the Driver Station app does:
 * classes marked @TeleOp or @Autonomous (and not @Disabled).
 *
 * To add a new OpMode, just create the class in TeamCode with one of those
 * annotations - no registration needed.
 */
public final class OpModeRegistry {

    /** Package the FTC SDK puts TeamCode in. */
    public static final String TEAMCODE_PACKAGE = "org.firstinspires.ftc.teamcode";

    /** One entry in the OpMode list. */
    public static final class Entry {
        public final String name;
        public final String group;
        public final boolean autonomous;
        public final Class<? extends OpMode> type;

        Entry(String name, String group, boolean autonomous, Class<? extends OpMode> type) {
            this.name = name;
            this.group = group;
            this.autonomous = autonomous;
            this.type = type;
        }

        /** Makes an entry for any OpMode class (used by tests and headless runs). */
        public static Entry of(Class<? extends OpMode> type, boolean autonomous) {
            return new Entry(type.getSimpleName(), "", autonomous, type);
        }
    }

    private final List<Entry> entries;

    public OpModeRegistry() {
        List<Entry> found = new ArrayList<>();
        for (String className : scanClassNames()) {
            Class<?> c;
            try {
                c = Class.forName(className, false, OpModeRegistry.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (!OpMode.class.isAssignableFrom(c) || c.isAnnotationPresent(Disabled.class)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends OpMode> op = (Class<? extends OpMode>) c;
            TeleOp t = c.getAnnotation(TeleOp.class);
            Autonomous a = c.getAnnotation(Autonomous.class);
            if (t != null) {
                found.add(new Entry(t.name().isEmpty() ? c.getSimpleName() : t.name(), t.group(), false, op));
            } else if (a != null) {
                found.add(new Entry(a.name().isEmpty() ? c.getSimpleName() : a.name(), a.group(), true, op));
            }
        }
        found.sort(Comparator.comparing((Entry e) -> e.autonomous).thenComparing(e -> e.name));
        entries = Collections.unmodifiableList(found);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Finds an OpMode by its display name or its class name. */
    public Entry find(String nameOrClass) {
        for (Entry e : entries) {
            if (e.name.equals(nameOrClass) || e.type.getSimpleName().equals(nameOrClass)
                    || e.type.getName().equals(nameOrClass)) {
                return e;
            }
        }
        return null;
    }

    /** Lists class names in the TeamCode package, from folders or jars on the classpath. */
    private static List<String> scanClassNames() {
        List<String> names = new ArrayList<>();
        String path = TEAMCODE_PACKAGE.replace('.', '/');
        try {
            Enumeration<URL> urls = OpModeRegistry.class.getClassLoader().getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path dir = Paths.get(url.toURI());
                    try (Stream<Path> files = Files.walk(dir)) {
                        files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                            String rel = dir.relativize(p).toString().replace(File.separatorChar, '.');
                            names.add(TEAMCODE_PACKAGE + "." + rel.substring(0, rel.length() - ".class".length()));
                        });
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    JarURLConnection conn = (JarURLConnection) url.openConnection();
                    conn.setUseCaches(false);
                    try (JarFile jar = conn.getJarFile()) {
                        Enumeration<JarEntry> e = jar.entries();
                        while (e.hasMoreElements()) {
                            String n = e.nextElement().getName();
                            if (n.startsWith(path + "/") && n.endsWith(".class")) {
                                names.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
                            }
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Could not scan TeamCode for OpModes", e);
        }
        return names;
    }
}
