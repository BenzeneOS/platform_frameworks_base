package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.phone.NowPlayingIndicationView
import javax.inject.Inject

class NowPlayingIndicationSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val activityStarter: ActivityStarter,
    private val indicationController: KeyguardIndicationController,
    private val statusBarStateController: StatusBarStateController,
) : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {
        val view =
            LayoutInflater.from(context)
                .inflate(R.layout.ambient_indication, constraintLayout, false)
        view.id = R.id.ambient_indication_container
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        constraintLayout
            .requireViewById<NowPlayingIndicationView>(R.id.ambient_indication_container)
            .initialize(
                activityStarter,
                indicationController::setAmbientIndicationVisible,
                statusBarStateController,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(
                R.id.ambient_indication_container,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            constrainHeight(
                R.id.ambient_indication_container,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            connect(
                R.id.ambient_indication_container,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
                context.resources.getDimensionPixelSize(R.dimen.ambient_indication_margin_bottom),
            )
            connect(
                R.id.ambient_indication_container,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(
                R.id.ambient_indication_container,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.ambient_indication_container)
    }
}
