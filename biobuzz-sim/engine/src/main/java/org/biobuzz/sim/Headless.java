package org.biobuzz.sim;

import org.biobuzz.sim.config.Jsonc;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.core.Tunables;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.util.JsonOut;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Headless mode: runs matches with no browser, as fast as the CPU allows,
 * and writes a JSON report. This is how we test autos and compare designs.
 *
 *   ./gradlew headless --args="--auto 'BIOBUZZ Auto' --seeds 1-20"
 *
 * Options:
 *   --auto NAME          AUTO OpMode (display name or class name)
 *   --teleop NAME        TELEOP OpMode (implies a full match)
 *   --full               run the whole match even without a TELEOP OpMode
 *   --seeds 1-20 | 20 | 3,7,9   which seeds (default 1-20)
 *   --variant a,b        design variants from config/variants
 *   --set Class.FIELD=v  set a TeamCode tunable (repeatable)
 *   --alliance red|blue  --start POSE
 *   --jobs N             run N matches at once in separate JVMs (default: CPU count)
 *   --out FILE           where to save the JSON (default runs/<time>.json)
 *
 * COMPARISONS: put alternatives separated by "|" in --auto, --variant, --set,
 * --alliance or --start. Every combination becomes a row, and every row is
 * run on the SAME seeds, so the differences are the change, not luck:
 *   --auto "OldAuto|NewAuto"
 *   --variant "gate|hood"
 *   --set "Shooter.KP=40|Shooter.KP=60"
 */
public final class Headless {

    private Headless() {
    }

    /** One configuration to test (a row of the comparison table). */
    static final class Row {
        String label = "";
        String auto = "";
        String teleop = "";
        String variants = "";
        List<String> sets = new ArrayList<>();
        String alliance = "red";
        String start = "";
        boolean full;

        Row copy() {
            Row r = new Row();
            r.label = label;
            r.auto = auto;
            r.teleop = teleop;
            r.variants = variants;
            r.sets = new ArrayList<>(sets);
            r.alliance = alliance;
            r.start = start;
            r.full = full;
            return r;
        }

        List<String> args(long seed) {
            List<String> a = new ArrayList<>(List.of("--worker", "--seeds", Long.toString(seed), "--auto", auto,
                    "--teleop", teleop, "--variant", variants, "--alliance", alliance, "--start", start));
            for (String s : sets) {
                a.add("--set");
                a.add(s);
            }
            if (full) {
                a.add("--full");
            }
            return a;
        }
    }

    public static void main(String[] args) throws Exception {
        String auto = "";
        String teleop = "";
        String variants = "";
        List<String> sets = new ArrayList<>();
        String alliance = "red";
        String start = "";
        String seedsText = "1-20";
        boolean full = false;
        boolean worker = false;
        int jobs = Runtime.getRuntime().availableProcessors();
        String out = null;
        Path config = Paths.get("config");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--auto": auto = args[++i]; break;
                case "--teleop": teleop = args[++i]; break;
                case "--full": full = true; break;
                case "--seeds": seedsText = args[++i]; break;
                case "--variant": variants = args[++i]; break;
                case "--set": sets.add(args[++i]); break;
                case "--alliance": alliance = args[++i]; break;
                case "--start": start = args[++i]; break;
                case "--jobs": jobs = Integer.parseInt(args[++i]); break;
                case "--out": out = args[++i]; break;
                case "--config": config = Paths.get(args[++i]); break;
                case "--worker": worker = true; break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
            }
        }
        List<Long> seeds = parseSeeds(seedsText);
        if (worker) {
            // Child process: one row, the given seeds; print one REPORT line per match.
            Row r = new Row();
            r.auto = auto;
            r.teleop = teleop;
            r.variants = variants;
            r.sets = sets;
            r.alliance = alliance;
            r.start = start;
            r.full = full;
            PrintStream realOut = System.out;
            System.setOut(new PrintStream(System.err, true)); // keep sim chatter off the result channel
            for (long s : seeds) {
                realOut.println("REPORT " + runOne(config, r, s));
                realOut.flush();
            }
            System.exit(0);
        }

        // Expand "|" alternatives into rows.
        List<Row> rows = new ArrayList<>();
        Row base = new Row();
        base.full = full;
        rows.add(base);
        rows = expand(rows, auto, (r, v) -> r.auto = v, "auto");
        rows = expand(rows, teleop, (r, v) -> r.teleop = v, "teleop");
        rows = expand(rows, variants, (r, v) -> r.variants = v, "variant");
        for (String s : sets) {
            rows = expand(rows, s, (r, v) -> {
                if (!v.isBlank()) {
                    r.sets.add(v);
                }
            }, "set");
        }
        rows = expand(rows, alliance, (r, v) -> r.alliance = v, "alliance");
        rows = expand(rows, start, (r, v) -> r.start = v, "start");
        for (Row r : rows) {
            if (r.label.isEmpty()) {
                r.label = r.auto.isEmpty() ? "(no auto)" : r.auto;
            }
        }

        boolean autoOnly = !full && teleop.isEmpty();
        System.out.printf("BIOBUZZ headless: %d row(s) x %d seed(s), %s, %d job(s)%n", rows.size(), seeds.size(),
                autoOnly ? "AUTO only (score when TELEOP would start)" : "full matches", jobs);
        long t0 = System.nanoTime();
        List<List<Map<String, Object>>> results = runAll(config, rows, seeds, jobs);
        double wall = (System.nanoTime() - t0) / 1e9;

        String json = summaryJson(rows, seeds, results, autoOnly);
        printTable(rows, seeds, results, autoOnly);
        System.out.printf("%n%d matches in %.1f s%n", rows.size() * seeds.size(), wall);
        Path file = Paths.get(out != null ? out
                : "runs/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
        System.out.println("Report saved to " + file);
        System.exit(0);
    }

    interface Setter {
        void set(Row r, String v);
    }

    private static List<Row> expand(List<Row> rows, String text, Setter setter, String what) {
        String[] alts = text.split("\\|", -1);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            for (String a : alts) {
                Row c = r.copy();
                setter.set(c, a.trim());
                if (alts.length > 1) {
                    c.label = (c.label.isEmpty() ? "" : c.label + ", ") + what + "=" + (a.isBlank() ? "(none)" : a.trim());
                }
                out.add(c);
            }
        }
        return out;
    }

    static List<Long> parseSeeds(String text) {
        List<Long> out = new ArrayList<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] ab = part.split("-");
                for (long s = Long.parseLong(ab[0]); s <= Long.parseLong(ab[1]); s++) {
                    out.add(s);
                }
            } else if (text.split(",").length == 1 && !text.contains("-")) {
                long n = Long.parseLong(part); // "--seeds 20" = seeds 1..20
                for (long s = 1; s <= n; s++) {
                    out.add(s);
                }
            } else {
                out.add(Long.parseLong(part));
            }
        }
        return out;
    }

    /** Runs one match in this JVM and returns its report. */
    static String runOne(Path config, Row r, long seed) throws IOException {
        List<String> vs = r.variants.isBlank() ? List.of() : Arrays.asList(r.variants.split(","));
        SimConfig cfg = SimConfig.load(config, vs);
        Simulation sim = new Simulation(cfg, vs);
        try {
            for (String s : r.sets) {
                Tunables.setFromText(s);
            }
            sim.setup(Alliance.parse(r.alliance), r.start.isBlank() ? null : r.start, seed);
            boolean autoOnly = !r.full && r.teleop.isBlank();
            return sim.runMatch(r.auto, r.teleop, autoOnly);
        } finally {
            sim.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<Map<String, Object>>> runAll(Path config, List<Row> rows, List<Long> seeds, int jobs)
            throws Exception {
        List<List<Map<String, Object>>> results = new ArrayList<>();
        if (jobs <= 1) {
            for (Row r : rows) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (long s : seeds) {
                    list.add((Map<String, Object>) Jsonc.parse(runOne(config, r, s), "report"));
                    System.out.print(".");
                }
                results.add(list);
            }
            System.out.println();
            return results;
        }
        // Separate JVMs: TeamCode's static fields and the sim clock are per-JVM.
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<List<Future<Map<String, Object>>>> futures = new ArrayList<>();
        for (Row r : rows) {
            List<Future<Map<String, Object>>> fl = new ArrayList<>();
            for (long s : seeds) {
                fl.add(pool.submit(() -> runChild(config, r, s)));
            }
            futures.add(fl);
        }
        for (List<Future<Map<String, Object>>> fl : futures) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Future<Map<String, Object>> f : fl) {
                list.add(f.get());
            }
            results.add(list);
        }
        pool.shutdown();
        System.out.println();
        return results;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runChild(Path config, Row r, long seed) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Headless.class.getName());
        cmd.addAll(r.args(seed));
        cmd.add("--config");
        cmd.add(config.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String report = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("REPORT ")) {
                    report = line.substring(7);
                }
            }
        }
        p.waitFor();
        synchronized (Headless.class) {
            System.out.print(".");
            System.out.flush();
        }
        if (report == null) {
            throw new IllegalStateException("Match seed " + seed + " (" + r.label + ") produced no report");
        }
        return (Map<String, Object>) Jsonc.parse(report, "report");
    }

    // =====================================================================
    // Numbers
    // =====================================================================

    /** The numbers we compare, pulled out of one match report. */
    @SuppressWarnings("unchecked")
    static Map<String, Double> metrics(Map<String, Object> rep, boolean autoOnly) {
        Map<String, Double> m = new LinkedHashMap<>();
        String us = ((String) rep.get("alliance")).toLowerCase();
        Map<String, Object> score = (Map<String, Object>) ((Map<String, Object>) rep.get("score")).get(us);
        Map<String, Object> robot = (Map<String, Object>) rep.get("ourRobot");
        List<Object> fouls = (List<Object>) rep.get("fouls");
        double foulPts = 0;
        double ourFouls = 0;
        for (Object o : fouls) {
            Map<String, Object> f = (Map<String, Object>) o;
            if (f.get("alliance").toString().equalsIgnoreCase(us)) {
                foulPts += ((Number) f.get("points")).doubleValue();
                if (!"WARNING".equals(f.get("penalty"))) {
                    ourFouls++;
                }
            }
        }
        m.put("score", num(autoOnly ? rep.get("ourAutoScore") : rep.get("ourScore")));
        m.put("auto", num(rep.get("ourAutoScore")));
        m.put("tips", num(score.get("autoTips")) + (autoOnly ? 0 : num(score.get("teleopTips"))));
        m.put("leave", num(score.get("leave")));
        m.put("park", num(score.get("autoPark")));
        m.put("shots", num(robot.get("shots")));
        m.put("inCell", num(robot.get("cellEntries")));
        m.put("fouls", ourFouls);
        m.put("foulPtsGiven", foulPts);
        m.put("illegalStart", (double) ((List<Object>) rep.get("startProblems")).size());
        m.put("crash", robot.get("opModeError") == null ? 0.0 : 1.0);
        return m;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? 0 : s / v.length;
    }

    static double sd(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    private static double[] column(List<Map<String, Object>> reps, String key, boolean autoOnly) {
        double[] v = new double[reps.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = metrics(reps.get(i), autoOnly).get(key);
        }
        return v;
    }

    private static void printTable(List<Row> rows, List<Long> seeds, List<List<Map<String, Object>>> res, boolean autoOnly) {
        String what = autoOnly ? "AUTO score" : "match score";
        System.out.println();
        System.out.printf("%-38s %7s %6s %5s %5s | %5s %5s %5s %6s %6s%n", "row (" + what + ", " + seeds.size() + " seeds)",
                "mean", "sd", "min", "max", "tips", "leave", "park", "fouls", "errors");
        for (int i = 0; i < rows.size(); i++) {
            double[] sc = column(res.get(i), "score", autoOnly);
            double errors = mean(column(res.get(i), "crash", autoOnly)) * sc.length
                    + mean(column(res.get(i), "illegalStart", autoOnly)) * sc.length;
            System.out.printf("%-38s %7.1f %6.1f %5.0f %5.0f | %5.2f %5.2f %5.2f %6.0f %6.0f%n", trim(rows.get(i).label, 38),
                    mean(sc), sd(sc), Arrays.stream(sc).min().orElse(0), Arrays.stream(sc).max().orElse(0),
                    mean(column(res.get(i), "tips", autoOnly)), mean(column(res.get(i), "leave", autoOnly)),
                    mean(column(res.get(i), "park", autoOnly)),
                    Arrays.stream(column(res.get(i), "fouls", autoOnly)).sum(), errors);
        }
        if (rows.size() > 1) {
            // Paired comparison: same seeds, so compare seed by seed.
            double[] a = column(res.get(0), "score", autoOnly);
            System.out.println();
            System.out.println("Paired against row 1 (same seeds):");
            for (int i = 1; i < rows.size(); i++) {
                double[] b = column(res.get(i), "score", autoOnly);
                double[] d = new double[a.length];
                int better = 0;
                int worse = 0;
                for (int k = 0; k < a.length; k++) {
                    d[k] = b[k] - a[k];
                    better += d[k] > 0 ? 1 : 0;
                    worse += d[k] < 0 ? 1 : 0;
                }
                double ci = d.length > 1 ? 1.96 * sd(d) / Math.sqrt(d.length) : 0;
                System.out.printf("  %-36s %+7.1f points (95%% CI +/-%.1f); better on %d, worse on %d, same on %d seeds%n",
                        trim(rows.get(i).label, 36), mean(d), ci, better, worse, d.length - better - worse);
            }
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    private static String summaryJson(List<Row> rows, List<Long> seeds, List<List<Map<String, Object>>> res, boolean autoOnly) {
        JsonOut j = new JsonOut().beginObject();
        j.field("type", "biobuzz-headless");
        j.field("created", LocalDateTime.now().toString());
        j.field("mode", autoOnly ? "auto-only" : "full");
        j.name("seeds").any(new ArrayList<Object>(seeds));
        j.name("rows").beginArray();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            j.beginObject().field("label", r.label).field("auto", r.auto).field("teleop", r.teleop)
                    .field("variants", r.variants).field("alliance", r.alliance).field("start", r.start);
            j.name("sets").any(new ArrayList<Object>(r.sets));
            j.name("summary").beginObject();
            for (String key : metrics(res.get(i).get(0), autoOnly).keySet()) {
                double[] v = column(res.get(i), key, autoOnly);
                j.name(key).beginObject().field("mean", mean(v)).field("sd", sd(v))
                        .field("min", Arrays.stream(v).min().orElse(0)).field("max", Arrays.stream(v).max().orElse(0))
                        .endObject();
            }
            j.endObject();
            j.name("runs").beginArray();
            for (Map<String, Object> rep : res.get(i)) {
                j.any(rep);
            }
            j.endArray();
            j.endObject();
        }
        j.endArray();
        return j.endObject().toString();
    }
}
