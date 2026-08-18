package com.gios.brightrecorder.hw

import android.view.KeyEvent

/** The wheel's two directions and its press, as they arrive from the hardware. */
enum class LightKey {
    /** Wheel turned towards the top of the phone. */
    WheelUp,

    /** Wheel turned towards the bottom of the phone. */
    WheelDown,

    /**
     * Wheel pressed in.
     *
     * A different device from the turns: the press is a `gpio-keys` button, the turns are the
     * optical sensor, which is why the scancode fallback needs its own trusted device name.
     */
    WheelClick,
}

/**
 * Recognising the LPIII's brightness wheel.
 *
 * The wheel is not a rotary encoder. It is a `Pixart pat9126ja` optical sensor on
 * `/dev/input/event4` that emits one discrete DOWN+UP key pair per notch, roughly 35–60 ms
 * apart, so this is key handling and not `AXIS_SCROLL` / `onRotaryScrollEvent`.
 *
 * Light patched `/system/usr/keylayout/Generic.kl` — the layout every input device on the
 * phone loads — to relabel five scancodes:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up      (Pixart, was R)
 * key 20    WHEEL_CW       # wheel down    (Pixart, was T)
 * key 66    WHEEL_CLICK    # wheel press   (gpio-keys, was F8)
 * key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
 * key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
 * ```
 *
 * Nothing intercepts these in `PhoneWindowManager`; they are dispatched to the focused
 * window like any other key, which is why an app that ignores the keycode gets nothing —
 * and why handling it needs no root and no accessibility service.
 *
 * The turns and the press are handled here; the camera button is not. The press is play/stop,
 * because in this app the wheel already *is* the transport — it shuttles the tape — so pressing
 * it in to start and stop is the one binding that needs no explaining.
 *
 * Caveat worth knowing before debugging a dead press: LightControl claims the wheel click
 * phone-wide, and when it has the click bound that binding wins — it is a foreground service with
 * an accessibility grant and this is an ordinary activity, so nothing here can or should fight it.
 * That is why the press did nothing before LightControl 2.15. It knows this package by name now
 * and stands off the whole wheel, turns and click alike, so no setting has to be changed by hand.
 * On an older LightControl the press still goes to the torch, and the workaround is to set
 * BrightRecorder to "Off" in its per-app list.
 *
 * `WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` are not AOSP keycodes; Light added them, so
 * their integer values are Light's to change. Hence two ways in, in order:
 *
 *  1. Resolve the label to a keycode at runtime. [KeyEvent.keyCodeFromString] reads the
 *     same native label table the keylayout parser uses, so Light's additions resolve.
 *  2. Fall back to the raw Linux scancode, which is fixed by the hardware. Scancode 19 is
 *     also `r` on a Bluetooth keyboard, so that path is gated on the device name.
 */
object LightKeys {

    // Linux scancodes, from `getevent -pl`. These are hardware, not software.
    private const val SCAN_WHEEL_UP = 19 // KEY_R
    private const val SCAN_WHEEL_DOWN = 20 // KEY_T
    private const val SCAN_WHEEL_CLICK = 66 // KEY_F8

    /**
     * Which physical device is allowed to claim which scancode.
     *
     * Per scancode, not one shared set, because the turns and the press come from *different*
     * devices — the turns from the optical sensor, the press from the board's `gpio-keys` — and a
     * shared set would let either device claim either code. That matters here: scancode 66 is F8
     * on a paired Bluetooth keyboard and 19 is `r`, so a loose check turns a keyboard into a
     * transport control.
     */
    private data class Control(val key: LightKey, val device: (String) -> Boolean)

    private val PIXART: (String) -> Boolean = { it == "Pixart pat9126ja" }

    /**
     * The board's button device. Matched by prefix rather than exactly: the name is the kernel's
     * and vendors spell it `gpio-keys`, `gpio_keys` and `gpio-keys-wheel` depending on the
     * devicetree. Nothing else on this phone emits scancode 66, so the looseness costs nothing.
     */
    private val GPIO: (String) -> Boolean = { it.startsWith("gpio", ignoreCase = true) }

    private val byScanCode = mapOf(
        SCAN_WHEEL_UP to Control(LightKey.WheelUp, PIXART),
        SCAN_WHEEL_DOWN to Control(LightKey.WheelDown, PIXART),
        SCAN_WHEEL_CLICK to Control(LightKey.WheelClick, GPIO),
    )

    private val byKeyCode: Map<Int, LightKey> = buildMap {
        putLabel("WHEEL_CCW", LightKey.WheelUp)
        putLabel("WHEEL_CW", LightKey.WheelDown)
        putLabel("WHEEL_CLICK", LightKey.WheelClick)
    }

    private fun MutableMap<Int, LightKey>.putLabel(label: String, key: LightKey) {
        val code = runCatching { KeyEvent.keyCodeFromString(label) }
            .getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        if (code != KeyEvent.KEYCODE_UNKNOWN) put(code, key)
    }

    /** Which control produced [event], or null if it wasn't one of ours. */
    fun of(event: KeyEvent): LightKey? {
        byKeyCode[event.keyCode]?.let { return it }
        // Either the labels moved or this build doesn't have them. Trust the scancode, but only
        // from the device that physically owns that particular code — otherwise a paired
        // keyboard's `r` shuttles the tape and its F8 starts playback.
        val device = event.device?.name ?: return null
        val control = byScanCode[event.scanCode] ?: return null
        return if (control.device(device)) control.key else null
    }

    /** True if this build maps the wheel labels at all — useful for a settings readout. */
    fun wheelLabelsPresent(): Boolean = byKeyCode.containsValue(LightKey.WheelUp)
}
