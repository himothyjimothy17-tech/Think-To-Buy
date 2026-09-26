package org.biobuzz.sim;

import org.biobuzz.sim.config.Jsonc;
import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.net.WebServer;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Starts the visual simulator: the physics engine plus a local web server,
 * then opens the 3D view in your browser.
 *
 * Run it with:  ./gradlew sim
 * Options (pass with --args="..."):
 *   --port 8765          web server port
 *   --variant hood       apply config/variants/hood.jsonc (comma-separate several)
 *   --no-browser         don't open the browser automatically
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8765;
        boolean openBrowser = true;
        List<String> variants = new ArrayList<>();
        Path config = Paths.get("config");
        Path web = Paths.get("web");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--variant": variants.addAll(Arrays.asList(args[++i].split(","))); break;
                case "--no-browser": openBrowser = false; break;
                case "--config": config = Paths.get(args[++i]); break;
                case "--web": web = Paths.get(args[++i]); break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
            }
        }

        SimConfig cfg = SimConfig.load(config, variants);
        Simulation sim = new Simulation(cfg, variants);
        WebServer server = new WebServer(web, port, text -> onBrowserMessage(sim, text), sim::fieldMessage);
        sim.setBroadcaster(msg -> server.broadcast(msg, msg.startsWith("{\"type\":\"state\"")));
        server.start();

        String url = "http://127.0.0.1:" + server.port() + "/";
        System.out.println();
        System.out.println("  BIOBUZZ simulator running at " + url);
        System.out.println("  OpModes found: " + sim.registry().entries().size());
        for (String w : sim.warnings()) {
            System.out.println("  WARNING: " + w);
        }
        System.out.println("  Press Ctrl+C to quit.");
        System.out.println();
        if (openBrowser) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception | Error e) {
                System.out.println("  (Couldn't open the browser automatically - open the address above.)");
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sim.shutdown();
            server.stop();
        }));
        sim.runRealTime();
    }

    @SuppressWarnings("unchecked")
    private static void onBrowserMessage(Simulation sim, String text) {
        Object parsed = Jsonc.parse(text, "browser message");
        if (parsed instanceof Map) {
            sim.onClientMessage((Map<String, Object>) parsed);
        }
    }
}
