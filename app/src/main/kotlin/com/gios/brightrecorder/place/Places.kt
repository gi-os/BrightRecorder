package com.gios.brightrecorder.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gios.brightrecorder.tape.Naming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turning a phone into a place name.
 *
 * This runs *during* a recording, not before one. Pressing record starts the microphone in the
 * same frame, and a location fix takes anywhere from a second to never — indoors it is usually
 * never. So the lookup is started alongside the recording and whatever it has found by the time
 * you stop is what the clip is called. A recording is never delayed, never blocked, and never
 * lost because the sky was not visible.
 *
 * The fallback chain is ordered by what is useful to read weeks later rather than by precision:
 * a named place beats the street it is on, a street beats a neighbourhood, and a neighbourhood
 * beats a city. What it will never do is fall back to coordinates or to a guess — a clip nobody
 * could name is [Naming.NOWHERE], which reads as somewhere you have been rather than as a lookup
 * you now have to do yourself.
 */
class Places(private val context: Context) {

    /**
     * Best place name found so far, read when a recording stops.
     *
     * Volatile because it is written by the lookup coroutine and read by whichever thread ends
     * the recording, which is not the same one.
     */
    @Volatile
    var current: String = Naming.NOWHERE
        private set

    /** True once a fix has produced something better than [Naming.NOWHERE]. */
    val located: Boolean get() = current != Naming.NOWHERE

    fun forget() {
        current = Naming.NOWHERE
    }

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Find out where we are, as well as can be managed inside [BUDGET_MS].
     *
     * Safe to call more than once and safe to cancel — the permission is granted while the first
     * recording is already running, so it is called again as soon as the answer arrives.
     */
    suspend fun locate() {
        if (!granted()) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val fix = runCatching {
            withTimeout(BUDGET_MS) { fix(manager) }
        }.getOrElse { if (it is TimeoutCancellationException) null else throw it } ?: return

        // A name or nothing at all. Coordinates used to go in here as a fallback for when the
        // geocoder could not answer, and they turned out to be worse than nothing: a list of clips
        // titled "48.8570, 2.3700" tells you where you were only if you go and look it up, which
        // is precisely the work this app exists to save. A clip nobody could name is honestly
        // [Naming.NOWHERE], and that at least reads as a place you have been.
        describe(fix)?.let { current = it }
    }

    /** A location, from the cheapest source that has one. */
    private suspend fun fix(manager: LocationManager): Location? {
        // A recent cached fix is free and usually good enough for a neighbourhood name.
        lastKnown(manager)?.let { return it }
        for (provider in PROVIDERS) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            current(manager, provider)?.let { return it }
        }
        return null
    }

    private fun lastKnown(manager: LocationManager): Location? {
        var best: Location? = null
        for (provider in PROVIDERS) {
            val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            // Old enough and it is a different city; this app gets used while travelling.
            if (System.currentTimeMillis() - loc.time > STALE_MS) continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    /** One fresh fix from [provider]. */
    private suspend fun current(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                val ok = runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                    ) { location -> if (cont.isActive) cont.resume(location) }
                }.isSuccess
                if (!ok && cont.isActive) cont.resume(null)
            } else {
                // API 29 has no getCurrentLocation. A listener that removes itself is the same
                // thing by hand; the withTimeout around this is what bounds it.
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Required on API 29")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) {
                        runCatching { manager.removeUpdates(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                val ok = runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
                }.isSuccess
                if (!ok && cont.isActive) cont.resume(null)
            }
        }

    /** The best human name for [fix], or null if the geocoder has nothing. */
    private suspend fun describe(fix: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = runCatching {
            withTimeout(BUDGET_MS) { geocode(geocoder, fix) }
        }.getOrNull() ?: return null
        return addresses.firstNotNullOfOrNull { name(it) }
    }

    private suspend fun geocode(geocoder: Geocoder, fix: Location): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(fix.latitude, fix.longitude, MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) {
                            if (cont.isActive) cont.resume(results)
                        }

                        override fun onError(message: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
            }
        } else {
            // Blocking, network-bound, and removed from the main thread by this dispatcher. The
            // deprecation on this overload is the reason the branch above exists.
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching {
                    geocoder.getFromLocation(fix.latitude, fix.longitude, MAX_RESULTS).orEmpty()
                }.getOrDefault(emptyList())
            }
        }

    /**
     * An address reduced to the nearest thing you would actually say out loud.
     *
     * The shape is always **somewhere, city** — "Café de Flore, Paris", "Rue de Lappe, Paris",
     * "Kreuzberg, Berlin" — because that is how a person names where they were. Never a street
     * number, never a postcode, never the country: precision past the corner you were standing on
     * is precision a memory has no use for, and it makes the title too long to read in a list.
     *
     * The order of preference is deliberate. [Address.featureName] is the most specific thing the
     * geocoder found, and where the data has one it is a named place — the café, the station, the
     * park — which beats the street it sits on. Failing that the street, then the neighbourhood.
     *
     * Worth knowing about the limits of this: Android's `Geocoder` is a *reverse geocoder*, not a
     * places search, so it names what is at the coordinates rather than what is interesting
     * nearby. A café comes back only when the fix lands on it. Real "what is around here" naming
     * would mean the Places API — a key, an account, and every recording becoming a billable
     * lookup — which is a different app from this one.
     */
    private fun name(address: Address): String? {
        val city = address.locality ?: address.subAdminArea ?: address.adminArea

        // A featureName that is just a house number is not a place name; the street below it is.
        val feature = address.featureName
            ?.takeIf { f -> f.isNotBlank() && f.any { it.isLetter() } }
            ?.takeIf { !it.equals(city, ignoreCase = true) }
            ?.takeIf { !it.equals(address.postalCode, ignoreCase = true) }

        val spot = feature ?: address.thoroughfare ?: address.subLocality
        return when {
            spot != null && city != null && !spot.equals(city, ignoreCase = true) -> "$spot, $city"
            city != null -> city
            spot != null -> spot
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /**
         * Providers, cheapest first.
         *
         * PASSIVE costs nothing at all — it returns a fix someone else's request produced.
         * NETWORK is a few hundred metres from cell towers and wifi, which is the right scale
         * for a neighbourhood name and works indoors. GPS is last: it is the slowest and the
         * most expensive, and this app never needs metres.
         */
        val PROVIDERS = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )

        /** How long each stage gets. Generous: this runs while a recording is already going. */
        const val BUDGET_MS = 20_000L

        /** A cached fix older than five minutes is not where you are now. */
        const val STALE_MS = 5 * 60 * 1000L

        const val MAX_RESULTS = 3
    }
}
