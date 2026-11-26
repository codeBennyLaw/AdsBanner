package com.adsbanner.clean.service

import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.adsbanner.clean.core.CainiaoSplashPolicy
import com.adsbanner.clean.core.SkipRule
import com.adsbanner.clean.core.SkipTargetPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Read-only evidence from the current window, using the engine's existing bounded tree pass.
 * Page text/description is reduced to a category. No screenshot, clipboard or arbitrary label is saved. */
internal class CainiaoScanTrace(screen: Point, sampledAt: Long, age: Long, activity: String, covered: Boolean, restricted: Boolean) {
    private val width = screen.x
    private val height = screen.y
    private val roots = JSONArray()
    private val targets = ArrayList<JSONObject>(3)
    private val markers = JSONArray()
    private val ids = linkedSetOf<String>()
    private val seenTargets = HashSet<String>()
    private val decisions = linkedSetOf<String>()
    private val errors = linkedSetOf<String>()
    private var visited = 0
    private var unavailable = 0
    private var maxDepth = 0
    private var containers = 0
    private val report = JSONObject().put("screen", "${width}x$height").put("sampledAtMs", sampledAt).put("ageMs", age)
        .put("activity", identifier(activity)).put("covered", covered).put("restricted", restricted)

    fun root(node: AccessibilityNodeInfo) = capture { if (roots.length() < 4) roots.put(metadata(node)) }
    fun missingChild() { unavailable++ }
    fun decision(allowed: Boolean) { decisions.add(if (allowed) "candidate-ad-proof-passed" else "candidate-ad-proof-rejected") }
    fun restrictedDecision(allowed: Boolean) { decisions.add(if (allowed) "restricted-target-passed" else "restricted-target-rejected") }

    fun node(node: AccessibilityNodeInfo, depth: Int, normalized: String = "") {
        capture { inspectNode(node, depth, normalized) }
    }

    private fun inspectNode(node: AccessibilityNodeInfo, depth: Int, normalized: String) {
        visited++
        maxDepth = maxOf(maxDepth, depth)
        val id = identifier(node.viewIdResourceName)
        if (id.isNotEmpty() && ids.size < 12) ids.add(id)
        if (node.viewIdResourceName == CainiaoSplashPolicy.CONTAINER_ID) containers++
        val bounds = Rect().also(node::getBoundsInScreen)
        val category = labelKind(node)
        val upperControl = bounds.width() > 0 && bounds.height() > 0 &&
            bounds.centerX() > width * 0.55f && bounds.centerY() < height * 0.35f &&
            bounds.width() <= width * 0.50f && bounds.height() <= height * 0.25f
        val circleGeometry = CainiaoSplashPolicy.unlabeledBounds(node.className?.toString(), bounds.toBounds(), width, height)
        val targetGeometry = CainiaoSplashPolicy.targetBounds(bounds.toBounds(), width, height)
        val lowerControl = CainiaoSplashPolicy.lowerLeftBounds(bounds.toBounds(), width, height) && node.childCount == 0
        val priority = (if (category == "skip" || kind(normalized) == "skip") 100 else 0) +
            (if (id == CainiaoSplashPolicy.MS_SKIP_ID) 90 else 0) + (if (lowerControl) 25 else 0) + (if (circleGeometry) 30 else 0) +
            (if (targetGeometry) 20 else 0) + (if (node.childCount == 0) 5 else 0) + (if (node.isClickable) 10 else 0)
        val weakest = targets.indices.minByOrNull { targets[it].optInt("priority") }
        if ((upperControl || lowerControl || category == "skip") &&
            (weakest == null || targets.size < 3 || priority > targets[weakest].optInt("priority")) &&
            seenTargets.add("${node.windowId}:$id:${node.className}:$bounds:$category")) {
            val target = metadata(node).put("depth", depth).put("normalized", kind(normalized))
                .put("circleGeometry", circleGeometry).put("targetGeometry", targetGeometry).put("priority", priority)
            val ancestors = JSONArray()
            var current = node.parent
            try {
                repeat(8) { index ->
                    val parent = current ?: return@repeat
                    ancestors.put(metadata(parent))
                    if (index == 0) {
                        val tail = JSONArray()
                        for (childIndex in maxOf(0, parent.childCount - 3) until parent.childCount) {
                            val child = parent.getChild(childIndex)
                            if (child == null) { tail.put(JSONObject().put("index", childIndex).put("unreadable", true)); continue }
                            try { tail.put(metadata(child).put("index", childIndex).put("sameAsTarget", child == node)) }
                            finally { recycle(child) }
                        }
                        target.put("parentTail", tail)
                    }
                    val next = parent.parent
                    recycle(parent)
                    current = next
                }
            } finally { current?.let(::recycle) }
            target.put("ancestors", ancestors)
            if (targets.size < 3) targets.add(target) else targets[weakest!!] = target
        }
        if (markers.length() < 4 && bounds.height() > 0 && bounds.height() <= height * 0.10f && bounds.top > height * 0.5f &&
            (category == "ad" || id == CainiaoSplashPolicy.MS_AD_TAG_ID ||
                (category == "empty" && node.className?.toString() == "android.widget.TextView")))
            markers.put(metadata(node))
    }

    fun finish(): JSONObject = report.put("roots", roots).put("visited", visited).put("missingChildren", unavailable)
        .put("maxDepth", maxDepth).put("exactContainers", containers).put("resourceIds", JSONArray(ids.toList()))
        .put("targets", JSONArray(targets)).put("lowerMarkers", markers).put("decisions", JSONArray(decisions.toList()))
        .put("readErrors", JSONArray(errors.toList()))
        .put("rank", (targets.maxOfOrNull { it.optInt("priority") } ?: 0) * 100 +
            targets.size * 1000 + containers * 100 + minOf(visited, 99))

    private fun capture(read: () -> Unit) {
        try { read() } catch (error: Exception) {
            if (errors.size < 3) errors.add(identifier(error.javaClass.name))
        }
    }

    private fun metadata(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        return JSONObject().put("class", identifier(node.className?.toString())).put("id", identifier(node.viewIdResourceName))
            .put("window", node.windowId).put("pkg", identifier(node.packageName?.toString()))
            .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            .put("children", node.childCount).put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
            .put("click", node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            .put("scroll", node.isScrollable).put("editable", node.isEditable).put("checkable", node.isCheckable)
            .put("label", labelKind(node))
    }

    private fun labelKind(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val kinds = listOf(kind(text), kind(description))
        return when {
            "skip" in kinds -> "skip"
            "ad" in kinds -> "ad"
            kinds.all { it == "empty" } -> "empty"
            else -> "other"
        }
    }

    private fun kind(text: String): String = when {
        text.isBlank() -> "empty"
        SkipRule.match(text) == SkipRule.STRONG -> "skip"
        SkipRule.isStandaloneAdMarker(text) || text.replace(" ", "").equals("AM广告", true) -> "ad"
        else -> "other"
    }

    private fun identifier(value: String?) = value.orEmpty().take(120).replace(Regex("[^a-zA-Z0-9_.$:/-]"), "?")
    private fun Rect.toBounds() = SkipTargetPolicy.Bounds(left, top, right, bottom)
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }
}
