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

/**
 * Viewer-side client. Connects to a {@link CameraServer}, delivers JPEG frames and
 * reconnects automatically until {@link #stop()} is called.
 */
public final class MjpegClient {

    public interface Listener {
        /** Called from the client thread. */
        void onConnected();

        /** Called from the client thread with one complete JPEG image. */
        void onFrame(byte[] jpeg);

        /** Called from the client thread when the connection drops; the client will retry. */
        void onDisconnected(String reason);
    }

    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private final PairingInfo info;
    private final Listener listener;
    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;

    public MjpegClient(PairingInfo info, Listener listener) {
        this.info = info;
        this.listener = listener;
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

    private void run() {
        long backoffMs = 1000;
        while (running) {
            String reason;
            boolean connected = false;
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress(info.host, info.port), 5000);
                s.setSoTimeout(10_000);
                s.setTcpNoDelay(true);
                sendRequest(s.getOutputStream(), Protocol.PATH_STREAM + "?t=" + enc(info.token));
                InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
                readStatusAndHeaders(in);
                connected = true;
                backoffMs = 1000;
                listener.onConnected();
                readFrames(in);
                reason = "Connection closed";
            } catch (IOException e) {
                reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                socket = null;
            }
            if (!running) {
                return;
            }
            listener.onDisconnected(connected ? reason : "Cannot reach camera (" + reason + ")");
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 5000);
        }
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
            listener.onFrame(frame);
        }
    }

    /** Sends one control command and returns the camera's status text. */
    public static String sendCommand(PairingInfo info, String name, String arg) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(info.host, info.port), 4000);
            s.setSoTimeout(4000);
            String path = Protocol.PATH_COMMAND + "?t=" + enc(info.token) + "&name=" + enc(name)
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
