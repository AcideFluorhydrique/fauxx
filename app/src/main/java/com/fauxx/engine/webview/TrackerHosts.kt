package com.fauxx.engine.webview

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Well-known ad, analytics and tracking hosts, loaded from `assets/tracker_hosts.json` (#341).
 *
 * Used only to notice when Fauxx's requests to trackers are failing (see [TrackerReachability]).
 * It never blocks or allows anything, so unlike [com.fauxx.data.crawllist.DomainBlocklist] it
 * fails OPEN: if the asset cannot be read, [isTracker] answers false for every host and the
 * detection is simply off.
 *
 * A host matches an entry exactly or as a subdomain of it, the same rule as
 * `DomainBlocklist.isBlocked`: `stats.g.doubleclick.net` matches `doubleclick.net`.
 */
@Singleton
class TrackerHosts internal constructor(private val load: () -> Set<String>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ loadFromAssets(context) })

    // Loaded on first use, which is the first request a pooled WebView makes (a background thread).
    private val domains: Set<String> by lazy {
        runCatching(load).getOrElse {
            Timber.w(it, "tracker_hosts.json could not be loaded; blocked-tracker detection is off")
            emptySet()
        }
    }

    /** True once the list loaded with at least one entry. False means detection is off. */
    val available: Boolean get() = domains.isNotEmpty()

    /**
     * True if [host] is, or is a subdomain of, a listed tracker host. Called for every allowed
     * request, so it walks the host's parent domains with set lookups instead of scanning the list.
     */
    fun isTracker(host: String): Boolean {
        val list = domains
        if (list.isEmpty()) return false
        var candidate = host.lowercase().trim().trimStart('.').trimEnd('.')
        while (candidate.isNotEmpty()) {
            if (candidate in list) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    internal companion object {
        const val ASSET = "tracker_hosts.json"

        /** Parse the asset's JSON. Entries are normalised the way [isTracker] normalises hosts. */
        fun parse(json: String): Set<String> =
            (Gson().fromJson(json, TrackerHostsJson::class.java)?.domains ?: emptyList())
                .map { it.lowercase().trim().trimStart('.').trimEnd('.') }
                .filter { it.isNotEmpty() }
                .toSet()

        private fun loadFromAssets(context: Context): Set<String> =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}

/**
 * JSON structure of tracker_hosts.json. @Keep for the same reason as the blocklist model: R8 would
 * otherwise rename the field in release builds and Gson would load an empty list, silently turning
 * the detection off.
 */
@Keep
internal data class TrackerHostsJson(val domains: List<String>? = null)
