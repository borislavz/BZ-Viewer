package com.borislav007.bzview.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class NetUtil {

    private NetUtil() {
    }

    /** Interfaces created by VPN apps (Tailscale, WireGuard, ZeroTier, OpenVPN...). */
    private static boolean isVpnInterface(String name) {
        return name.startsWith("tun") || name.startsWith("tailscale") || name.startsWith("wg")
                || name.startsWith("zt") || name.startsWith("utun") || name.startsWith("ppp");
    }

    /**
     * Returns this phone's private (LAN) IPv4 address, preferring Wi-Fi / hotspot interfaces,
     * or null when the phone has no local network address. VPN interfaces are ignored.
     */
    public static String localIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (isVpnInterface(name)) {
                    continue;
                }
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

    /**
     * Returns this phone's address inside a VPN overlay such as Tailscale (100.64.0.0/10),
     * or null when no VPN is connected. Viewers use it to reach the camera from outside the home.
     */
    public static String vpnIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                // A carrier-grade-NAT address on a mobile-data interface also lies in 100.64/10,
                // so the interface must be a VPN one.
                if (!isVpnInterface(name)) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && PairingInfo.isVpnAddress(address.getHostAddress())) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
