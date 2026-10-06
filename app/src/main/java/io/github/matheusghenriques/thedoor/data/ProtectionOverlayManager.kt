package io.github.matheusghenriques.thedoor.data

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Full-screen TYPE_ACCESSIBILITY_OVERLAY containment shield.
 *
 * The window is added on the service's own context (the only source of the
 * accessibility-overlay window token), covers the whole display including the
 * navigation bar (FLAG_LAYOUT_NO_LIMITS) and consumes every touch. It is also
 * focusable so the back gesture is delivered to it instead of the window behind;
 * hardware keys are consumed separately by the service's onKeyEvent while the
 * overlay is visible (see shouldConsumeKeys).
 *
 * Layer notes (AOSP): the accessibility overlay sits above all activity windows,
 * including Samsung popup/freeform windows and the Overview screen, but below
 * critical system windows (status bar, IME).
 *
 * Visual note: the shield is fully transparent — it draws nothing. Touch
 * interception comes from the window itself (type, flags, full-screen frame
 * plus the view's OnTouchListener) and focus from the view's focusability
 * flags; none of that depends on drawn content (AOSP: the touchable region is
 * the window frame, and input visibility follows WMS policy visibility,
 * ignoring buffer state — see Layer::fillInputInfo).
 *
 * All calls must happen on the main thread; every method is defensive: a failed
 * add/remove must never crash the service (a dead service is a silent bypass).
 */
class ProtectionOverlayManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "DoorOverlay"
    }

    private var overlayView: View? = null
    private val attachedViews = mutableListOf<View>()

    val isShown: Boolean
        get() = overlayView != null

    /** True while the containment overlay is visible; used by onKeyEvent. */
    fun shouldConsumeKeys(): Boolean = isShown

    fun show(phrase: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (overlayView != null) return
        val wm = try {
            service.getSystemService(WindowManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        val view = try {
            buildView(phrase)
        } catch (e: Exception) {
            Log.w(TAG, "build failed: ${e::class.java.simpleName}")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(view, params)
            attachedViews.add(view)
            overlayView = view
            view.requestFocus()
            Log.d(TAG, "overlay shown")
        } catch (e: Exception) {
            Log.w(TAG, "add failed: ${e::class.java.simpleName} ${e.message}")
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (overlayView == null) return
        // Clear state first so a failing removeView cannot leave the manager in a
        // stale "shown" state (which would consume keys forever). Every view ever
        // added is tracked and re-attempted, so nothing gets orphaned.
        overlayView = null
        val views = attachedViews.toList()
        attachedViews.clear()
        for (v in views) {
            try {
                service.getSystemService(WindowManager::class.java)?.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "remove failed: ${e::class.java.simpleName}")
            }
        }
        Log.d(TAG, "overlay hidden")
    }

    /**
     * Builds the fully transparent shield view. Nothing is drawn: touch
     * interception comes from the window (type/flags/full-screen frame) plus
     * this view's OnTouchListener, and focus comes from the focusability
     * flags — none of that depends on drawn content (see class KDoc).
     *
     * [phrase] is currently unused; kept so a future visual variant (e.g. a
     * small "paused" chip) can consume it without touching call sites.
     */
    private fun buildView(@Suppress("UNUSED_PARAMETER") phrase: String): View {
        return View(service).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, _ -> true }
        }
    }
}
