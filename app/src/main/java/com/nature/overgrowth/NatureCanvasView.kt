package com.nature.overgrowth

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

// ─── Data classes ─────────────────────────────────────────────────────────────

data class Rect4(
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

data class BoxRect(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val spawnLeft: Float, val spawnTop: Float,
    val spawnRight: Float, val spawnBottom: Float
)

data class BranchPoint(
    val x: Float, val y: Float, val angle: Float,
    var currentThickness: Float, val targetThickness: Float
)

// ─── NatureCanvasView ─────────────────────────────────────────────────────────

class NatureCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Config ──────────────────────────────────────────────────────────────
    private val outerSpread = 100f
    private val textPadding = 10f
    private val gridSize = 12
    private val globalSpeed = 0.25f
    private val gridKeyStride = 10000

    // ── State ───────────────────────────────────────────────────────────────
    private val branches = mutableListOf<Branch>()
    private val leaves   = mutableListOf<Leaf>()
    private val flowers  = mutableListOf<Flower>()

    private val activeBranches = mutableListOf<Branch>()
    private val activeLeaves   = mutableListOf<Leaf>()
    private val activeFlowers  = mutableListOf<Flower>()

    private var energyBudget   = 0f
    private val spatialGrid    = mutableSetOf<Long>()

    // ── Rects populated from outside ────────────────────────────────────────
    var boxRects   = listOf<BoxRect>()
    var avoidRects = listOf<Rect4>()
    var textRects  = listOf<Rect4>()

    private var isFrozen = true
    private var timeAccumulator = 0f

    // Counters exposed to MainActivity
    var onCountsChanged: ((leaves: Int, flowers: Int, branches: Int) -> Unit)? = null

    // ── Paint objects ────────────────────────────────────────────────────────
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4a5c3c")
        style = Paint.Style.FILL
    }

    // ── Animation loop via Choreographer ─────────────────────────────────────
    private var loopRunning = false
    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!loopRunning) return
            tick()
            invalidate()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (!loopRunning) {
            loopRunning = true
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stopLoop() {
        loopRunning = false
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun grow() {
        isFrozen = false
        autoGrow()
        postDelayed({ isFrozen = true }, 100)
        startLoop()
    }

    fun clearGrowth() {
        branches.clear(); activeBranches.clear()
        leaves.clear();   activeLeaves.clear()
        flowers.clear();  activeFlowers.clear()
        energyBudget = 0f
        timeAccumulator = 0f
        spatialGrid.clear()
        isFrozen = true
        stopLoop()
        invalidate()
        onCountsChanged?.invoke(0, 0, 0)
    }

    // ── Core tick ────────────────────────────────────────────────────────────

    private fun tick() {
        if (!isFrozen) {
            timeAccumulator += globalSpeed
            while (timeAccumulator >= 1f) {
                updateAll()
                timeAccumulator -= 1f
            }
        }
        onCountsChanged?.invoke(leaves.size, flowers.size, branches.size)

        val stillAnimating = activeBranches.isNotEmpty() ||
                activeLeaves.isNotEmpty() || activeFlowers.isNotEmpty()
        if (!stillAnimating && isFrozen) stopLoop()
    }

    private fun updateAll() {
        val bIter = activeBranches.iterator()
        while (bIter.hasNext()) {
            val b = bIter.next()
            b.update()
            if (!b.active && b.thicknessAnimating == 0) bIter.remove()
        }
        val lIter = activeLeaves.iterator()
        while (lIter.hasNext()) {
            val l = lIter.next()
            l.update()
            if (l.scale >= l.maxScale) lIter.remove()
        }
        val fIter = activeFlowers.iterator()
        while (fIter.hasNext()) {
            val f = fIter.next()
            f.update()
            if (f.scale >= f.maxScale) fIter.remove()
        }
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in branches) b.draw(canvas, branchPaint)
        for (l in leaves)   l.draw(canvas)
        for (f in flowers)  f.draw(canvas)
    }

    // ── Growth logic ─────────────────────────────────────────────────────────

    private fun autoGrow() {
        if (branches.size > 600) return
        energyBudget += 6000f
        if (activeBranches.size < 20) {
            spawnNewRoots()
        } else {
            for (b in branches) {
                if (!b.active && Random.nextFloat() < 0.35f && b.baseThickness > 0.8f) {
                    b.active = true
                    b.maxDistance += 100f
                    activeBranches.add(b)
                }
            }
        }
    }

    private fun spawnNewRoots() {
        for (box in boxRects) {
            val count = 4 + Random.nextInt(3)
            repeat(count) {
                val edge = Random.nextInt(4)
                val x: Float; val y: Float; var angle: Float
                val halfPi = (PI / 2).toFloat()
                when (edge) {
                    0 -> { x = box.spawnLeft + Random.nextFloat() * (box.spawnRight - box.spawnLeft); y = box.spawnTop;    angle = halfPi  }
                    1 -> { x = box.spawnRight;  y = box.spawnTop + Random.nextFloat() * (box.spawnBottom - box.spawnTop); angle = PI.toFloat() }
                    2 -> { x = box.spawnLeft + Random.nextFloat() * (box.spawnRight - box.spawnLeft); y = box.spawnBottom; angle = -halfPi }
                    else -> { x = box.spawnLeft; y = box.spawnTop + Random.nextFloat() * (box.spawnBottom - box.spawnTop); angle = 0f }
                }
                var finalAngle = if (Random.nextFloat() > 0.75f) angle + PI.toFloat() else angle
                finalAngle += (Random.nextFloat() - 0.5f) * 1.5f
                val b = Branch(x, y, finalAngle, 4f + Random.nextFloat() * 4f, 0, box)
                branches.add(b); activeBranches.add(b)
            }
        }
    }

    // ── Spatial helpers ───────────────────────────────────────────────────────

    fun isPositionValid(x: Float, y: Float, parentBox: BoxRect): Boolean {
        if (x < parentBox.left || x > parentBox.right || y < parentBox.top || y > parentBox.bottom) return false
        for (r in avoidRects) {
            if (x > r.left && x < r.right && y > r.top && y < r.bottom) return false
        }
        return true
    }

    fun getMaxAllowedScale(
        x: Float, y: Float, baseMax: Float, unitRadius: Float, overlapAllowance: Float = 12f
    ): Float {
        var allowed = baseMax
        for (rect in textRects) {
            val cx = x.coerceIn(rect.left, rect.right)
            val cy = y.coerceIn(rect.top, rect.bottom)
            val dist = sqrt((x - cx).pow(2) + (y - cy).pow(2))
            val cap = (dist + overlapAllowance) / unitRadius
            if (cap < allowed) allowed = cap
        }
        return allowed
    }

    fun spawnLeafOrFlower(x: Float, y: Float, angle: Float) {
        val gx = (x / gridSize).toInt()
        val gy = (y / gridSize).toInt()
        val key = (gx + 2000).toLong() * gridKeyStride + (gy + 2000)
        if (!spatialGrid.contains(key)) {
            spatialGrid.add(key)
            val halfPi = (PI / 2).toFloat()
            val sideAngle = angle + (if (Random.nextFloat() > 0.5f) 1 else -1) * (halfPi + (Random.nextFloat() - 0.5f))
            if (Random.nextFloat() < 0.06f) {
                val f = Flower(x, y, sideAngle, this)
                if (f.maxScale > 0.1f) { flowers.add(f); activeFlowers.add(f) }
            } else {
                val l = Leaf(x, y, sideAngle, this)
                if (l.maxScale > 0.1f) { leaves.add(l); activeLeaves.add(l) }
            }
        }
    }

    fun spawnBranch(x: Float, y: Float, angle: Float, thickness: Float, gen: Int, box: BoxRect) {
        val b = Branch(x, y, angle, thickness, gen, box)
        branches.add(b); activeBranches.add(b)
    }

    fun consumeEnergy(): Boolean {
        if (energyBudget <= 0) return false
        energyBudget--
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes: Branch, Leaf, Flower
    // ─────────────────────────────────────────────────────────────────────────

    inner class Branch(
        startX: Float, startY: Float, startAngle: Float,
        val baseThickness: Float, private val generation: Int,
        private val parentBox: BoxRect
    ) {
        var active = true
        var thicknessAnimating = 1
        var maxDistance = 200f + Random.nextFloat() * 350f

        private var x = startX
        private var y = startY
        private var angle = startAngle
        private val speed = 1.0f + Random.nextFloat() * 1.5f
        private var curlDirection = if (Random.nextFloat() > 0.5f) 1f else -1f
        private var turnBias = 0f
        private var distanceCovered = 0f

        val points = mutableListOf(
            BranchPoint(startX, startY, startAngle, 0.1f, baseThickness)
        )

        private val halfPi = (PI / 2).toFloat()
        private val twoPi  = (PI * 2).toFloat()

        fun update() {
            // Animate thickness
            if (thicknessAnimating > 0) {
                val start = points.size - thicknessAnimating
                var still = 0
                for (i in start until points.size) {
                    val p = points[i]
                    if (p.currentThickness < p.targetThickness) {
                        p.currentThickness = min(p.currentThickness + 0.025f, p.targetThickness)
                        still++
                    }
                }
                thicknessAnimating = still
            }

            if (!active || !consumeEnergy()) return

            val lookAhead = 15f
            val turnSpeed = 0.35f
            val lx = x + cos(angle) * lookAhead
            val ly = y + sin(angle) * lookAhead

            if (!isPositionValid(lx, ly, parentBox)) {
                val la = angle - 0.7f
                val ra = angle + 0.7f
                val leftClear  = isPositionValid(x + cos(la) * lookAhead, y + sin(la) * lookAhead, parentBox)
                val rightClear = isPositionValid(x + cos(ra) * lookAhead, y + sin(ra) * lookAhead, parentBox)
                when {
                    leftClear && !rightClear  -> curlDirection = -1f
                    rightClear && !leftClear  -> curlDirection =  1f
                }
                angle    += turnSpeed * curlDirection
                turnBias  = curlDirection * 0.15f
            } else {
                if (Random.nextFloat() < 0.05f) turnBias = (Random.nextFloat() - 0.5f) * 0.8f
                turnBias *= 0.85f
                angle    += turnBias + (Random.nextFloat() - 0.5f) * 0.18f
            }

            x += cos(angle) * speed
            y += sin(angle) * speed
            distanceCovered += speed

            val progression = distanceCovered / maxDistance
            val newThick = max(0.3f, baseThickness * (1f - progression * 0.85f))
            points.add(BranchPoint(x, y, angle, 0.1f, newThick))
            thicknessAnimating++

            if (Random.nextFloat() < 0.04f) {
                spawnLeafOrFlower(x, y, angle)
            }

            if (distanceCovered > maxDistance) {
                active = false
                if (newThick > 0.8f && generation < 5) {
                    val forks = if (Random.nextFloat() > 0.3f) 2 else 1
                    repeat(forks) {
                        val forkAngle = angle + (Random.nextFloat() - 0.5f) * 1.5f
                        spawnBranch(x, y, forkAngle, newThick * 0.9f, generation + 1, parentBox)
                    }
                }
            }
        }

        fun draw(canvas: Canvas, paint: Paint) {
            val pts = points
            val n = pts.size
            if (n < 2) return

            val path = Path()

            if (n == 2) {
                val p0 = pts[0]; val p1 = pts[1]
                val a0 = p0.angle + halfPi; val r0 = p0.currentThickness * 0.5f
                val a1 = p1.angle + halfPi; val r1 = p1.currentThickness * 0.5f
                path.moveTo(p0.x + cos(a0) * r0, p0.y + sin(a0) * r0)
                path.lineTo(p1.x + cos(a1) * r1, p1.y + sin(a1) * r1)
                path.lineTo(p1.x - cos(a1) * r1, p1.y - sin(a1) * r1)
                path.lineTo(p0.x - cos(a0) * r0, p0.y - sin(a0) * r0)
            } else {
                // Right edge forward (bezier through midpoints)
                var p = pts[0]
                var a = p.angle + halfPi
                var r = p.currentThickness * 0.5f
                var prevRx = p.x + cos(a) * r
                var prevRy = p.y + sin(a) * r

                p = pts[1]; a = p.angle + halfPi; r = p.currentThickness * 0.5f
                var curRx = p.x + cos(a) * r
                var curRy = p.y + sin(a) * r
                path.moveTo((prevRx + curRx) * 0.5f, (prevRy + curRy) * 0.5f)
                prevRx = curRx; prevRy = curRy

                for (i in 2 until n) {
                    p = pts[i]; a = p.angle + halfPi; r = p.currentThickness * 0.5f
                    curRx = p.x + cos(a) * r; curRy = p.y + sin(a) * r
                    path.quadTo(prevRx, prevRy, (prevRx + curRx) * 0.5f, (prevRy + curRy) * 0.5f)
                    prevRx = curRx; prevRy = curRy
                }
                path.lineTo(prevRx, prevRy) // tip right

                // Left edge backward
                p = pts[n - 1]; a = p.angle + halfPi; r = p.currentThickness * 0.5f
                var prevLx = p.x - cos(a) * r
                var prevLy = p.y - sin(a) * r
                path.lineTo(prevLx, prevLy)

                for (i in n - 2 downTo 1) {
                    p = pts[i]; a = p.angle + halfPi; r = p.currentThickness * 0.5f
                    val curLx = p.x - cos(a) * r; val curLy = p.y - sin(a) * r
                    path.quadTo(prevLx, prevLy, (prevLx + curLx) * 0.5f, (prevLy + curLy) * 0.5f)
                    prevLx = curLx; prevLy = curLy
                }
                path.lineTo(prevLx, prevLy)
            }

            path.close()
            canvas.drawPath(path, paint)

            // Rounded caps
            val first = pts[0]; val last = pts[n - 1]
            if (first.currentThickness > 0)
                canvas.drawCircle(first.x, first.y, first.currentThickness * 0.5f, paint)
            if (last.currentThickness > 0)
                canvas.drawCircle(last.x, last.y, last.currentThickness * 0.5f, paint)
        }
    }

    // ─── Leaf ─────────────────────────────────────────────────────────────────

    inner class Leaf(
        private val x: Float, private val y: Float, private val angle: Float,
        view: NatureCanvasView
    ) {
        var scale = 0f
        val maxScale: Float
        private val color: Int
        private val veinColor: Int
        private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeCap = Paint.Cap.ROUND
        }

        init {
            val desired = if (Random.nextFloat() < 0.25f)
                1.8f + Random.nextFloat() * 1.2f
            else
                0.6f + Random.nextFloat() * 0.9f
            maxScale = view.getMaxAllowedScale(x, y, desired, 36f)
            val hue = 85 + (Random.nextFloat() * 30).toInt()
            val sat = 50 + (Random.nextFloat() * 25).toInt()
            val lit = 35 + (Random.nextFloat() * 15).toInt()
            color = hslToColor(hue, sat / 100f, lit / 100f)
            veinColor = Color.argb(30, 0, 0, 0)
        }

        fun update() {
            scale = min(scale + 0.05f, maxScale)
        }

        fun draw(canvas: Canvas) {
            if (scale <= 0f) return
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
            canvas.scale(scale, scale)

            val path = Path()
            path.moveTo(0f, 0f)
            path.quadTo(15f, -19f, 35f, 0f)
            path.quadTo(15f,  19f,  0f, 0f)
            leafPaint.color = color
            canvas.drawPath(path, leafPaint)

            val vPath = Path()
            vPath.moveTo(0f, 0f)
            vPath.quadTo(18f, -3f, 30f, 0f)
            veinPaint.color = veinColor
            canvas.drawPath(vPath, veinPaint)

            canvas.restore()
        }
    }

    // ─── Flower ──────────────────────────────────────────────────────────────

    private val flowerHues = intArrayOf(330, 280, 0, 45)

    inner class Flower(
        private val x: Float, private val y: Float, private val angle: Float,
        view: NatureCanvasView
    ) {
        var scale = 0f
        val maxScale: Float
        private val color: Int
        private val petals: Int
        private val angleStep: Float
        private val petalPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#FFCC00")
        }

        init {
            val desired = 0.9f + Random.nextFloat() * 1.0f
            maxScale = view.getMaxAllowedScale(x, y, desired, 20f, 0f)
            val hue = flowerHues[Random.nextInt(4)]
            val sat = if (hue == 0) 0 else 70 + Random.nextInt(30)
            val lit = if (hue == 0) 95 else 60 + Random.nextInt(20)
            color = hslToColor(hue, sat / 100f, lit / 100f)
            petals = 4 + Random.nextInt(3)
            angleStep = (2.0 * PI / petals).toFloat()
        }

        fun update() {
            scale = min(scale + 0.04f, maxScale)
        }

        fun draw(canvas: Canvas) {
            if (scale <= 0f) return
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
            canvas.scale(scale, scale)

            petalPaint.color = color
            for (i in 0 until petals) {
                val rot = angleStep * i
                canvas.save()
                canvas.rotate(Math.toDegrees(rot.toDouble()).toFloat())
                val oval = RectF(11f - 14f, -7f, 11f + 14f, 7f)
                canvas.drawOval(oval, petalPaint)
                canvas.restore()
            }
            canvas.drawCircle(0f, 0f, 5.5f, centerPaint)
            canvas.restore()
        }
    }
}

// ─── HSL → ARGB helper ────────────────────────────────────────────────────────

fun hslToColor(hDeg: Int, s: Float, l: Float): Int {
    val h = hDeg / 360f
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h * 6f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when ((h * 6f).toInt()) {
        0    -> Triple(c, x, 0f)
        1    -> Triple(x, c, 0f)
        2    -> Triple(0f, c, x)
        3    -> Triple(0f, x, c)
        4    -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
}
