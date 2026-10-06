package io.github.matheusghenriques.thedoor.data

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Pure state machine for the protection containment overlay.
 *
 * All timing goes through [now] so the lifecycle is unit-testable on the JVM.
 * Main-thread confined; no internal synchronization.
 *
 * Incident lifecycle:
 * - An incident starts on a protection trigger ([startIncident]) or preventively
 *   when a floating (popup/split-screen) Settings window is detected ([onWindows],
 *   gated by the caller's floating-detection flag).
 * - While any window of an active package remains visible, the overlay stays up.
 * - The overlay is only released after [REMOVAL_DEBOUNCE_MS] of CONTINUOUS absence of all
 *   active-package windows. Reappearance resets the timer. This delay only extends
 *   protection; it never creates a bypass window.
 * - Unidentifiable application windows count as presence (conservative).
 * - The service's own windows and accessibility overlays are never counted.
 */
class ProtectionShield(
    private val now: () -> Long,
    private val selfPackage: String
) {

    companion object {
        const val REMOVAL_DEBOUNCE_MS = 3_000L
        const val ESCALATION_THRESHOLD = 2
        const val ESCALATION_WINDOW_MS = 10_000L
        private const val ABSENCE_UNSET = Long.MIN_VALUE
    }

    private val activePackages = LinkedHashSet<String>()
    private var absenceStartedAt = ABSENCE_UNSET
    private var escalationCount = 0
    private var lastEscalationAt = 0L

    val isIncidentActive: Boolean get() = activePackages.isNotEmpty()

    /**
     * Starts or extends the current protection incident. Idempotent per package;
     * calling it resets the absence timer (protection direction).
     */
    fun startIncident(pkg: String) {
        activePackages.add(pkg)
        absenceStartedAt = ABSENCE_UNSET
    }

    /**
     * Records a protection trigger for escalation. Deliberately has NO reset hook:
     * a lockNow() does not clear the counter — it only decays via the time window.
     * Returns true when the escalation threshold is reached (caller should lock).
     */
    fun recordEscalation(): Boolean {
        val t = now()
        escalationCount = if (t - lastEscalationAt > ESCALATION_WINDOW_MS) 1 else escalationCount + 1
        lastEscalationAt = t
        return escalationCount >= ESCALATION_THRESHOLD
    }

    /** Screen turned off: pauses the absence timer but keeps the incident state. */
    fun onScreenOff() {
        absenceStartedAt = ABSENCE_UNSET
    }

    /**
     * Evaluates the latest window snapshot and returns whether the containment
     * overlay should be visible right now.
     *
     * [floatingDetectionEnabled] gates the PREVENTIVE start of an incident on a
     * floating Settings window (the service passes the accessibility-protection
     * toggle — if the user did not enable that protection, opening Settings in
     * popup/split-screen must not contain anything). Once an incident is active,
     * a floating Settings window counts as presence regardless of the gate:
     * retention rules never loosen.
     */
    fun onWindows(
        snapshots: List<WindowSnapshot>,
        floatingDetectionEnabled: Boolean = true
    ): Boolean {
        val floatingSettings = snapshots.any { it.isFloatingSettings() }

        if (!isIncidentActive) {
            if (floatingDetectionEnabled && floatingSettings) {
                startIncident(PackageConstants.SETTINGS)
            }
            return floatingDetectionEnabled && floatingSettings
        }

        val present = floatingSettings || snapshots.any { it.isRelevantTo(activePackages, selfPackage) }
        if (present) {
            absenceStartedAt = ABSENCE_UNSET
            return true
        }

        if (absenceStartedAt == ABSENCE_UNSET) {
            absenceStartedAt = now()
            return true
        }
        return if (now() - absenceStartedAt < REMOVAL_DEBOUNCE_MS) {
            true
        } else {
            activePackages.clear()
            absenceStartedAt = ABSENCE_UNSET
            false
        }
    }
}

/**
 * Framework-free view of an [AccessibilityWindowInfo]. [packageName] is null when the
 * window could not be introspected (no root node).
 */
data class WindowSnapshot(
    val packageName: String?,
    val windowType: Int,
    val boundsArea: Long,
    val displayArea: Long
) {
    companion object {
        const val FLOATING_AREA_RATIO_MAX = 0.8f
    }

    val areaRatio: Float
        get() = if (displayArea > 0) boundsArea.toFloat() / displayArea.toFloat() else 1f

    /** Floating/popup/split-screen Settings window: the preventive-detection heuristic. */
    fun isFloatingSettings(): Boolean {
        return packageName == PackageConstants.SETTINGS &&
                windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                areaRatio < FLOATING_AREA_RATIO_MAX
    }

    /**
     * Whether this window should keep the overlay up during an incident.
     * Null-package application windows count as presence (conservative); the service's
     * own windows and accessibility overlays never do.
     */
    fun isRelevantTo(activePackages: Set<String>, selfPackage: String): Boolean {
        if (windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
        if (packageName == selfPackage) return false
        if (packageName == null) return windowType == AccessibilityWindowInfo.TYPE_APPLICATION
        return packageName in activePackages
    }
}
