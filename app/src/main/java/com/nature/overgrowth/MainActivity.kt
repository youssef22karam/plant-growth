package com.nature.overgrowth

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class MainActivity : AppCompatActivity() {

    private lateinit var natureCanvas: NatureCanvasView
    private lateinit var scrollView: ScrollView
    private lateinit var btnGrow: Button
    private lateinit var btnClear: Button
    private lateinit var leafCount: TextView
    private lateinit var flowerCount: TextView
    private lateinit var branchCount: TextView

    // All card/box IDs we want vines to grow around
    private val boxIds = listOf(
        R.id.box1, R.id.box2, R.id.box3, R.id.box4, R.id.box5
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        natureCanvas  = findViewById(R.id.natureCanvas)
        scrollView    = findViewById(R.id.scrollView)
        btnGrow       = findViewById(R.id.btnGrow)
        btnClear      = findViewById(R.id.btnClear)
        leafCount     = findViewById(R.id.leafCount)
        flowerCount   = findViewById(R.id.flowerCount)
        branchCount   = findViewById(R.id.branchCount)

        // Live counter updates
        natureCanvas.onCountsChanged = { l, f, b ->
            runOnUiThread {
                leafCount.text   = l.toString()
                flowerCount.text = f.toString()
                branchCount.text = b.toString()
            }
        }

        // Feed rects once layout is ready, and again on every scroll
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    feedRects()
                }
            }
        )

        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> feedRects() }

        btnGrow.setOnClickListener {
            feedRects()
            natureCanvas.grow()
            animateButtonPress(btnGrow)
        }

        btnClear.setOnClickListener {
            natureCanvas.clearGrowth()
            animateButtonPress(btnClear)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        natureCanvas.stopLoop()
    }

    // ── Feed scrolled rects to the canvas ────────────────────────────────────

    private fun feedRects() {
        val scrollY   = scrollView.scrollY.toFloat()
        val canvasLoc = IntArray(2)
        natureCanvas.getLocationOnScreen(canvasLoc)

        val outerSpread = 100f
        val textPad     = 10f

        val boxes   = mutableListOf<BoxRect>()
        val avoids  = mutableListOf<Rect4>()
        val texts   = mutableListOf<Rect4>()

        for (id in boxIds) {
            val v = findViewById<View>(id) ?: continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)

            val left   = (loc[0] - canvasLoc[0]).toFloat()
            val top    = (loc[1] - canvasLoc[1]).toFloat()
            val right  = left + v.width
            val bottom = top  + v.height

            boxes.add(
                BoxRect(
                    left   = left   - outerSpread,
                    top    = top    - outerSpread,
                    right  = right  + outerSpread,
                    bottom = bottom + outerSpread,
                    spawnLeft   = left   - outerSpread,
                    spawnTop    = top    - outerSpread,
                    spawnRight  = right  + outerSpread,
                    spawnBottom = bottom + outerSpread
                )
            )

            avoids.add(Rect4(left, top, right, bottom))
            texts.add(Rect4(
                left   + textPad,
                top    + textPad,
                right  - textPad,
                bottom - textPad
            ))
        }

        natureCanvas.boxRects   = boxes
        natureCanvas.avoidRects = avoids
        natureCanvas.textRects  = texts
    }

    // ── Subtle button press animation ────────────────────────────────────────

    private fun animateButtonPress(btn: Button) {
        ValueAnimator.ofFloat(1f, 0.93f, 1f).apply {
            duration = 180
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                btn.scaleX = s
                btn.scaleY = s
            }
            start()
        }
    }
}
