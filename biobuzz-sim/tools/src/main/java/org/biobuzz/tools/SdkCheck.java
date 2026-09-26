package org.biobuzz.tools;

import org.biobuzz.simhooks.SimOnly;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SDK CHECKER - run with {@code ./gradlew sdkCheck}.
 *
 * Makes sure our TeamCode will work on the REAL robot, not just in the sim:
 *
 *   1. (Gradle does this first) TeamCode is compiled against the REAL FTC SDK
 *      jars downloaded from Maven Central. If that fails, the robot build would
 *      fail too.
 *   2. Every public method in the fake SDK must also exist in the real SDK.
 *      (Otherwise TeamCode could use something that only exists in the sim.)
 *   3. Lists every SDK method TeamCode actually calls, and whether the mock
 *      and the real SDK both have it.
 *   4. Flags Java calls that break the simulator's clock: System.nanoTime(),
 *      System.currentTimeMillis(), Thread.sleep(), and new Thread(...).
 *      Use ElapsedTime and sleep() from LinearOpMode instead - they work the
 *      same on the robot and let the sim run faster than real time.
 *
 * Arguments: <mock classes dir> <teamcode classes dir> <real sdk jar>...
 */
public final class SdkCheck {

    /** Packages that belong to the FTC SDK. */
    private static final String[] SDK_PACKAGES = {
        "com/qualcomm/", "org/firstinspires/ftc/robotcore/", "org/firstinspires/ftc/robotserver/",
        "org/firstinspires/ftc/vision/", "org/firstinspires/ftc/ftccommon/",
    };

    /** Calls that make TeamCode depend on the computer's real clock. */
    private static final Map<String, String> CLOCK_BREAKERS = Map.of(
            "java/lang/System.nanoTime:()J", "System.nanoTime() - use ElapsedTime instead",
            "java/lang/System.currentTimeMillis:()J", "System.currentTimeMillis() - use ElapsedTime instead",
            "java/lang/Thread.sleep:(J)V", "Thread.sleep() - use sleep() from LinearOpMode instead",
            "java/lang/Thread.\"<init>\":(Ljava/lang/Runnable;)V", "new Thread(...) - extra threads run outside the sim's clock");

    private SdkCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path mockDir = Paths.get(args[0]);
        Path teamDir = Paths.get(args[1]);
        List<String> realJars = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            realJars.add(args[i]);
        }
        String realCp = String.join(File.pathSeparator, realJars);

        System.out.println();
        System.out.println("=== BIOBUZZ SDK check ===");
        System.out.println("[1] TeamCode compiled against the REAL FTC SDK: OK");

        int problems = 0;
        problems += checkMockIsSubsetOfReal(mockDir, realCp);
        problems += checkTeamCodeUsage(teamDir, mockDir, realCp);

        System.out.println();
        if (problems == 0) {
            System.out.println("RESULT: PASS - TeamCode should work the same on the robot and in the sim.");
        } else {
            System.out.println("RESULT: " + problems + " problem(s) found (see above).");
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // 2. The fake SDK must not offer anything the real SDK doesn't have.
    // -------------------------------------------------------------------------

    private static int checkMockIsSubsetOfReal(Path mockDir, String realCp) throws Exception {
        System.out.println();
        System.out.println("[2] Fake SDK vs real SDK (every mock method must exist in the real SDK)");
        List<String> classNames = listClasses(mockDir);
        int problems = 0;
        int checked = 0;
        int missingFromMock = 0;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {mockDir.toUri().toURL()}, SdkCheck.class.getClassLoader())) {
            for (String name : classNames) {
                if (name.startsWith("org.biobuzz.")) {
                    continue; // simulator plumbing, not part of the SDK
                }
                Class<?> c = Class.forName(name, false, loader);
                if (!Modifier.isPublic(c.getModifiers()) || c.isAnnotationPresent(SimOnly.class) || c.isSynthetic()) {
                    continue;
                }
                Set<String> real = realMembers(name, realCp);
                if (real == null) {
                    System.out.println("  PROBLEM: class " + name + " is in the mock but NOT in the real SDK");
                    problems++;
                    continue;
                }
                Set<String> mock = mockMembers(c);
                for (String m : mock) {
                    checked++;
                    if (!real.contains(m)) {
                        System.out.println("  PROBLEM: " + name + "." + m + " exists in the mock but not in the real SDK");
                        problems++;
                    }
                }
                for (String r : real) {
                    if (!mock.contains(r)) {
                        missingFromMock++;
                    }
                }
            }
        }
        System.out.printf("  %d mock methods/fields checked, %d problem(s).%n", checked, problems);
        System.out.printf("  (FYI: %d real-SDK members in these classes aren't mocked yet. That's fine unless "
                + "TeamCode needs them - it would then fail to compile in the sim, and step [3] shows what's used.)%n",
                missingFromMock);
        return problems;
    }

    /** Public/protected members of a mock class as "name(paramTypes)" strings, skipping @SimOnly ones. */
    private static Set<String> mockMembers(Class<?> c) {
        Set<String> out = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (visible(m.getModifiers()) && !m.isSynthetic() && !m.isAnnotationPresent(SimOnly.class)
                    && !isEnumBoilerplate(c, m)) {
                out.add(signature(m.getName(), m));
            }
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (visible(k.getModifiers()) && !k.isSynthetic() && !k.isAnnotationPresent(SimOnly.class) && !c.isEnum()) {
                String sig = signature("<init>", k);
                // An inner (non-static) class's constructor secretly takes the outer object
                // first; javap doesn't show that parameter, so drop it here too.
                if (c.isMemberClass() && !Modifier.isStatic(c.getModifiers())) {
                    sig = sig.replaceFirst("\\(" + Pattern.quote(c.getDeclaringClass().getName()) + ",?", "(");
                }
                out.add(sig);
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (visible(f.getModifiers()) && !f.isSynthetic() && !f.isAnnotationPresent(SimOnly.class)) {
                out.add("field " + f.getName());
            }
        }
        return out;
    }

    private static boolean isEnumBoilerplate(Class<?> c, Method m) {
        return c.isEnum() && (m.getName().equals("values") || m.getName().equals("valueOf"));
    }

    private static boolean visible(int mod) {
        return Modifier.isPublic(mod) || Modifier.isProtected(mod);
    }

    private static String signature(String name, Executable e) {
        return name + "(" + Stream.of(e.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(",")) + ")";
    }

    private static final Pattern JAVAP_METHOD = Pattern.compile(
            "^\\s*(?:public|protected)\\b.*?([\\w$<>]+)\\((.*)\\)(?:\\s+throws\\s+[^;]+)?;$");
    private static final Pattern JAVAP_FIELD = Pattern.compile("^\\s*(?:public|protected)\\b[^(]*\\s([\\w$]+);$");

    /** Public/protected members of a REAL SDK class, read with javap. Null if the class doesn't exist. */
    private static Set<String> realMembers(String className, String realCp) {
        String text = javap("-protected", "-cp", realCp, className);
        if (text == null || text.contains("Error: class not found")) {
            return null;
        }
        String simple = className.substring(className.lastIndexOf('.') + 1);
        Set<String> out = new TreeSet<>();
        for (String line : text.split("\n")) {
            line = stripGenerics(line.trim());
            Matcher m = JAVAP_METHOD.matcher(line);
            if (m.matches()) {
                String name = m.group(1);
                if (name.equals(className) || name.equals(simple) || className.endsWith("." + name)
                        || className.endsWith("$" + name)) {
                    name = "<init>";
                }
                String params = m.group(2).replace("...", "[]").replace(" ", "");
                out.add(name + "(" + params + ")");
                continue;
            }
            Matcher f = JAVAP_FIELD.matcher(line);
            if (f.matches()) {
                out.add("field " + f.group(1));
            }
        }
        return out;
    }

    /** Removes generic type arguments, e.g. "List<T> get(Class<? extends T>)" -> "List get(Class)". */
    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (depth == 0) {
                sb.append(ch);
            }
        }
        // Type variables become their bound after erasure (Object unless declared otherwise).
        return sb.toString()
                .replaceAll("\\bDEVICE_TYPE\\b", "com.qualcomm.robotcore.hardware.HardwareDevice")
                .replaceAll("\\bT\\b", "java.lang.Object");
    }

    // -------------------------------------------------------------------------
    // 3 + 4. What does TeamCode call?
    // -------------------------------------------------------------------------

    private static final Pattern REF = Pattern.compile(
            "//\\s*(Method|InterfaceMethod|Field)\\s+([\\w/$]+)\\.(\"?[\\w<>$]+\"?):(\\S+)");

    private static int checkTeamCodeUsage(Path teamDir, Path mockDir, String realCp) throws IOException {
        System.out.println();
        System.out.println("[3] SDK methods our TeamCode uses");
        Map<String, Set<String>> used = new TreeMap<>(); // owner class -> members
        Set<String> clockProblems = new TreeSet<>();
        for (String cls : listClasses(teamDir)) {
            String text = javap("-c", "-p", "-cp", teamDir.toString(), cls);
            if (text == null) {
                continue;
            }
            Matcher m = REF.matcher(text);
            while (m.find()) {
                String owner = m.group(2);
                String member = m.group(3);
                String desc = m.group(4);
                String key = owner + "." + member + ":" + desc;
                if (CLOCK_BREAKERS.containsKey(key)) {
                    clockProblems.add(cls + ": " + CLOCK_BREAKERS.get(key));
                }
                if (isSdk(owner)) {
                    used.computeIfAbsent(owner.replace('/', '.'), k -> new TreeSet<>())
                            .add(("Field".equals(m.group(1)) ? "field " : "") + member.replace("\"", ""));
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : used.entrySet()) {
            System.out.println("  " + e.getKey());
            System.out.println("      " + String.join(", ", e.getValue()));
        }
        System.out.println("  (All of these compiled against both the mock and the real SDK.)");

        System.out.println();
        System.out.println("[4] Calls that bypass the simulated clock");
        if (clockProblems.isEmpty()) {
            System.out.println("  none - good.");
        } else {
            for (String p : clockProblems) {
                System.out.println("  PROBLEM: " + p);
            }
        }
        return clockProblems.size();
    }

    private static boolean isSdk(String internalName) {
        for (String p : SDK_PACKAGES) {
            if (internalName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> listClasses(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> dir.relativize(p).toString().replace(File.separatorChar, '.'))
                    .map(n -> n.substring(0, n.length() - ".class".length()))
                    .filter(n -> !n.equals("module-info"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String javap(String... args) {
        ToolProvider javap = ToolProvider.findFirst("javap").orElseThrow(
                () -> new IllegalStateException("javap not found - run with a full JDK, not a JRE"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(out, true, "UTF-8")) {
            javap.run(ps, ps, args);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }
}
