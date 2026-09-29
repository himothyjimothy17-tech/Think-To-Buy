package org.biobuzz.sim.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A very small web server for the simulator:
 *   - serves the files in web/ (the 3D view) over HTTP
 *   - upgrades /ws to a WebSocket for live data both ways
 *
 * SECURITY: it listens on 127.0.0.1 only, so no other computer on the
 * network can connect. Nothing is ever sent to the internet.
 *
 * Written by hand (instead of using a library) so the project has no
 * dependencies to download. The WebSocket part follows RFC 6455.
 */
public final class WebServer {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("js", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put("mjs", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("json", "application/json");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("glb", "model/gltf-binary");
        CONTENT_TYPES.put("gltf", "model/gltf+json");
        CONTENT_TYPES.put("bin", "application/octet-stream");
        CONTENT_TYPES.put("jpg", "image/jpeg");
    }

    private final Path webRoot;
    /** Saved runs and headless reports, served read-only at /runs/. */
    private final Path runsRoot;
    private final int port;
    private final Consumer<String> onMessage;
    private final Supplier<String> onConnectMessage;
    private final Set<Client> clients = new CopyOnWriteArraySet<>();
    private ServerSocket server;

    /**
     * @param onMessage        called with each text message a browser sends
     * @param onConnectMessage produces the first message sent to a new browser
     */
    public WebServer(Path webRoot, int port, Consumer<String> onMessage, Supplier<String> onConnectMessage) {
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.runsRoot = webRoot.toAbsolutePath().normalize().getParent().resolve("runs");
        this.port = port;
        this.onMessage = onMessage;
        this.onConnectMessage = onConnectMessage;
    }

    public void start() throws IOException {
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "web-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Sends a message to every connected browser. Never blocks the caller. */
    public void broadcast(String text, boolean isState) {
        for (Client c : clients) {
            c.enqueue(text, isState);
        }
    }

    public void stop() {
        try {
            server.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "web-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) {
                    System.err.println("[web] accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                webSocket(socket, in, out, headers);
            } else {
                serveFile(out, path);
            }
        } catch (IOException e) {
            // Browser closed the connection - normal.
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void serveFile(OutputStream out, String rawPath) throws IOException {
        String path = rawPath.split("\\?")[0];
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.equals("/runs/")) {
            sendRunList(out);
            return;
        }
        if (path.equals("/models/")) {
            sendModelList(out);
            return;
        }
        Path root = path.startsWith("/runs/") ? runsRoot : webRoot;
        Path file = (path.startsWith("/runs/") ? runsRoot.resolve(path.substring(6)) : webRoot.resolve(path.substring(1)))
                .normalize();
        // Never serve anything outside web/ or runs/ (blocks "../../secret" tricks).
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length
                    + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        byte[] body = Files.readAllBytes(file);
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\nContent-Type: " + CONTENT_TYPES.getOrDefault(ext, "application/octet-stream")
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** JSON list of the optional CAD files in web/models/ (so the page can check without 404s). */
    private void sendModelList(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        Path dir = webRoot.resolve("models");
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> st = Files.list(dir)) {
                for (Path f : (Iterable<Path>) st.sorted()::iterator) {
                    if (sb.length() > 1) {
                        sb.append(',');
                    }
                    sb.append('"').append(f.getFileName().toString().replace("\"", "").replace("\\", "")).append('"');
                }
            }
        }
        byte[] body = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\nContent-Type: application/json\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** JSON list of saved runs, newest first: [{"file":..., "bytes":..., "modified":...}]. */
    private void sendRunList(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        if (Files.isDirectory(runsRoot)) {
            java.util.List<Path> files = new java.util.ArrayList<>();
            try (java.util.stream.Stream<Path> st = Files.list(runsRoot)) {
                st.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            files.sort((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()));
            for (Path f : files) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append("{\"file\":\"").append(f.getFileName().toString().replace("\"", "")).append("\",\"bytes\":")
                        .append(Files.size(f)).append(",\"modified\":").append(f.toFile().lastModified()).append('}');
            }
        }
        byte[] body = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\nContent-Type: application/json\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > 8192) {
                throw new IOException("header line too long");
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    // ----------------------------------------------------------- WebSocket

    private void webSocket(Socket socket, InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        // Only accept pages served by THIS server (stops other websites from connecting to the sim).
        String origin = headers.get("origin");
        if (origin != null && !origin.startsWith("http://127.0.0.1:") && !origin.startsWith("http://localhost:")) {
            out.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            return;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        Client client = new Client(out);
        // The field layout must reach the browser before any state message.
        client.enqueue(onConnectMessage.get(), false);
        clients.add(client);
        Thread writer = new Thread(client::writeLoop, "ws-writer");
        writer.setDaemon(true);
        writer.start();
        try {
            readFrames(in, client);
        } finally {
            clients.remove(client);
            client.close();
            socket.close();
        }
    }

    private void readFrames(InputStream in, Client client) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | in.read();
                }
            }
            if (len > 1_000_000) {
                throw new IOException("frame too large");
            }
            byte[] mask = new byte[4];
            if (masked) {
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0x8: // close
                    return;
                case 0x9: // ping -> pong
                    client.sendFrame(0xA, payload);
                    break;
                case 0x1: // text
                case 0x0: // continuation
                    message.write(payload);
                    if (fin) {
                        String text = message.toString(StandardCharsets.UTF_8);
                        message.reset();
                        try {
                            onMessage.accept(text);
                        } catch (RuntimeException e) {
                            System.err.println("[web] bad message: " + e.getMessage());
                        }
                    }
                    break;
                default:
                    break; // ignore binary / pong
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("connection closed");
            }
            off += n;
        }
    }

    /**
     * One connected browser. Messages go through a queue and a writer thread
     * so a slow browser can never stall the simulation. For "state" messages
     * only the newest one is kept (old positions are useless once newer ones exist).
     */
    private static final class Client {
        private final OutputStream out;
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private String latestState;
        private boolean closed;

        Client(OutputStream out) {
            // Buffered so each frame goes out as one network packet, not byte by byte.
            this.out = new BufferedOutputStream(out, 64 * 1024);
        }

        synchronized void enqueue(String text, boolean isState) {
            if (closed) {
                return;
            }
            if (isState) {
                latestState = text;
            } else {
                queue.add(text);
            }
            notifyAll();
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }

        void writeLoop() {
            try {
                while (true) {
                    String next;
                    synchronized (this) {
                        while (!closed && queue.isEmpty() && latestState == null) {
                            wait();
                        }
                        if (closed) {
                            return;
                        }
                        if (!queue.isEmpty()) {
                            next = queue.poll();
                        } else {
                            next = latestState;
                            latestState = null;
                        }
                    }
                    sendFrame(0x1, next.getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException | IOException e) {
                close();
            }
        }

        void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (out) {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length < 65536) {
                    out.write(126);
                    out.write(payload.length >>> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) (((long) payload.length >>> (8 * i)) & 0xFF));
                    }
                }
                out.write(payload);
                out.flush();
            }
        }
    }
}
