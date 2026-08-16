package com.alibaba.server.nio.tls;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TlsNetworkAddressResolverTest {

    @Test
    public void prefersPhysicalLanIpv4OverVpnAndVirtualInterfaces() throws UnknownHostException {
        TlsNetworkAddressResolver.AddressCandidate selected =
                TlsNetworkAddressResolver.selectBest(Arrays.asList(
                        candidate("utun4", "VPN", 4, "10.8.0.2", true, true),
                        candidate("docker0", "Docker", 5, "172.17.0.1", true, false),
                        candidate("en0", "Wi-Fi", 6, "192.168.0.101", false, false)));

        assertEquals("192.168.0.101", selected.getAddress().getHostAddress());
    }

    @Test
    public void prefersDefaultRouteWhenMultiplePhysicalInterfacesAreAvailable() throws UnknownHostException {
        TlsNetworkAddressResolver.AddressCandidate selected =
                TlsNetworkAddressResolver.selectBest(Arrays.asList(
                        candidate("en1", "USB Ethernet", 7, "192.168.2.10", false, false),
                        candidate("en0", "Wi-Fi", 6, "192.168.0.101", false, true)));

        assertEquals("192.168.0.101", selected.getAddress().getHostAddress());
    }

    @Test
    public void recognizesEmptyAndAutoConfiguration() {
        assertTrue(TlsNetworkAddressResolver.isAutomatic(null));
        assertTrue(TlsNetworkAddressResolver.isAutomatic(" "));
        assertTrue(TlsNetworkAddressResolver.isAutomatic("AUTO"));
    }

    private TlsNetworkAddressResolver.AddressCandidate candidate(
            String interfaceName,
            String displayName,
            int interfaceIndex,
            String address,
            boolean excluded,
            boolean routePreferred) throws UnknownHostException {
        return new TlsNetworkAddressResolver.AddressCandidate(
                interfaceName,
                displayName,
                interfaceIndex,
                InetAddress.getByName(address),
                excluded,
                routePreferred);
    }
}
