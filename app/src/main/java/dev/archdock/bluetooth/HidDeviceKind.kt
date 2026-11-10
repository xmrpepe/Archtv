package dev.archdock.bluetooth

/** What kind of input device a Bluetooth device looks like. Pure logic, unit-tested. */
enum class HidDeviceKind(val label: String) {
    KEYBOARD("Keyboard"),
    MOUSE("Mouse"),
    KEYBOARD_MOUSE("Keyboard + mouse"),
    GAMEPAD("Game controller"),
    INPUT("Input device"),
    OTHER("Other");

    val isInput get() = this != OTHER

    companion object {
        /** Bluetooth Class of Device: major class "Peripheral". */
        const val MAJOR_PERIPHERAL = 0x0500
        /** BLE HID-over-GATT service, 0x1812. */
        const val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb"

        /**
         * @param majorClass BluetoothClass.majorDeviceClass (0 when unknown, e.g. LE-only)
         * @param deviceClass BluetoothClass.deviceClass (major | minor bits)
         * @param advertisesHid the device advertises the HID-over-GATT service
         * @param name advertised name, used as a last resort
         */
        fun classify(majorClass: Int, deviceClass: Int, advertisesHid: Boolean, name: String?): HidDeviceKind {
            if (majorClass == MAJOR_PERIPHERAL) {
                val keyboard = deviceClass and 0x40 != 0
                val pointing = deviceClass and 0x80 != 0
                val minor = deviceClass and 0x3C
                return when {
                    keyboard && pointing -> KEYBOARD_MOUSE
                    keyboard -> KEYBOARD
                    pointing -> MOUSE
                    minor == 0x04 || minor == 0x08 -> GAMEPAD // joystick / gamepad
                    else -> INPUT
                }
            }
            val n = name?.lowercase().orEmpty()
            val byName = when {
                listOf("keyboard", "keys", "kbd", "k380", "k780", "mx keys", "keychron").any { it in n } -> KEYBOARD
                listOf("mouse", "mx master", "mx anywhere", "trackball", "trackpad").any { it in n } -> MOUSE
                listOf("controller", "gamepad", "xbox", "dualsense", "8bitdo").any { it in n } -> GAMEPAD
                else -> null
            }
            return byName ?: if (advertisesHid) INPUT else OTHER
        }
    }
}
