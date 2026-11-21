package com.borislav007.bzview.core;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP server that runs on the camera phone.
 *
 * <ul>
 *   <li>GET /stream?t=TOKEN - endless multipart/x-mixed-replace (MJPEG) video stream</li>
 *   <li>GET /cmd?t=TOKEN&amp;name=NAME&amp;arg=ARG - remote control, replies with status text</li>
 * </ul>
 */
public final class CameraServer {

    public interface Listener {
        /** Called from a server thread whenever the number of connected viewers changes. */
        void onViewerCountChanged(int count);

        /** Called from a server thread for every command. Returns the status text to send back. */
        String onCommand(String name, String arg);
    }

    private final int port;
    private final String token;
    private final Listener listener;

    private final Object frameLock = new Object();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger viewers = new AtomicInteger();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private byte[] latestFrame;
    private long frameSeq;

    public CameraServer(int port, String token, Listener listener) {
        this.port = port;
        this.token = token;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        serverSocket = server;
        pool = Executors.newCachedThreadPool();
        running = true;
        pool.execute(this::acceptLoop);
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    public int getViewerCount() {
        return viewers.get();
    }

    /** Publishes the newest JPEG frame to every connected viewer. */
    public void publishFrame(byte[] jpeg) {
        synchronized (frameLock) {
            latestFrame = jpeg;
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                ExecutorService p = pool;
                if (p == null) {
                    socket.close();
                    return;
                }
                p.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        sockets.add(socket);
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            int headerCount = 0;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty() && headerCount++ < 100) {
                // headers are ignored
            }
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                respond(out, 400, "Bad request");
                return;
            }
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> query = parseQuery(q < 0 ? "" : target.substring(q + 1));
            if (!tokenMatches(query.get("t"))) {
                respond(out, 403, "Forbidden");
                return;
            }
            if (Protocol.PATH_STREAM.equals(path)) {
                stream(out);
            } else if (Protocol.PATH_COMMAND.equals(path)) {
                String body = listener.onCommand(query.getOrDefault("name", ""), query.get("arg"));
                respond(out, 200, body == null ? "ok" : body);
            } else {
                respond(out, 404, "Not found");
            }
        } catch (IOException | RuntimeException ignored) {
            // client went away
        } finally {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void stream(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Pragma: no-cache\r\n"
                + "Connection: close\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + Protocol.BOUNDARY + "\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        listener.onViewerCountChanged(viewers.incrementAndGet());
        try {
            long lastSeq = 0;
            while (running) {
                byte[] frame;
                synchronized (frameLock) {
                    if (frameSeq == lastSeq) {
                        frameLock.wait(2000);
                    }
                    if (!running) {
                        break;
                    }
                    if (frameSeq == lastSeq) {
                        frame = null;
                    } else {
                        frame = latestFrame;
                        lastSeq = frameSeq;
                    }
                }
                if (frame == null) {
                    // Keep-alive; also detects viewers that have disappeared.
                    out.write('\r');
                    out.write('\n');
                    out.flush();
                    continue;
                }
                out.write(("--" + Protocol.BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame);
                out.write('\r');
                out.write('\n');
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listener.onViewerCountChanged(viewers.decrementAndGet());
        }
    }

    private boolean tokenMatches(String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Bad Request";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
        return map;
    }
}
