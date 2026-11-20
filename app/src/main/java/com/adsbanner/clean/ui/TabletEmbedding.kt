package com.adsbanner.clean.ui

import android.content.ComponentName
import android.content.Context
import androidx.window.embedding.EmbeddingAspectRatio
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitPairRule
import androidx.window.embedding.SplitRule

object TabletEmbedding {
    private var registered = false

    fun register(context: Context) {
        if (registered || !TabletLayout.isTablet(context)) return

        val main = ComponentName(context, MainActivity::class.java)
        val protection = ComponentName(context, ProtectionActivity::class.java)
        val stats = ComponentName(context, StatsActivity::class.java)
        val targets = ComponentName(context, TargetListActivity::class.java)
        val filters = setOf(
            SplitPairFilter(main, stats, null),
            SplitPairFilter(main, targets, null),
            SplitPairFilter(main, protection, null),
            SplitPairFilter(protection, targets, null)
        )
        val attributes = SplitAttributes.Builder()
            .setSplitType(SplitAttributes.SplitType.ratio(0.38f))
            .setLayoutDirection(SplitAttributes.LayoutDirection.LEFT_TO_RIGHT)
            .build()
        val rule = SplitPairRule.Builder(filters)
            .setMinWidthDp(600)
            .setMinSmallestWidthDp(600)
            .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_DISALLOW)
            .setMaxAspectRatioInLandscape(EmbeddingAspectRatio.ALWAYS_ALLOW)
            .setFinishPrimaryWithSecondary(SplitRule.FinishBehavior.NEVER)
            .setFinishSecondaryWithPrimary(SplitRule.FinishBehavior.ADJACENT)
            .setClearTop(true)
            .setDefaultSplitAttributes(attributes)
            .build()
        RuleController.getInstance(context).addRule(rule)
        registered = true
    }
}
