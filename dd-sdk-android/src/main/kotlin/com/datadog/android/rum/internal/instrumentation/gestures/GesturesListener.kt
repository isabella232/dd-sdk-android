/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.rum.internal.instrumentation.gestures

import android.content.res.Resources
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import androidx.core.view.ScrollingView
import com.datadog.android.core.internal.utils.devLogger
import com.datadog.android.rum.GlobalRum
import com.datadog.android.rum.RumAttributes
import com.datadog.android.rum.internal.monitor.DatadogRumMonitor
import com.datadog.android.rum.tracking.ViewAttributesProvider
import java.lang.ref.WeakReference
import java.util.LinkedList
import kotlin.math.abs

internal class GesturesListener(
    private val decorViewReference: WeakReference<View>,
    private val attributesProviders: Array<ViewAttributesProvider> = emptyArray()

) :
    GestureDetector.OnGestureListener {

    private val coordinatesContainer = IntArray(2)
    private var scrollEventType = NO_EVENT
    private var gestureDirection = ""
    private var scrollTargetReference: WeakReference<View?> = WeakReference(null)
    private var currentDownEvent: MotionEvent? = null

    // region GesturesListener

    override fun onShowPress(e: MotionEvent) {
        // No Op
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val decorView: View? = decorViewReference.get()
        handleTapUp(decorView, e)
        return false
    }

    override fun onDown(e: MotionEvent): Boolean {
        resetScrollEventParameters()
        currentDownEvent = e
        return false
    }

    fun onUp(event: MotionEvent) {
        closeScrollOrSwipeEventIfAny(decorViewReference.get(), event)
        resetScrollEventParameters()
    }

    override fun onFling(
        startDownEvent: MotionEvent,
        endUpEvent: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {

        scrollEventType = SWIPE_EVENT
        return false
    }

    override fun onScroll(
        startDownEvent: MotionEvent,
        currentMoveEvent: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        val rumMonitor = GlobalRum.get()
        val decorView = decorViewReference.get()
        if (decorView == null || rumMonitor !is DatadogRumMonitor) {
            return false
        }

        // we only start the user action once
        if (scrollEventType == NO_EVENT) {
            // check if we find a valid target
            val scrollTarget = findTargetForScroll(decorView, startDownEvent.x, startDownEvent.y)
            if (scrollTarget != null) {
                scrollTargetReference = WeakReference(scrollTarget)
                rumMonitor.startUserAction()
            } else {
                return false
            }
            scrollEventType = SCROLL_EVENT
        }

        return false
    }

    override fun onLongPress(e: MotionEvent) {
        // No Op
    }

    // endregion

    // region Internal

    private fun closeScrollOrSwipeEventIfAny(decorView: View?, onUpEvent: MotionEvent) {
        if (scrollEventType == NO_EVENT) {
            return
        }
        val registeredRumMonitor = GlobalRum.get()
        val scrollTarget = scrollTargetReference.get()
        if (decorView == null ||
            registeredRumMonitor !is DatadogRumMonitor ||
            scrollTarget == null
        ) {
            return
        }

        val targetId: String = resolveTargetIdOrResourceName(scrollTarget)
        val attributes = resolveAttributes(scrollTarget, targetId, onUpEvent)
        registeredRumMonitor.stopUserAction(
            scrollEventType,
            attributes
        )
    }

    private fun resolveAttributes(
        scrollTarget: View,
        targetId: String,
        onUpEvent: MotionEvent
    ): MutableMap<String, Any?> {
        val attributes = mutableMapOf<String, Any?>(
            RumAttributes.TAG_TARGET_CLASS_NAME to scrollTarget.javaClass.canonicalName,
            RumAttributes.TAG_TARGET_RESOURCE_ID to targetId
        )
        val downEvent = currentDownEvent
        downEvent?.let {
            gestureDirection = resolveGestureDirection(it, onUpEvent)
            attributes.put(RumAttributes.TAG_GESTURE_DIRECTION, gestureDirection)
        }

        attributesProviders.forEach {
            it.extractAttributes(scrollTarget, attributes)
        }
        return attributes
    }

    private fun resetScrollEventParameters() {
        scrollTargetReference.clear()
        scrollEventType = NO_EVENT
        gestureDirection = ""
        currentDownEvent = null
    }

    private fun handleTapUp(decorView: View?, e: MotionEvent) {
        if (decorView != null) {
            findTargetForTap(decorView, e.x, e.y)?.let { target ->
                val targetId: String = resolveTargetIdOrResourceName(target)
                val attributes = mutableMapOf<String, Any?>(
                    RumAttributes.TAG_TARGET_CLASS_NAME to target.javaClass.canonicalName,
                    RumAttributes.TAG_TARGET_RESOURCE_ID to targetId
                )
                attributesProviders.forEach {
                    it.extractAttributes(target, attributes)
                }
                GlobalRum.get().addUserAction(
                    TAP_EVENT,
                    attributes
                )
            }
        }
    }

    private fun resolveTargetIdOrResourceName(target: View): String {
        @Suppress("SwallowedException")
        return try {
            target.resources.getResourceEntryName(target.id) ?: targetIdAsHexa(target)
        } catch (e: Resources.NotFoundException) {
            targetIdAsHexa(target)
        }
    }

    private fun targetIdAsHexa(target: View) = "0x${target.id.toString(16)}"

    private fun findTargetForTap(decorView: View, x: Float, y: Float): View? {
        val stack = LinkedList<View>()
        stack.addFirst(decorView)

        while (stack.isNotEmpty()) {
            val view = stack.removeFirst()

            if (isValidTapTarget(view)) {
                return view
            }

            if (view is ViewGroup) {
                handleViewGroup(view, x, y, stack, coordinatesContainer)
            }
        }

        devLogger.i(MSG_NO_TARGET)
        return null
    }

    private fun findTargetForScroll(decorView: View, x: Float, y: Float): View? {
        val stack = LinkedList<View>()
        stack.addFirst(decorView)

        while (stack.isNotEmpty()) {
            val view = stack.removeFirst()

            if (isValidScrollableTarget(view)) {
                return view
            }

            if (view is ViewGroup) {
                handleViewGroup(view, x, y, stack, coordinatesContainer)
            }
        }
        devLogger.i(
            "We could not find a valid target scrollable for " +
                    "the $SCROLL_EVENT or $SWIPE_EVENT event."
        )
        return null
    }

    private fun handleViewGroup(
        view: ViewGroup,
        x: Float,
        y: Float,
        stack: LinkedList<View>,
        coordinatesContainer: IntArray
    ) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (hitTest(child, x, y, coordinatesContainer)) {
                stack.add(child)
            }
        }
    }

    private fun isValidTapTarget(view: View): Boolean {
        return view.isClickable && view.visibility == View.VISIBLE
    }

    private fun isValidScrollableTarget(view: View): Boolean {
        return view.visibility == View.VISIBLE && isScrollableView(view)
    }

    private fun isScrollableView(view: View): Boolean {
        return ScrollingView::class.java.isAssignableFrom(view.javaClass) ||
                AbsListView::class.java.isAssignableFrom(view.javaClass)
    }

    private fun hitTest(
        view: View,
        x: Float,
        y: Float,
        container: IntArray
    ): Boolean {
        view.getLocationOnScreen(container)
        val vx = container[0]
        val vy = container[1]
        val w = view.width
        val h = view.height

        return !(x < vx || x > vx + w || y < vy || y > vy + h)
    }

    private fun resolveGestureDirection(startEvent: MotionEvent, endEvent: MotionEvent): String {
        val diffX = endEvent.x - startEvent.x
        val diffY = endEvent.y - startEvent.y
        return if (abs(diffX) > abs(diffY)) {
            if (diffX > 0) {
                SCROLL_DIRECTION_LEFT
            } else {
                SCROLL_DIRECTION_RIGHT
            }
        } else {
            if (diffY > 0) {
                SCROLL_DIRECTION_DOWN
            } else {
                SCROLL_DIRECTION_UP
            }
        }
    }

    // endregion

    companion object {

        internal const val SCROLL_DIRECTION_LEFT = "left"
        internal const val SCROLL_DIRECTION_RIGHT = "right"
        internal const val SCROLL_DIRECTION_UP = "up"
        internal const val SCROLL_DIRECTION_DOWN = "down"

        internal const val TAP_EVENT = "TapEvent"
        internal const val SCROLL_EVENT = "ScrollEvent"
        internal const val SWIPE_EVENT = "SwipeEvent"
        private const val NO_EVENT = "NONE"

        internal const val MSG_NO_TARGET = "We could not find a valid target for the $TAP_EVENT. " +
            "The DecorView was empty and either transparent " +
            "or not clickable for this Activity."
    }
}