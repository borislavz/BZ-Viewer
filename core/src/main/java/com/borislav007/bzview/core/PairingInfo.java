package com.borislav007.bzview.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Address + secret token that lets a viewer connect to one camera phone. */
public final class PairingInfo {

    private static final Pattern PATTERN =
            Pattern.compile("^" + Protocol.SCHEME + "://([A-Za-z0-9.\\-]+):(\\d{1,5})/([A-Za-z0-9]{4,64})$");

    public final String host;
    public final int port;
    public final String token;

    public PairingInfo(String host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
    }

    public String toUri() {
        return Protocol.SCHEME + "://" + host + ":" + port + "/" + token;
    }

    /** Address that opens the web viewer in any browser, e.g. http://192.168.1.20:8765/?t=ab12cd34 */
    public String toWebUrl() {
        return "http://" + host + ":" + port + Protocol.PATH_ROOT + "?t=" + token;
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
        return new PairingInfo(m.group(1), port, m.group(3));
    }
}
