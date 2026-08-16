package com.alibaba.server.nio.tls;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 解析 TLS Gateway 对外监听地址。
 */
@Slf4j
public final class TlsNetworkAddressResolver {
    public static final String AUTO = "auto";

    private static final String ROUTE_PROBE_ADDRESS = "8.8.8.8";
    private static final int ROUTE_PROBE_PORT = 53;

    private static final String[] EXCLUDED_INTERFACE_MARKERS = {
            "loopback", "lo0", "docker", "podman", "bridge", "vmnet", "vbox",
            "virtualbox", "hyper-v", "vethernet", "vpn", "utun", "tun", "tap",
            "wireguard", "tailscale", "zerotier", "hamachi", "vnic", "parallels",
            "virbr", "wsl", "container", "ipsec", "l2tp", "ppp", "teredo",
            "isatap", "bluetooth", "awdl", "llw", "anpi", "gif0", "stf0"
    };

    private static final String[] PREFERRED_INTERFACE_MARKERS = {
            "ethernet", "wi-fi", "wifi", "wireless", "wlan", "以太网", "无线"
    };

    /**
     * 解析显式地址，或在配置为 auto 时选择当前机器的有效网络地址。
     *
     * @param configuredValue 配置值
     * @return 可用于监听的本机地址
     */
    public InetAddress resolve(String configuredValue) {
        String value = StringUtils.trimToEmpty(configuredValue);
        if (isAutomatic(value)) {
            InetAddress resolved = resolveAutomatically();
            log.info("TLS Gateway 已自动选择本机网络地址: {}", resolved.getHostAddress());
            return resolved;
        }
        return explicitAddress(value);
    }

    /**
     * 判断配置是否要求自动解析。
     *
     * @param value 配置值
     * @return 空值或 auto 时返回 true
     */
    public static boolean isAutomatic(String value) {
        return StringUtils.isBlank(value) || AUTO.equalsIgnoreCase(value.trim());
    }

    private InetAddress resolveAutomatically() {
        try {
            List<AddressCandidate> candidates = discoverCandidates(routeAddress());
            AddressCandidate selected = selectBest(candidates);
            if (selected == null) {
                throw new TlsGatewayConfigurationException(
                        "未找到可用的本机非回环网络地址，"
                                + "请连接网络或设置 NET_SERVER_PUBLIC_IP");
            }
            if (selected.isExcludedInterface()) {
                log.warn(
                        "未发现物理网卡地址，TLS Gateway 将使用备用网络接口: "
                                + "interface={}, address={}",
                        selected.getInterfaceName(),
                        selected.getAddress().getHostAddress());
            }
            return selected.getAddress();
        } catch (SocketException exception) {
            throw new TlsGatewayConfigurationException("枚举本机网络地址失败", exception);
        }
    }

    private InetAddress explicitAddress(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            if (isUnusableAddress(address)) {
                throw new TlsGatewayConfigurationException(
                        "NET_SERVER_PUBLIC_IP 必须是实际局域网或公网地址: " + value);
            }
            return address;
        } catch (UnknownHostException exception) {
            throw new TlsGatewayConfigurationException(
                    "NET_SERVER_PUBLIC_IP 无法解析: " + value,
                    exception);
        }
    }

    private List<AddressCandidate> discoverCandidates(InetAddress routeAddress) throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return Collections.emptyList();
        }
        List<AddressCandidate> candidates = new ArrayList<>();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            boolean excluded;
            try {
                excluded = !networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || networkInterface.isVirtual()
                        || isExcludedInterface(
                                networkInterface.getName(),
                                networkInterface.getDisplayName());
            } catch (SocketException exception) {
                log.debug("跳过无法读取状态的网络接口: interface={}, reason={}",
                        networkInterface.getName(), exception.getMessage());
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (isUnusableAddress(address)) {
                    continue;
                }
                candidates.add(new AddressCandidate(
                        networkInterface.getName(),
                        networkInterface.getDisplayName(),
                        networkInterface.getIndex(),
                        address,
                        excluded,
                        routeAddress != null && routeAddress.equals(address)));
            }
        }
        return candidates;
    }

    private InetAddress routeAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(ROUTE_PROBE_ADDRESS, ROUTE_PROBE_PORT));
            InetAddress address = socket.getLocalAddress();
            return isUnusableAddress(address) ? null : address;
        } catch (SocketException exception) {
            log.debug("无法通过默认路由探测本机地址，将改用网卡排序: {}", exception.getMessage());
            return null;
        }
    }

    static AddressCandidate selectBest(List<AddressCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<AddressCandidate> preferred = new ArrayList<>();
        List<AddressCandidate> fallback = new ArrayList<>();
        for (AddressCandidate candidate : candidates) {
            if (candidate == null || isUnusableAddress(candidate.getAddress())) {
                continue;
            }
            fallback.add(candidate);
            if (!candidate.isExcludedInterface()) {
                preferred.add(candidate);
            }
        }
        List<AddressCandidate> selectable = preferred.isEmpty() ? fallback : preferred;
        selectable.sort(Comparator
                .comparingInt(TlsNetworkAddressResolver::score)
                .reversed()
                .thenComparingInt(AddressCandidate::getInterfaceIndex)
                .thenComparing(AddressCandidate::getInterfaceName)
                .thenComparing(candidate -> candidate.getAddress().getHostAddress()));
        return selectable.isEmpty() ? null : selectable.get(0);
    }

    private static int score(AddressCandidate candidate) {
        int score = 0;
        InetAddress address = candidate.getAddress();
        if (address instanceof Inet4Address) {
            score += 1_000;
        } else if (address instanceof Inet6Address) {
            score += 100;
        }
        if (address.isSiteLocalAddress()) {
            score += 400;
        }
        if (candidate.isRoutePreferred()) {
            score += 500;
        }
        if (isPreferredInterface(candidate.getInterfaceName(), candidate.getDisplayName())) {
            score += 200;
        }
        String interfaceName = candidate.getInterfaceName().toLowerCase(Locale.ROOT);
        if ("en0".equals(interfaceName)
                || "eth0".equals(interfaceName)
                || "wlan0".equals(interfaceName)) {
            score += 50;
        }
        return score;
    }

    private static boolean isUnusableAddress(InetAddress address) {
        return address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress();
    }

    private static boolean isExcludedInterface(String name, String displayName) {
        String normalized = normalizeInterface(name, displayName);
        for (String marker : EXCLUDED_INTERFACE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPreferredInterface(String name, String displayName) {
        String normalizedName = StringUtils.trimToEmpty(name).toLowerCase(Locale.ROOT);
        if (normalizedName.matches("en\\d+")
                || normalizedName.matches("eth\\d+")
                || normalizedName.matches("wlan\\d+")) {
            return true;
        }
        String normalized = normalizeInterface(name, displayName);
        for (String marker : PREFERRED_INTERFACE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeInterface(String name, String displayName) {
        return (StringUtils.trimToEmpty(name) + " " + StringUtils.trimToEmpty(displayName))
                .toLowerCase(Locale.ROOT);
    }

    static final class AddressCandidate {
        private final String interfaceName;
        private final String displayName;
        private final int interfaceIndex;
        private final InetAddress address;
        private final boolean excludedInterface;
        private final boolean routePreferred;

        AddressCandidate(
                String interfaceName,
                String displayName,
                int interfaceIndex,
                InetAddress address,
                boolean excludedInterface,
                boolean routePreferred) {
            this.interfaceName = StringUtils.defaultString(interfaceName);
            this.displayName = StringUtils.defaultString(displayName);
            this.interfaceIndex = interfaceIndex;
            this.address = address;
            this.excludedInterface = excludedInterface;
            this.routePreferred = routePreferred;
        }

        String getInterfaceName() {
            return interfaceName;
        }

        String getDisplayName() {
            return displayName;
        }

        int getInterfaceIndex() {
            return interfaceIndex;
        }

        InetAddress getAddress() {
            return address;
        }

        boolean isExcludedInterface() {
            return excludedInterface;
        }

        boolean isRoutePreferred() {
            return routePreferred;
        }
    }
}
