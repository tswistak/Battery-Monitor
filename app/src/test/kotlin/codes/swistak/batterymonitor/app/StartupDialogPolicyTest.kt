/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDialogPolicyTest {
    @Test
    fun `unsupported Live Updates use legacy notification onboarding`() {
        for (enabled in listOf(false, true)) {
            assertEquals(NotificationOnboarding.LEGACY, notificationOnboarding(false, enabled))
        }
    }

    @Test
    fun `enabled Live Updates use the battery chip explanation`() {
        assertEquals(
            NotificationOnboarding.LIVE_UPDATES_ON, notificationOnboarding(
                supportsLiveUpdates = true, liveUpdatesEnabled = true
            )
        )
    }

    @Test
    fun `disabled Live Updates offer the system settings explanation`() {
        assertEquals(
            NotificationOnboarding.LIVE_UPDATES_OFF, notificationOnboarding(
                supportsLiveUpdates = true, liveUpdatesEnabled = false
            )
        )
    }

    @Test
    fun `a genuine clean installation establishes a baseline without a changelog`() {
        assertEquals(
            ChangelogVersionDecision(20L, false),
            changelogVersionDecision(20L, null, true, 100L, 100L)
        )
    }

    @Test
    fun `a legacy existing installation receives the first in-app changelog`() {
        for (firstRun in listOf(false, true)) {
            assertEquals(
                ChangelogVersionDecision(null, true),
                changelogVersionDecision(20L, null, firstRun, 100L, 200L)
            )
        }
    }

    @Test
    fun `completed legacy onboarding prevents an apparent fresh-install baseline`() {
        assertEquals(
            ChangelogVersionDecision(null, true),
            changelogVersionDecision(20L, null, false, 100L, 100L)
        )
    }

    @Test
    fun `consecutive and skipped upgrades show only the installed release`() {
        for (installed in listOf(21L, 30L)) {
            assertEquals(
                ChangelogVersionDecision(null, true),
                changelogVersionDecision(installed, 20L, false, 100L, 200L)
            )
        }
    }

    @Test
    fun `an acknowledged installed version has no pending changelog`() {
        assertEquals(
            ChangelogVersionDecision(null, false),
            changelogVersionDecision(20L, 20L, false, 100L, 200L)
        )
    }

    @Test
    fun `pending upgrade decisions do not acknowledge an interrupted presentation`() {
        for (acknowledged in listOf(null, 20L)) {
            val decision = changelogVersionDecision(21L, acknowledged, false, 100L, 200L)
            assertTrue(decision.pending)
            assertNull(decision.baselineVersion)
        }
    }

    @Test
    fun `acknowledgement suppresses the release immediately`() {
        val acknowledged = acknowledgeVersion(21L, 20L)
        assertEquals(21L, acknowledged)
        assertFalse(changelogVersionDecision(21L, acknowledged, false, 100L, 200L).pending)
    }

    @Test
    fun `downgrade and re-upgrade to an acknowledged release stay quiet`() {
        for (installed in listOf(19L, 20L)) {
            assertEquals(
                ChangelogVersionDecision(null, false),
                changelogVersionDecision(installed, 20L, false, 100L, 200L)
            )
        }
        assertEquals(20L, acknowledgeVersion(19L, 20L))
        assertTrue(changelogVersionDecision(21L, 20L, false, 100L, 300L).pending)
    }

    @Test
    fun `a fresh baseline is retained while running an older build`() {
        val baseline = changelogVersionDecision(20L, null, true, 100L, 100L).baselineVersion
        assertEquals(20L, baseline)
        assertFalse(changelogVersionDecision(19L, baseline, false, 100L, 200L).pending)
        assertFalse(changelogVersionDecision(20L, baseline, false, 100L, 300L).pending)
    }

    @Test
    fun `version codes support the installed long version code range`() {
        val installed = Int.MAX_VALUE.toLong() + 10L
        assertTrue(changelogVersionDecision(installed, installed - 1L, false, 1L, 2L).pending)
        assertEquals(installed, acknowledgeVersion(installed, null))
    }

    @Test
    fun `a fresh launch needing notification permission starts with onboarding`() {
        assertEquals(
            StartupPresentation.ONBOARDING, nextStartupPresentation(
                StartupProgress(firstRun = true, changelogPending = false),
                environment(notificationsGranted = false)
            )
        )
    }

    @Test
    fun `an upgrade changelog takes priority over notifications and recurring battery warnings`() {
        for (notificationRequired in listOf(false, true)) {
            for (granted in listOf(false, true)) {
                for (optimized in listOf(false, true)) {
                    assertEquals(
                        StartupPresentation.CHANGELOG, nextStartupPresentation(
                            StartupProgress(firstRun = false, changelogPending = true),
                            StartupEnvironment(notificationRequired, granted, optimized)
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `existing users request missing permission after acknowledging the changelog`() {
        assertEquals(
            StartupPresentation.REQUEST_NOTIFICATIONS, nextStartupPresentation(
                StartupProgress(firstRun = false, changelogPending = false),
                environment(notificationsGranted = false)
            )
        )
    }

    @Test
    fun `unsupported notification API and granted permission both skip onboarding`() {
        for (environment in listOf(
            StartupEnvironment(
                notificationPermissionRequired = false,
                notificationsGranted = false,
                batteryOptimized = true
            ), StartupEnvironment(
                notificationPermissionRequired = true,
                notificationsGranted = true,
                batteryOptimized = true
            )
        )) {
            assertEquals(
                StartupPresentation.BATTERY_OPTIMIZATION, nextStartupPresentation(
                    StartupProgress(firstRun = true, changelogPending = false), environment
                )
            )
        }
    }

    @Test
    fun `notification denial does not request twice within the same startup`() {
        for (firstRun in listOf(false, true)) {
            assertEquals(
                StartupPresentation.BATTERY_OPTIMIZATION, nextStartupPresentation(
                    StartupProgress(firstRun, false, notificationAttempted = true),
                    environment(notificationsGranted = false)
                )
            )
        }
    }

    @Test
    fun `unrestricted background execution skips the battery explanation`() {
        assertEquals(
            StartupPresentation.NONE, nextStartupPresentation(
                StartupProgress(firstRun = false, changelogPending = false),
                environment(batteryOptimized = false)
            )
        )
    }

    @Test
    fun `cancelled battery explanation does not appear again within the same startup`() {
        assertEquals(
            StartupPresentation.NONE, nextStartupPresentation(
                StartupProgress(
                    firstRun = false, changelogPending = false, batteryPromptShown = true
                ), environment()
            )
        )
    }

    @Test
    fun `completed startup prompts leave no presentation`() {
        assertEquals(
            StartupPresentation.NONE, nextStartupPresentation(
                StartupProgress(
                    firstRun = false, changelogPending = false, batteryPromptShown = true
                ), environment()
            )
        )
    }

    private fun environment(
        notificationsGranted: Boolean = true, batteryOptimized: Boolean = true
    ) = StartupEnvironment(
        notificationPermissionRequired = true,
        notificationsGranted = notificationsGranted,
        batteryOptimized = batteryOptimized
    )
}
