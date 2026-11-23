package com.borislav007.bzview.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Address + secret token that lets a viewer connect to one camera phone.
 *
 * <p>The camera has a local (Wi-Fi) address and, when it runs a VPN such as Tailscale, a remote
 * address that works from anywhere. Viewers try the local address first and fall back to the
 * remote one. Text form: {@code bzview://192.168.1.20:8765/TOKEN?r=100.101.102.103}.
 */
public final class PairingInfo {

    private static final Pattern PATTERN = Pattern.compile("^" + Protocol.SCHEME
            + "://([A-Za-z0-9.\\-]+):(\\d{1,5})/([A-Za-z0-9]{4,64})"
            + "(?:\\?r=([A-Za-z0-9.\\-]+)(?::(\\d{1,5}))?)?$");

    /** One way to reach the camera. */
    public static final class Route {
        public final String host;
        public final int port;
        /** True for the internet/VPN route, false for the same-network (Wi-Fi) route. */
        public final boolean remote;

        public Route(String host, int port, boolean remote) {
            this.host = host;
            this.port = port;
            this.remote = remote;
        }

        public boolean sameAs(Route other) {
            return other != null && host.equals(other.host) && port == other.port;
        }
    }

    public final String host;
    public final int port;
    public final String token;
    /** VPN / internet address of the camera, or null when it has none. */
    public final String remoteHost;
    public final int remotePort;

    public PairingInfo(String host, int port, String token) {
        this(host, port, token, null, port);
    }

    public PairingInfo(String host, int port, String token, String remoteHost, int remotePort) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public boolean hasRemote() {
        return remoteHost != null;
    }

    /**
     * Learns the camera's remote address from its status text (a {@code remote=} line), so a
     * viewer paired on the local network gets the remote route without scanning again.
     *
     * @return the updated pairing, or null when the status adds nothing new
     */
    public PairingInfo withReportedRemote(String statusText) {
        if (statusText == null) {
            return null;
        }
        for (String line : statusText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("remote=")) {
                String remote = t.substring(7);
                if (!remote.matches("[A-Za-z0-9.\\-]+") || remote.equals(host) || remote.equals(remoteHost)) {
                    return null;
                }
                return new PairingInfo(host, port, token, remote, port);
            }
        }
        return null;
    }

    /** Routes in the order a viewer should try them: local first, then remote. */
    public List<Route> routes() {
        List<Route> list = new ArrayList<>(2);
        list.add(new Route(host, port, isVpnAddress(host)));
        if (remoteHost != null && !(remoteHost.equals(host) && remotePort == port)) {
            list.add(new Route(remoteHost, remotePort, true));
        }
        return list;
    }

    public String toUri() {
        String uri = Protocol.SCHEME + "://" + host + ":" + port + "/" + token;
        if (remoteHost != null) {
            uri += "?r=" + remoteHost + (remotePort != port ? ":" + remotePort : "");
        }
        return uri;
    }

    /** Address that opens the web viewer from the same Wi-Fi, e.g. http://192.168.1.20:8765/?t=ab12cd34 */
    public String toWebUrl() {
        return "http://" + host + ":" + port + Protocol.PATH_ROOT + "?t=" + token;
    }

    /** Address that opens the web viewer through the VPN, or null when there is no remote address. */
    public String toRemoteWebUrl() {
        return remoteHost == null ? null
                : "http://" + remoteHost + ":" + remotePort + Protocol.PATH_ROOT + "?t=" + token;
    }

    /**
     * True for addresses that only exist inside a VPN overlay (Tailscale uses 100.64.0.0/10 and
     * *.ts.net names), so a route to them counts as remote even when it is the only one.
     */
    public static boolean isVpnAddress(String host) {
        if (host == null) {
            return false;
        }
        if (host.toLowerCase().endsWith(".ts.net")) {
            return true;
        }
        String[] p = host.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns null when the text is not a valid pairing address. */
    public static PairingInfo parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        int port = Integer.parseInt(m.group(2));
        if (port < 1 || port > 65535) {
            return null;
        }
        String remote = m.group(4);
        int remotePort = port;
        if (m.group(5) != null) {
            remotePort = Integer.parseInt(m.group(5));
            if (remotePort < 1 || remotePort > 65535) {
                return null;
            }
        }
        return new PairingInfo(m.group(1), port, m.group(3), remote, remotePort);
    }
}
