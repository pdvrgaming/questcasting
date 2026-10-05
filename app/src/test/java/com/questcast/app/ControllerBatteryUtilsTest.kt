package com.questcast.app

import com.questcast.app.util.ControllerBatteryUtils
import org.junit.Assert.*
import org.junit.Test

class ControllerBatteryUtilsTest {

    @Test
    fun testParseOvrRemoteOutput_singleLineFormats() {
        val sample1 = """
            OVRRemoteService status:
            Paired device: 2c:26:17:aa:bb:cc, Type: Left, Connected: true, Battery: 78%, Firmware: 1.15.0
            Paired device: 2c:26:17:dd:ee:ff, Type: Right, Connected: true, Battery: 92%, Firmware: 1.15.0
        """.trimIndent()

        val result1 = ControllerBatteryUtils.parseOvrRemoteOutput(sample1)
        assertEquals(78, result1.left)
        assertEquals(92, result1.right)

        val sample2 = """
            [0] Device: Left Touch Controller, Battery: 65%
            [1] Device: Right Touch Controller, Battery: 100%
        """.trimIndent()

        val result2 = ControllerBatteryUtils.parseOvrRemoteOutput(sample2)
        assertEquals(65, result2.left)
        assertEquals(100, result2.right)
    }

    @Test
    fun testParseOvrRemoteOutput_multiLineSections() {
        val sample = """
            Remote Left:
              Connected: true
              Battery Level: 84%
              Firmware: 2.1.0
            Remote Right:
              Connected: true
              Battery Level: 45%
              Firmware: 2.1.0
        """.trimIndent()

        val result = ControllerBatteryUtils.parseOvrRemoteOutput(sample)
        assertEquals(84, result.left)
        assertEquals(45, result.right)
    }

    @Test
    fun testParseDumpsysInputOutput() {
        val sample = """
            Input Devices:
              Device 3: Oculus Touch Controller (Left)
                Classes: 0x80000003
                Path: /dev/input/event4
                Battery: 80%
              Device 4: Oculus Touch Controller (Right)
                Classes: 0x80000003
                Path: /dev/input/event5
                Battery: 95%
        """.trimIndent()

        val result = ControllerBatteryUtils.parseDumpsysInputOutput(sample)
        assertEquals(80, result.left)
        assertEquals(95, result.right)
    }

    @Test
    fun testParseDumpsysInputOutput_floatCapacity() {
        val sample = """
            Input Devices:
              Device 1: Meta Quest Touch Plus Controller (Left)
                BatteryState: capacity=0.88, status=2
              Device 2: Meta Quest Touch Plus Controller (Right)
                BatteryState: capacity=0.72, status=2
        """.trimIndent()

        val result = ControllerBatteryUtils.parseDumpsysInputOutput(sample)
        assertEquals(88, result.left)
        assertEquals(72, result.right)
    }

    @Test
    fun testParseEmptyOrInvalidOutput() {
        val result = ControllerBatteryUtils.parseOvrRemoteOutput("Service not running or unauthorized")
        assertNull(result.left)
        assertNull(result.right)
    }
}
