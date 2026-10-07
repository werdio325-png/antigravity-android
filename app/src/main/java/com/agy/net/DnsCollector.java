package com.agy.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * All visible DNS servers (active network first, then every other), IPv4
 * ordered before IPv6 and de-duplicated. Scoped IPv6 addresses are dropped.
 * Falls back to 1.1.1.1 / 8.8.8.8 when nothing is visible.
 */
public final class DnsCollector {

    private static final String TAG = "DnsCollector";

    private DnsCollector() {
    }

    public static List<String> collectDnsServers(Context context) {
        List<String> ipv4 = new ArrayList<>();
        List<String> ipv6 = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    addDns(ipv4, ipv6, cm.getLinkProperties(active));
                }
                for (Network network : cm.getAllNetworks()) {
                    addDns(ipv4, ipv6, cm.getLinkProperties(network));
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "ACCESS_NETWORK_STATE missing; using fallback DNS", e);
        } catch (Exception e) {
            Log.w(TAG, "DNS discovery failed", e);
        }
        List<String> servers = new ArrayList<>(ipv4.size() + ipv6.size());
        servers.addAll(ipv4);
        servers.addAll(ipv6);
        if (servers.isEmpty()) {
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
        }
        return servers;
    }

    private static void addDns(List<String> ipv4, List<String> ipv6, LinkProperties link) {
        if (link == null) {
            return;
        }
        for (InetAddress addr : link.getDnsServers()) {
            if (addr == null) {
                continue;
            }
            String ip = addr.getHostAddress();
            if (ip == null || ip.isEmpty() || ip.indexOf('%') >= 0) {
                continue;
            }
            if (addr instanceof Inet4Address) {
                if (!ipv4.contains(ip)) {
                    ipv4.add(ip);
                }
            } else if (addr instanceof Inet6Address) {
                if (!ipv6.contains(ip)) {
                    ipv6.add(ip);
                }
            }
        }
    }
}
