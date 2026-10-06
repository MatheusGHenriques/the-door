package io.github.matheusghenriques.thedoor.data

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionShieldTest {

    private val selfPackage = "io.github.matheusghenriques.thedoor"
    private var now = 0L
    private val shield = ProtectionShield({ now }, selfPackage)

    private fun window(
        pkg: String?,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        area: Long = 1_000_000L,
        displayArea: Long = 1_000_000L
    ) = WindowSnapshot(pkg, type, area, displayArea)

    // ---------------------------------------------------------------- floating heuristic

    @Test
    fun `settings window covering most of display is not floating`() {
        assertFalse(window(PackageConstants.SETTINGS, area = 950_000).isFloatingSettings())
    }

    @Test
    fun `small settings window is floating`() {
        assertTrue(window(PackageConstants.SETTINGS, area = 300_000).isFloatingSettings())
    }

    @Test
    fun `split screen settings window is floating`() {
        assertTrue(window(PackageConstants.SETTINGS, area = 500_000).isFloatingSettings())
    }

    @Test
    fun `small non-settings window is not floating`() {
        assertFalse(window(PackageConstants.FIREFOX, area = 300_000).isFloatingSettings())
    }

    @Test
    fun `small non-application settings window is not floating`() {
        assertFalse(
            window(
                PackageConstants.SETTINGS,
                type = AccessibilityWindowInfo.TYPE_SYSTEM,
                area = 300_000
            ).isFloatingSettings()
        )
    }

    // ---------------------------------------------------------------- incident start

    @Test
    fun `floating settings window starts incident and shows overlay`() {
        val visible = shield.onWindows(listOf(window(PackageConstants.SETTINGS, area = 300_000)))
        assertTrue(visible)
        assertTrue(shield.isIncidentActive)
    }

    @Test
    fun `fullscreen settings window alone does not start incident`() {
        val visible = shield.onWindows(listOf(window(PackageConstants.SETTINGS)))
        assertFalse(visible)
        assertFalse(shield.isIncidentActive)
    }

    // ---------------------------------------------------------------- retention

    @Test
    fun `incident keeps overlay while active package window present`() {
        shield.startIncident(PackageConstants.SETTINGS)
        now = 60_000
        val visible = shield.onWindows(listOf(window(PackageConstants.SETTINGS, area = 300_000)))
        assertTrue(visible)
    }

    @Test
    fun `overlay persists during absence debounce then hides`() {
        shield.startIncident(PackageConstants.SETTINGS)

        // First absent scan starts the debounce: still visible.
        now = 1_000
        assertTrue(shield.onWindows(emptyList()))

        // 2s into debounce: still visible.
        now = 3_000
        assertTrue(shield.onWindows(emptyList()))

        // After full 3s of continuous absence: hide and incident cleared.
        now = 4_100
        assertFalse(shield.onWindows(emptyList()))
        assertFalse(shield.isIncidentActive)
    }

    @Test
    fun `reappearance during debounce resets absence timer`() {
        shield.startIncident(PackageConstants.SETTINGS)

        now = 1_000
        assertTrue(shield.onWindows(emptyList()))
        now = 2_500
        assertTrue(shield.onWindows(listOf(window(PackageConstants.SETTINGS, area = 300_000))))

        // Only 1.6s since reappearance scan: full 3s must elapse again.
        now = 4_000
        assertTrue(shield.onWindows(emptyList()))
        now = 5_600
        assertTrue(shield.onWindows(emptyList()))
        now = 7_100
        assertFalse(shield.onWindows(emptyList()))
    }

    @Test
    fun `screen off pauses absence timer and incident survives`() {
        shield.startIncident(PackageConstants.SETTINGS)

        now = 1_000
        assertTrue(shield.onWindows(emptyList()))
        now = 2_000
        shield.onScreenOff()

        // Long "gap" while screen off; on the next scan the debounce restarts.
        now = 3_000_000
        assertTrue(shield.onWindows(emptyList()))
        assertTrue(shield.isIncidentActive)

        now = 3_003_100
        assertFalse(shield.onWindows(emptyList()))
        assertFalse(shield.isIncidentActive)
    }

    // ---------------------------------------------------------------- conservative presence

    @Test
    fun `unidentifiable application window counts as presence during incident`() {
        shield.startIncident(PackageConstants.SETTINGS)
        val visible = shield.onWindows(listOf(window(null)))
        assertTrue(visible)
    }

    @Test
    fun `unidentifiable non-application window does not count as presence`() {
        shield.startIncident(PackageConstants.SETTINGS)
        val visible =
            shield.onWindows(listOf(window(null, type = AccessibilityWindowInfo.TYPE_SYSTEM)))
        // Absence debounce starts, overlay stays for now but no window held it.
        assertTrue(visible)
        now = 3_100
        assertFalse(shield.onWindows(listOf(window(null, type = AccessibilityWindowInfo.TYPE_SYSTEM))))
    }

    @Test
    fun `own package windows never keep the overlay`() {
        shield.startIncident(PackageConstants.SETTINGS)
        now = 1_000
        assertTrue(shield.onWindows(listOf(window(selfPackage))))
        now = 4_100
        assertFalse(shield.onWindows(listOf(window(selfPackage))))
    }

    @Test
    fun `accessibility overlay window never keeps the overlay`() {
        shield.startIncident(PackageConstants.SETTINGS)
        val overlayWindow = window(
            selfPackage,
            type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        )
        now = 1_000
        assertTrue(shield.onWindows(listOf(overlayWindow)))
        now = 4_100
        assertFalse(shield.onWindows(listOf(overlayWindow)))
    }

    @Test
    fun `other active packages are tracked together`() {
        shield.startIncident(PackageConstants.FIREFOX)
        shield.startIncident(PackageConstants.SETTINGS)

        // Firefox gone, Settings still there: still present.
        assertTrue(shield.onWindows(listOf(window(PackageConstants.SETTINGS))))

        // Both gone: debounce starts.
        now = 1_000
        assertTrue(shield.onWindows(emptyList()))
        now = 4_100
        assertFalse(shield.onWindows(emptyList()))
        assertFalse(shield.isIncidentActive)
    }

    @Test
    fun `floating settings detected while another incident is active keeps overlay`() {
        shield.startIncident(PackageConstants.FIREFOX)
        val visible = shield.onWindows(
            listOf(
                window(PackageConstants.FIREFOX),
                window(PackageConstants.SETTINGS, area = 300_000)
            )
        )
        assertTrue(visible)
        // After Firefox closes, floating Settings alone still holds.
        now = 60_000
        assertTrue(
            shield.onWindows(listOf(window(PackageConstants.SETTINGS, area = 300_000)))
        )
    }

    // ---------------------------------------------------------------- escalation

    @Test
    fun `single escalation does not lock`() {
        assertFalse(shield.recordEscalation())
    }

    @Test
    fun `two escalations within window lock`() {
        now = 1_000
        assertFalse(shield.recordEscalation())
        now = 2_000
        assertTrue(shield.recordEscalation())
    }

    @Test
    fun `escalation counter has no reset and does not clear after locking`() {
        now = 1_000
        shield.recordEscalation()
        now = 2_000
        assertTrue(shield.recordEscalation())
        // Lock happened; counter must keep escalating on subsequent triggers.
        now = 3_000
        assertTrue(shield.recordEscalation())
    }

    @Test
    fun `escalation decays after window elapses`() {
        now = 1_000
        shield.recordEscalation()
        now = 2_000
        assertTrue(shield.recordEscalation())
        // More than 10s later: counter restarts at 1.
        now = 15_000
        assertFalse(shield.recordEscalation())
    }
}
