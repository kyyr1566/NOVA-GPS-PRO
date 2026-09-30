package com.nova.gpspro.ui

import android.view.View
import com.nova.gpspro.MainActivity

/** A page only does UI work while it is visible (listeners attached in onShow, removed in onHide). */
abstract class Page(val act: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}
}
