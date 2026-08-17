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
 * a neighbourhood beats a city, a city beats a region, and coordinates beat nothing. The one
 * thing it will not do is guess — [Naming.NOWHERE] is the answer when there is no answer.
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
     * Safe to call more than once and safe to cancel. Each stage that succeeds updates [current]
     * immediately rather than waiting for the whole chain, so a slow reverse geocode still leaves
     * the clip named with coordinates instead of nothing.
     */
    suspend fun locate() {
        if (!granted()) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val fix = runCatching {
            withTimeout(BUDGET_MS) { fix(manager) }
        }.getOrElse { if (it is TimeoutCancellationException) null else throw it } ?: return

        // Coordinates first, so an offline phone still labels the clip with somewhere.
        current = coordinates(fix)
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

    /** "48.8570, 2.3700" — the answer when there is no name for the place. */
    private fun coordinates(fix: Location): String =
        String.format(Locale.US, "%.4f, %.4f", fix.latitude, fix.longitude)

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
     * An address reduced to the shortest thing that would mean something to you later.
     *
     * "Bastille, Paris" rather than "Place de la Bastille, 75011 Paris, France": a street number
     * is more precision than a memory needs and it makes the clip title too long to read in a
     * list. The country is left off entirely — you know which country you were in.
     */
    private fun name(address: Address): String? {
        val city = address.locality ?: address.subAdminArea ?: address.adminArea
        val area = address.subLocality ?: address.thoroughfare
        return when {
            area != null && city != null && !area.equals(city, ignoreCase = true) -> "$area, $city"
            city != null -> city
            area != null -> area
            else -> address.countryName
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
