package com.borislav007.bzview.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class NetUtil {

    private NetUtil() {
    }

    /**
     * Returns this phone's private (LAN) IPv4 address, preferring Wi-Fi / hotspot interfaces,
     * or null when the phone has no local network address.
     */
    public static String localIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                boolean wifiLike = name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("swlan") || name.startsWith("eth");
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        if (wifiLike) {
                            return address.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
