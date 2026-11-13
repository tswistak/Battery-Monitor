/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WidgetSizesTest {
    @Test
    fun reportedSizesDrivePreviewAndRenderingWithBoundedVariants() {
        val options = legacyOptions().apply {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(
                    SizeF(280f, 170f),
                    SizeF(220.5f, 200.5f),
                    SizeF(300f, 150f),
                    SizeF(240f, 180f),
                    SizeF(260f, 190f),
                    SizeF(0f, 200f)
                )
            )
        }
        val portrait =
            WidgetSizes.preview(WidgetFamily.TREND, options, Configuration.ORIENTATION_PORTRAIT)
        val landscape =
            WidgetSizes.preview(WidgetFamily.TREND, options, Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(WidgetSize(220, 200), portrait)
        assertEquals(WidgetSize(300, 150), landscape)
        assertEquals(
            setOf(WidgetSize(220, 150), portrait, landscape, WidgetSize(260, 190)),
            renderedSizes(options).toSet()
        )
    }

    @Test
    fun missingOrUnusableHostSizesFallBackToProviderDefault() {
        val options = Bundle().apply {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(0f, 0f))
            )
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)
        }
        val preview =
            WidgetSizes.preview(WidgetFamily.TREND, options, Configuration.ORIENTATION_PORTRAIT)
        assertEquals(WidgetSizes.default(WidgetFamily.TREND), preview)
        assertEquals(listOf(preview), renderedSizes(options))
    }

    @Test
    @Config(sdk = [26])
    fun legacyBoundsRemainPortraitAndLandscapeEstimates() {
        val options = legacyOptions().apply {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(400f, 400f))
            )
        }
        val portrait =
            WidgetSizes.preview(WidgetFamily.TREND, options, Configuration.ORIENTATION_PORTRAIT)
        val landscape =
            WidgetSizes.preview(WidgetFamily.TREND, options, Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(WidgetSize(150, 210), portrait)
        assertEquals(WidgetSize(280, 120), landscape)
        assertEquals(listOf(portrait, landscape), renderedSizes(options))
    }

    private fun legacyOptions() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 210)
    }

    private fun renderedSizes(options: Bundle): List<WidgetSize> = buildList {
        WidgetSizes.remoteViews(WidgetFamily.TREND, options) {
            add(it)
            RemoteViews("android", android.R.layout.simple_list_item_1)
        }
    }
}
