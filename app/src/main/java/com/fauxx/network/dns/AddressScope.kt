package com.fauxx.network.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where an address sits: on the public internet, or on the user's own network. Shared by the
 * loopback proxy, which refuses to tunnel to anything private, and the custom DoH settings, which
 * accept a cleartext `http://` resolver only on a private network (#334).
 */
internal object AddressScope {

    /** Private-network names a resolver on the user's LAN or VPN typically has (#334). */
    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net")

    /** CGNAT, 100.64.0.0/10: carrier NAT, and the range Tailscale hands out. */
    fun isCgnat(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
    }

    /** IPv6 unique local addresses, fc00::/7. */
    fun isUniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC

    /**
     * Whether [address] is on the user's own network or device: loopback, RFC 1918, CGNAT
     * (Tailscale), IPv4 link-local, or IPv6 unique local. IPv6 link-local (fe80::/10) is left out:
     * it is only reachable with an interface scope, which a URL cannot carry.
     */
    fun isPrivateNetwork(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> true
        address is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || isCgnat(address)
        address is Inet6Address -> isUniqueLocal(address)
        else -> false
    }

    /**
     * Whether [host] (as an `HttpUrl` gives it: lowercase, IPv6 without brackets) names something on
     * a private network: an IP literal in [isPrivateNetwork], `localhost`, or a name under one of the
     * private suffixes. A name is not proof, so whatever it resolves to is checked again before any
     * cleartext query is sent (see [CleartextDohResolver]).
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        LoopbackProxy.parseIpLiteral(h)?.let { return isPrivateNetwork(it) }
        if (h == "localhost") return true
        return PRIVATE_SUFFIXES.any { h.endsWith(it) && h.length > it.length }
    }
}
