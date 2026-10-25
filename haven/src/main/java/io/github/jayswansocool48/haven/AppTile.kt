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

/** A rounded app card with its name underneath. Banners fill the card; plain icons sit on a pastel tile. */
@SuppressLint("ViewConstructor")
class AppTile(
    ctx: Context,
    val app: AppEntry,
    private val t: HavenTheme,
    cardW: Int,
    cardH: Int,
    private val dimmed: Boolean,
) : LinearLayout(ctx) {

    private val radius = ctx.dp(20f)
    val card = FrameLayout(ctx)
    private val image = ImageView(ctx)
    private val name = ctx.text(app.label, 15f, t.dim)
    private val ring = rounded(0, radius, 0xFFFFFFFF.toInt(), ctx.dpi(4f))
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
        card.elevation = ctx.dp(2f)
        addView(card, LayoutParams(cardW, cardH))

        name.gravity = Gravity.CENTER
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val nlp = LayoutParams(cardW, WRAP)
        nlp.topMargin = ctx.dpi(10f)
        addView(name, nlp)

        if (dimmed) alpha = 0.5f

        setOnFocusChangeListener { _, has ->
            bounce(this, has, 1.12f)
            card.foreground = if (has) ring else null
            name.setTextColor(if (has) t.text else t.dim)
            name.typeface = if (has) Fonts.bold(ctx) else Fonts.medium(ctx)
            if (dimmed) alpha = if (has) 1f else 0.5f
        }
    }

    /** Shows the art once it's been loaded off the main thread. */
    fun setArt(banner: Drawable?, icon: Drawable) {
        card.removeAllViews()
        if (banner != null) {
            image.setImageDrawable(banner)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            card.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            image.setImageDrawable(icon)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val s = context.dpi(56f)
            card.addView(image, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }
    }

    /** The little wobble while a favorite is being moved. */
    fun setMoving(on: Boolean) {
        wiggle?.cancel()
        wiggle = null
        rotation = 0f
        if (on) {
            card.foreground = rounded(0, radius, t.accent, context.dpi(5f))
            wiggle = ObjectAnimator.ofFloat(this, View.ROTATION, -2.5f, 2.5f).apply {
                duration = 140
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (hasFocus()) {
            card.foreground = ring
        }
    }
}
