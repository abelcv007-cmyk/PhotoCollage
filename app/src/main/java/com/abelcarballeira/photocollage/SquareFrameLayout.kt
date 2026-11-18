package com.abelcarballeira.photocollage

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout cuadrado: su alto siempre es igual a su ancho. Se usa como celda de la cuadrícula. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
