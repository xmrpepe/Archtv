package dev.archdock

import dev.archdock.bluetooth.HidDeviceKind
import dev.archdock.bluetooth.HidDeviceKind.Companion.classify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HidDeviceKindTest {
    private val peripheral = HidDeviceKind.MAJOR_PERIPHERAL

    @Test fun classicClassOfDevice() {
        assertEquals(HidDeviceKind.KEYBOARD, classify(peripheral, 0x0540, false, "Whatever"))
        assertEquals(HidDeviceKind.MOUSE, classify(peripheral, 0x0580, false, null))
        assertEquals(HidDeviceKind.KEYBOARD_MOUSE, classify(peripheral, 0x05C0, false, null))
        assertEquals(HidDeviceKind.GAMEPAD, classify(peripheral, 0x0508, false, null))
        assertEquals(HidDeviceKind.INPUT, classify(peripheral, 0x0500, false, null))
    }

    @Test fun leDevicesWithoutClass() {
        assertEquals(HidDeviceKind.KEYBOARD, classify(0, 0, true, "MX Keys Mini"))
        assertEquals(HidDeviceKind.MOUSE, classify(0, 0, true, "MX Master 3S"))
        assertEquals(HidDeviceKind.INPUT, classify(0, 0, true, "BT5.0 Device"))
        assertEquals(HidDeviceKind.GAMEPAD, classify(0, 0, false, "Xbox Wireless Controller"))
    }

    @Test fun nonInputDevicesAreOther() {
        // Major class 0x0400 = audio/video (headphones), no HID advert.
        assertEquals(HidDeviceKind.OTHER, classify(0x0400, 0x0418, false, "WH-1000XM5"))
        assertEquals(HidDeviceKind.OTHER, classify(0, 0, false, null))
        assertFalse(HidDeviceKind.OTHER.isInput)
    }
}
