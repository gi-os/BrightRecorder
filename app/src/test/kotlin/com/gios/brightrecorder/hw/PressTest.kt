package com.gios.brightrecorder.hw

import com.gios.brightrecorder.hw.Press.Act
import org.junit.Assert.assertEquals
import org.junit.Test

class PressTest {

    @Test
    fun `a tap plays or stops`() {
        val press = Press()
        assertEquals(Act.None, press.down(recording = false))
        assertEquals(Act.Toggle, press.up())
    }

    @Test
    fun `a hold records`() {
        val press = Press()
        assertEquals(Act.None, press.down(recording = false))
        assertEquals(Act.StartRecording, press.held())
    }

    /** The whole reason the answered flag exists: a hold must not also toggle play on the way up. */
    @Test
    fun `letting go of a hold does not then start the tape over the recording`() {
        val press = Press()
        press.down(recording = false)
        press.held()
        assertEquals(Act.None, press.up())
    }

    @Test
    fun `pressing again stops the recording, without holding`() {
        val press = Press()
        assertEquals(Act.StopRecording, press.down(recording = true))
        assertEquals(Act.None, press.up())
    }

    /** The timer fires whether or not the press is still down, so it has to check. */
    @Test
    fun `the hold timer firing after the release does nothing`() {
        val press = Press()
        press.down(recording = false)
        press.up()
        assertEquals(Act.None, press.held())
    }

    @Test
    fun `holding while recording does not start a second recording`() {
        val press = Press()
        press.down(recording = true)
        assertEquals(Act.None, press.held())
    }

    @Test
    fun `the timer firing twice records once`() {
        val press = Press()
        press.down(recording = false)
        assertEquals(Act.StartRecording, press.held())
        assertEquals(Act.None, press.held())
    }

    @Test
    fun `a release with no press is not a tap`() {
        assertEquals(Act.None, Press().up())
    }

    @Test
    fun `a cancelled press is not a tap`() {
        val press = Press()
        press.down(recording = false)
        press.cancel()
        assertEquals(Act.None, press.up())
    }

    @Test
    fun `tap then hold are read independently`() {
        val press = Press()
        press.down(recording = false)
        assertEquals(Act.Toggle, press.up())
        press.down(recording = false)
        assertEquals(Act.StartRecording, press.held())
    }
}
