package com.fauxx.network.dns

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * A DNS-over-HTTPS resolver Fauxx offers by name (#227). [bootstrap] holds the resolver's own
 * addresses as IP literals, so reaching it never needs the system resolver, which may be the very
 * thing blocking it. Literals parse without any network I/O.
 */
data class DohPreset(val id: String, val label: String, val url: String, val bootstrap: List<String>) {
    fun bootstrapAddresses(): List<InetAddress> = bootstrap.map { InetAddress.getByName(it) }
}

object DohPresets {
    /** Default, matching the desktop companion's `DEFAULT_DOH_RESOLVER`. */
    val QUAD9 = DohPreset(
        id = "quad9", label = "Quad9", url = "https://dns.quad9.net/dns-query",
        bootstrap = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    )
    val CLOUDFLARE = DohPreset(
        id = "cloudflare", label = "Cloudflare", url = "https://cloudflare-dns.com/dns-query",
        bootstrap = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    )
    val MULLVAD = DohPreset(
        id = "mullvad", label = "Mullvad", url = "https://dns.mullvad.net/dns-query",
        bootstrap = listOf("194.242.2.2", "2a07:e340::2"),
    )
    val GOOGLE = DohPreset(
        id = "google", label = "Google", url = "https://dns.google/dns-query",
        bootstrap = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
    )

    val ALL: List<DohPreset> = listOf(QUAD9, CLOUDFLARE, MULLVAD, GOOGLE)

    /** Id stored when the user supplies their own URL (a NextDNS profile, for example). */
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "quad9"

    fun byId(id: String): DohPreset? = ALL.firstOrNull { it.id == id }

    /**
     * Whether [url] is usable as a custom DoH endpoint: https, or http for a resolver on the user's
     * own network only (#334). Anywhere else, a resolver reached in the clear would hand the whole
     * browsing list to the network it is meant to route around. It needs a host, and no
     * `user:password@` part (credentials have no place in a DoH URL, and would end up stored in
     * plain preferences).
     *
     * For http, "on the user's own network" means either a private [serverIp] (then the host may be
     * any name, `pihole` or `dns.home` included: the connection goes to that address), or, without
     * one, a host that is private by itself ([AddressScope.isPrivateHost]). Either way the cleartext
     * client checks the address it actually connects to again.
     */
    fun isValidCustomUrl(url: String, serverIp: String = ""): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.isHttps) return true
        val privateServerIp = parseServerIp(serverIp)?.let(AddressScope::isPrivateNetwork) == true
        return privateServerIp || AddressScope.isPrivateHost(parsed.host)
    }

    /** Whether [url] asks for DoH without encryption (`http://`, #334). Says nothing about validity. */
    fun isCleartext(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == false

    /**
     * Whether [serverIp] may go with [url]: a valid address ([parseServerIp]), and for an `http://`
     * URL a private one, since that is where the unencrypted queries would actually be sent.
     */
    fun isUsableServerIp(url: String, serverIp: String): Boolean {
        val address = parseServerIp(serverIp) ?: return false
        return !isCleartext(url) || AddressScope.isPrivateNetwork(address)
    }

    /**
     * Strict mode (#333) with an http URL whose host is a name and no server IP: only the device's
     * own DNS knows names like `pihole.lan`, and strict mode never asks it, so the address is needed.
     */
    fun needsServerIpInStrict(url: String, serverIp: String): Boolean {
        if (!isCleartext(url) || serverIp.isNotBlank()) return false
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        return LoopbackProxy.parseIpLiteral(host) == null
    }

    /**
     * The optional server address for a custom URL (#227): Fauxx connects there instead of looking
     * up the URL's hostname, which still names the server for TLS. For a resolver on the user's own
     * network, or one whose name their DNS blocks. Numeric only, validated without any lookup
     * (IPv6 may be bracketed); the unspecified address, multicast and link-local are refused. Null
     * for anything else, including blank.
     */
    fun parseServerIp(text: String): InetAddress? {
        val host = text.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return null
        val address = LoopbackProxy.parseIpLiteral(host) ?: return null
        if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return null
        return address
    }
}
