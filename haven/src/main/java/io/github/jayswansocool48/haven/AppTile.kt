package io.github.jayswansocool48.haven

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A rounded app card. TV banners fill the card; other apps get their icon and name on a soft
 * pastel tile. The name under the card only shows on the selected app, which keeps the grid calm.
 */
@SuppressLint("ViewConstructor")
class AppTile(
    ctx: Context,
    val app: AppEntry,
    private val t: HavenTheme,
    cardW: Int,
    private val cardH: Int,
    private val dimmed: Boolean,
) : LinearLayout(ctx) {

    private val radius = ctx.dp(18f)
    val card = FrameLayout(ctx)
    private val name = ctx.text(app.label, 13f, t.text, bold = true)
    private val ring = rounded(0, radius, 0xFFFFFFFF.toInt(), ctx.dpi(3f))
    private val moveRing = rounded(0, radius, t.accent, ctx.dpi(4f))
    /** Drawn on top of the art: the white focus ring, or the accent ring while moving. */
    private val overlay = View(ctx)
    private var art: View? = null
    private var wiggle: ObjectAnimator? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true

        val tileColor = t.tiles[((app.pkg.hashCode() % t.tiles.size) + t.tiles.size) % t.tiles.size]
        card.background = rounded(tileColor, radius)
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        card.clipToOutline = true
        overlay.visibility = View.INVISIBLE
        card.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(card, LayoutParams(cardW, cardH))

        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.alpha = 0f
        val nlp = LayoutParams(cardW + ctx.dpi(30f), WRAP)
        nlp.topMargin = ctx.dpi(10f)
        addView(name, nlp)

        if (dimmed) alpha = 0.45f

        setOnFocusChangeListener { _, has ->
            bounce(this, has, 1.1f)
            if (wiggle == null) showRing(if (has) ring else null)
            name.animate().alpha(if (has) 1f else 0f).setDuration(if (has) 200 else 120).start()
            if (dimmed) alpha = if (has) 1f else 0.45f
        }
    }

    /** Shows the art once it's been loaded off the main thread. */
    fun setArt(banner: Drawable?, icon: Drawable) {
        art?.let { card.removeView(it) }
        val view: View = if (banner != null) {
            ImageView(context).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
        } else {
            // Icon with the app's name under it, centered on the tile.
            context.vbox().apply {
                gravity = Gravity.CENTER
                val s = Math.min(context.dpi(40f), (cardH * 0.44f).toInt())
                addView(ImageView(context).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(s, s))
                val label: TextView = context.text(app.label, 10f, t.text).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    alpha = 0.85f
                }
                val p = context.dpi(10f)
                addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = context.dpi(4f)
                    leftMargin = p
                    rightMargin = p
                })
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
        }
        art = view
        card.addView(view, 0)
    }

    /** The little wobble while a favorite is being moved. */
    fun setMoving(on: Boolean) {
        wiggle?.cancel()
        wiggle = null
        rotation = 0f
        if (on) {
            showRing(moveRing)
            wiggle = ObjectAnimator.ofFloat(this, View.ROTATION, -2f, 2f).apply {
                duration = 150
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            showRing(if (hasFocus()) ring else null)
        }
    }

    private fun showRing(d: Drawable?) {
        overlay.background = d
        overlay.visibility = if (d == null) View.INVISIBLE else View.VISIBLE
    }
}
