package com.fauxx.data.custompool

import java.net.IDN
import java.net.URI
import java.text.Normalizer

/** Why an input line was not added to the custom pool (#328). Each maps to a UI message. */
enum class CustomPoolRejection {
    /** Blank after trimming. Bulk paste skips these silently. */
    EMPTY,

    /** Longer than [CustomPoolValidator.MAX_QUERY_LENGTH] / [CustomPoolValidator.MAX_URL_LENGTH]. */
    TOO_LONG,

    /** Contains control characters (a pasted tab or line break inside one entry, or binary junk). */
    INVALID_CHARACTERS,

    /** Not a web address: unparseable, no host, or a scheme other than http/https. */
    NOT_A_WEB_ADDRESS,

    /** Carries a user name or password (`https://user:pass@host`). */
    HAS_CREDENTIALS,

    /** An IP address, `localhost`, a single-label name or a private-network name. */
    LOCAL_ADDRESS,

    /** Matches the harmful-query blocklist (searches) or the domain blocklist (sites). */
    BLOCKED,

    /** Already in the pool, or repeated within the same paste. */
    DUPLICATE,

    /** The pool for this kind is full ([CustomPoolValidator.MAX_ENTRIES_PER_KIND]). */
    LIMIT_REACHED,
}

/** Result of validating one input line. */
sealed class CustomPoolValidation {
    /** [value] is the normalised form that gets stored. */
    data class Valid(val value: String) : CustomPoolValidation()
    data class Rejected(val reason: CustomPoolRejection) : CustomPoolValidation()
}

/**
 * Parsing, normalisation and safety checks for user-added searches and sites (#328).
 *
 * Pure: the two blocklists are passed in as predicates, so this runs in plain JVM tests and the
 * same rules apply at add time ([CustomPoolRepository]) and again at dispatch time (the crawl list
 * re-checks [isDispatchableUrl] and the domain blocklist, the search module re-checks the query
 * blocklist), because an app update can grow either blocklist after an entry was stored.
 *
 * Site rules, beyond the domain blocklist the crawler applies to every built-in URL:
 * - http and https only. A bare `example.com` is read as `https://example.com/`.
 * - No user info (`user:pass@`), which has no business in a decoy visit and is a classic way to
 *   disguise the real host.
 * - No IP literals, `localhost`, single-label names or private-network suffixes (`.lan`, `.local`,
 *   `.home.arpa`, `.internal`, ...). Fauxx's WebView must not be pointed at the user's router,
 *   NAS or other devices on their network: that is a different kind of request from decoy
 *   browsing, and a page on the open web is what the pool is for.
 * - The fragment is dropped (it never reaches the server) and the host is lowercased, so two
 *   spellings of one page collapse to one entry.
 */
object CustomPoolValidator {

    /** Per-kind cap. Generous for "my local sites", small enough to keep the in-memory snapshot trivial. */
    const val MAX_ENTRIES_PER_KIND = 200

    /** A search box rarely sees more than a sentence; built-in queries are far shorter. */
    const val MAX_QUERY_LENGTH = 200

    /** Long enough for any real article URL, short enough that a pasted blob is rejected. */
    const val MAX_URL_LENGTH = 500

    /** Lines read from one bulk paste. Anything past this is reported as over the limit. */
    const val MAX_BULK_LINES = MAX_ENTRIES_PER_KIND

    /**
     * C0/C1 control characters (a tab or line break inside one entry, binary junk) and the bidi
     * overrides, which can make an entry display as something other than what is dispatched.
     * Zero-width joiners are allowed: several scripts need them, and the query blocklist strips
     * them before matching anyway.
     */
    private val CONTROL_CHARS = Regex("[\\p{Cc}\u202A-\u202E\u2066-\u2069]")
    private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOST_CHARS = Regex("^[a-z0-9.-]+$")

    /** Private-network and special-use suffixes, mirroring the ones the custom DNS screen names. */
    private val LOCAL_SUFFIXES = listOf(
        ".lan", ".local", ".localhost", ".home.arpa", ".internal", ".intranet",
        ".corp", ".home", ".localdomain", ".test", ".example", ".invalid", ".onion",
    )

    /**
     * Validate a search term. Internal whitespace is collapsed and NFC-normalised so the stored
     * text is what gets dispatched; the case is kept, since the user's spelling is the point.
     */
    fun validateQuery(raw: String, isQueryBlocked: (String) -> Boolean): CustomPoolValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return CustomPoolValidation.Rejected(CustomPoolRejection.EMPTY)
        if (CONTROL_CHARS.containsMatchIn(trimmed)) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.INVALID_CHARACTERS)
        }
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
            .replace(WHITESPACE_RUN, " ")
        if (normalized.length > MAX_QUERY_LENGTH) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.TOO_LONG)
        }
        if (isQueryBlocked(normalized)) return CustomPoolValidation.Rejected(CustomPoolRejection.BLOCKED)
        return CustomPoolValidation.Valid(normalized)
    }

    /**
     * Validate and normalise a website address. See the class doc for the rules. [isUrlBlocked]
     * is the domain blocklist, checked against the normalised URL.
     */
    fun validateUrl(raw: String, isUrlBlocked: (String) -> Boolean): CustomPoolValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return CustomPoolValidation.Rejected(CustomPoolRejection.EMPTY)
        if (trimmed.length > MAX_URL_LENGTH) return CustomPoolValidation.Rejected(CustomPoolRejection.TOO_LONG)
        if (CONTROL_CHARS.containsMatchIn(trimmed) || trimmed.any { it.isWhitespace() }) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.INVALID_CHARACTERS)
        }

        // "example.com/news" -> https. Anything that already names a scheme keeps it, so
        // "ftp://..." is rejected below instead of being rewritten, and "javascript:..." or
        // "mailto:..." (a scheme with no dot before the colon) are rejected right here.
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            SCHEME_PREFIX.containsMatchIn(trimmed) && !trimmed.substringBefore(':').contains('.') ->
                return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
            else -> "https://$trimmed"
        }

        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        }
        // The raw authority, not URI.host: java.net.URI leaves host null for an internationalised
        // name ("пример.рф"), which is exactly the kind of local site this feature is for.
        val authority = uri.rawAuthority
            ?: return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        if (authority.contains('@')) return CustomPoolValidation.Rejected(CustomPoolRejection.HAS_CREDENTIALS)
        val (rawHost, port) = splitHostPort(authority)
            ?: return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        if (rawHost.startsWith("[")) return CustomPoolValidation.Rejected(CustomPoolRejection.LOCAL_ADDRESS)
        val host = try {
            IDN.toASCII(rawHost.trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: Exception) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        }
        if (host.isEmpty() || !HOST_CHARS.matches(host)) {
            return CustomPoolValidation.Rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS)
        }
        if (isLocalHost(host)) return CustomPoolValidation.Rejected(CustomPoolRejection.LOCAL_ADDRESS)

        val defaultPort = if (scheme == "https") 443 else 80
        val portPart = if (port == null || port == defaultPort) "" else ":$port"
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val normalized = "$scheme://$host$portPart$path$query"
        if (normalized.length > MAX_URL_LENGTH) return CustomPoolValidation.Rejected(CustomPoolRejection.TOO_LONG)

        if (isUrlBlocked(normalized)) return CustomPoolValidation.Rejected(CustomPoolRejection.BLOCKED)
        return CustomPoolValidation.Valid(normalized)
    }

    /**
     * Dispatch-time re-check for a stored site, independent of the blocklist: still http(s), still
     * has a public-looking host and no credentials. Stored values passed [validateUrl], so this
     * only fails for rows written by a buggy or tampered build; it fails closed.
     */
    fun isDispatchableUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return !isLocalHost(host)
    }

    /** Host of a stored (already normalised) URL, or null if it cannot be parsed. */
    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** Splits a bulk paste into candidate lines: one entry per line, blank lines dropped. */
    fun splitLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * Plans adding [lines] (one entry each, as typed or pasted) to a pool that already holds
     * [existing] values and has room for [capacity] more. Validates each line with [validate],
     * drops repeats (case-insensitively, against both the pool and earlier lines of the same paste)
     * and stops accepting once the pool would be full. Lines past [MAX_BULK_LINES] are counted as
     * [CustomPoolRejection.LIMIT_REACHED] without being parsed. Blank lines are ignored.
     */
    fun planAdd(
        lines: List<String>,
        existing: Collection<String>,
        capacity: Int,
        validate: (String) -> CustomPoolValidation,
    ): CustomPoolAddPlan {
        val seen = existing.mapTo(mutableSetOf()) { it.lowercase() }
        val accepted = mutableListOf<String>()
        val rejections = linkedMapOf<CustomPoolRejection, Int>()
        fun reject(reason: CustomPoolRejection, count: Int = 1) {
            rejections[reason] = (rejections[reason] ?: 0) + count
        }
        val candidates = lines.filter { it.isNotBlank() }
        candidates.take(MAX_BULK_LINES).forEach { line ->
            when (val result = validate(line)) {
                is CustomPoolValidation.Rejected ->
                    if (result.reason != CustomPoolRejection.EMPTY) reject(result.reason)
                is CustomPoolValidation.Valid -> when {
                    !seen.add(result.value.lowercase()) -> reject(CustomPoolRejection.DUPLICATE)
                    accepted.size >= capacity -> reject(CustomPoolRejection.LIMIT_REACHED)
                    else -> accepted += result.value
                }
            }
        }
        if (candidates.size > MAX_BULK_LINES) {
            reject(CustomPoolRejection.LIMIT_REACHED, candidates.size - MAX_BULK_LINES)
        }
        return CustomPoolAddPlan(accepted, rejections)
    }

    /** Splits "host" or "host:port" (port 1..65535). Null when the port part is malformed. */
    private fun splitHostPort(authority: String): Pair<String, Int?>? {
        if (authority.startsWith("[")) return authority to null // IPv6 literal, rejected by the caller
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return authority.substring(0, colon) to port
    }

    private fun isLocalHost(host: String): Boolean {
        val h = host.trimEnd('.')
        if (h.startsWith("[") || h.contains(':')) return true // IPv6 literal
        if (IPV4.matches(h)) return true
        if (h == "localhost") return true
        if (!h.contains('.')) return true // single-label name: only resolvable on a local network
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }
}

/**
 * Outcome of [CustomPoolValidator.planAdd]: the normalised values to store, and how many lines were
 * turned away for each reason (insertion order, so the UI can show the first reason).
 */
data class CustomPoolAddPlan(
    val accepted: List<String>,
    val rejections: Map<CustomPoolRejection, Int>,
) {
    val rejectedCount: Int get() = rejections.values.sum()
}
