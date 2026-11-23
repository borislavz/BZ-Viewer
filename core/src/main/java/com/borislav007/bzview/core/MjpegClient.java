package com.borislav007.bzview.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Viewer-side client. Connects to a {@link CameraServer}, delivers JPEG frames and
 * reconnects automatically until {@link #stop()} is called.
 */
public final class MjpegClient {

    public interface Listener {
        /** Called from the client thread. */
        void onConnected();

        /**
         * Called from the client thread with one complete JPEG image.
         *
         * @param cameraTimeMs the camera phone's clock when the frame was captured
         *                     (epoch milliseconds), or 0 if the camera did not say
         */
        void onFrame(byte[] jpeg, long cameraTimeMs);

        /** Called from the client thread when the connection drops; the client will retry. */
        void onDisconnected(String reason);

        /**
         * Called from the client thread just before {@link #onConnected()}: whether the camera
         * is being reached over the remote (VPN/internet) route instead of the local network.
         */
        default void onRouteChosen(boolean remote) {
        }
    }

    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    /** The camera sends a frame or a keep-alive at least every 2 s, so silence means trouble. */
    private static final int READ_TIMEOUT_MS = 6000;
    /** A same-network camera answers at once; if it does not, try the remote route. */
    private static final int LOCAL_CONNECT_MS = 2000;
    private static final int REMOTE_CONNECT_MS = 7000;
    private static final int SINGLE_ROUTE_CONNECT_MS = 5000;
    /** While on the remote route, check this often whether the local one works again. */
    private static final long LOCAL_PROBE_INTERVAL_MS = 15_000;

    /** Route that last worked, so control commands go there first. */
    private static volatile String lastGoodRoute;

    private volatile PairingInfo info;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean switchingToLocal;
    private volatile Socket socket;
    private Thread thread;

    public MjpegClient(PairingInfo info, Listener listener) {
        this.info = info;
        this.listener = listener;
    }

    /**
     * Replaces the pairing details (same camera and token), for example after the camera
     * reported a remote address. Takes effect the next time the client connects.
     */
    public void updatePairing(PairingInfo newInfo) {
        info = newInfo;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "bzview-mjpeg-client");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private static int connectTimeout(List<PairingInfo.Route> routes, PairingInfo.Route route) {
        if (routes.size() == 1) {
            return SINGLE_ROUTE_CONNECT_MS;
        }
        return route.remote ? REMOTE_CONNECT_MS : LOCAL_CONNECT_MS;
    }

    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Each round tries the local route first and then the remote one, so the viewer is back on
     * the local network as soon as the phone is home again.
     */
    private void run() {
        long backoffMs = 1000;
        while (running) {
            switchingToLocal = false;
            String reason = "no route";
            Socket connectedSocket = null;
            InputStream in = null;
            PairingInfo.Route used = null;
            final PairingInfo pairing = info;
            List<PairingInfo.Route> routes = pairing.routes();

            for (PairingInfo.Route route : routes) {
                if (!running) {
                    return;
                }
                Socket candidate = new Socket();
                socket = candidate;
                try {
                    candidate.connect(new InetSocketAddress(route.host, route.port),
                            connectTimeout(routes, route));
                    candidate.setSoTimeout(READ_TIMEOUT_MS);
                    candidate.setTcpNoDelay(true);
                    sendRequest(candidate.getOutputStream(), Protocol.PATH_STREAM + "?t=" + enc(pairing.token));
                    InputStream stream = new BufferedInputStream(candidate.getInputStream(), 64 * 1024);
                    // A different device at the same local address (another network) fails here.
                    readStatusAndHeaders(stream);
                    connectedSocket = candidate;
                    in = stream;
                    used = route;
                    break;
                } catch (IOException e) {
                    reason = describe(e);
                    closeQuietly(candidate);
                }
            }

            if (connectedSocket == null) {
                socket = null;
                if (!running) {
                    return;
                }
                listener.onDisconnected("Cannot reach camera (" + reason + ")");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5000);
                continue;
            }

            socket = connectedSocket;
            lastGoodRoute = used.host + ":" + used.port;
            backoffMs = 1000;
            Thread probe = null;
            if (used.remote && !routes.get(0).remote) {
                probe = startLocalProbe(connectedSocket, routes.get(0));
            }
            try {
                listener.onRouteChosen(used.remote);
                listener.onConnected();
                readFrames(in);
                reason = "Connection closed";
            } catch (IOException e) {
                reason = describe(e);
            } finally {
                if (probe != null) {
                    probe.interrupt();
                }
                closeQuietly(connectedSocket);
                socket = null;
            }
            if (!running) {
                return;
            }
            if (switchingToLocal) {
                listener.onDisconnected("Switching to local network");
                continue; // reconnect at once; the local route is tried first
            }
            listener.onDisconnected(reason);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 5000);
        }
    }

    /** While streaming over the remote route, notices when the local route works again. */
    private Thread startLocalProbe(final Socket current, final PairingInfo.Route local) {
        Thread t = new Thread(() -> {
            try {
                while (running && !current.isClosed()) {
                    Thread.sleep(LOCAL_PROBE_INTERVAL_MS);
                    if (!running || current.isClosed()) {
                        return;
                    }
                    try {
                        sendCommand(local, info.token, Protocol.CMD_STATUS, null, 1200);
                    } catch (IOException e) {
                        continue; // still away from home
                    }
                    switchingToLocal = true;
                    closeQuietly(current);
                    return;
                }
            } catch (InterruptedException ignored) {
                // the stream ended
            }
        }, "bzview-route-probe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void readFrames(InputStream in) throws IOException {
        while (running) {
            String line;
            do {
                line = readLine(in);
                if (line == null) {
                    return;
                }
            } while (!line.startsWith("--"));

            int length = -1;
            long cameraTimeMs = 0;
            while (true) {
                line = readLine(in);
                if (line == null) {
                    return;
                }
                if (line.isEmpty()) {
                    break;
                }
                if (line.regionMatches(true, 0, "content-length:", 0, 15)) {
                    try {
                        length = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("Bad frame header");
                    }
                } else if (line.regionMatches(true, 0, Protocol.HEADER_FRAME_TIME + ":", 0,
                        Protocol.HEADER_FRAME_TIME.length() + 1)) {
                    try {
                        cameraTimeMs = Long.parseLong(
                                line.substring(Protocol.HEADER_FRAME_TIME.length() + 1).trim());
                    } catch (NumberFormatException ignored) {
                        cameraTimeMs = 0;
                    }
                }
            }
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("Bad frame length");
            }
            byte[] frame = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(frame, read, length - read);
                if (n < 0) {
                    throw new EOFException("Stream ended");
                }
                read += n;
            }
            listener.onFrame(frame, cameraTimeMs);
        }
    }

    /**
     * Sends one control command and returns the camera's status text. Uses the route that last
     * worked first, then the other one.
     */
    public static String sendCommand(PairingInfo info, String name, String arg) throws IOException {
        List<PairingInfo.Route> routes = new ArrayList<>(info.routes());
        String good = lastGoodRoute;
        if (good != null) {
            for (int i = 1; i < routes.size(); i++) {
                PairingInfo.Route r = routes.get(i);
                if ((r.host + ":" + r.port).equals(good)) {
                    routes.remove(i);
                    routes.add(0, r);
                    break;
                }
            }
        }
        IOException last = null;
        for (PairingInfo.Route route : routes) {
            int connectMs = routes.size() == 1 ? 4000 : (route.remote ? 6000 : 2000);
            try {
                String body = sendCommand(route, info.token, name, arg, connectMs);
                lastGoodRoute = route.host + ":" + route.port;
                return body;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No route");
    }

    private static String sendCommand(PairingInfo.Route route, String token, String name, String arg,
            int connectMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(route.host, route.port), connectMs);
            s.setSoTimeout(Math.max(4000, connectMs));
            String path = Protocol.PATH_COMMAND + "?t=" + enc(token) + "&name=" + enc(name)
                    + (arg != null ? "&arg=" + enc(arg) : "");
            sendRequest(s.getOutputStream(), path);
            InputStream in = new BufferedInputStream(s.getInputStream());
            readStatusAndHeaders(in);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                body.write(buf, 0, n);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sendRequest(OutputStream out, String path) throws IOException {
        out.write(("GET " + path + " HTTP/1.1\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void readStatusAndHeaders(InputStream in) throws IOException {
        String status = readLine(in);
        if (status == null) {
            throw new EOFException("No response");
        }
        if (!status.contains(" 200")) {
            if (status.contains(" 403")) {
                throw new IOException("Wrong pairing code");
            }
            throw new IOException("Camera replied: " + status);
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // skip headers
        }
    }

    /** Reads one line (without CR/LF). Returns null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) {
                throw new IOException("Line too long");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
