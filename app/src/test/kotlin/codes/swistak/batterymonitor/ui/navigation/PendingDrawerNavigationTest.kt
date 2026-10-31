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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.ui.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDrawerNavigationTest {
    @Test
    fun `input dispatches exactly once without waiting for the queued close`() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val navigation = PendingDrawerNavigation(CoroutineScope(coroutineContext), {
            events += "close"
            closed.await()
        }, { events += it.route })
        navigation.select(SectionOwner.HISTORY)
        assertEquals(listOf("history"), events)
        yield()
        assertEquals(listOf("history", "close"), events)
        closed.complete(Unit)
        yield()
        assertEquals(listOf("history", "close"), events)
    }

    @Test
    fun `rapid inputs each dispatch once and leave the newest destination selected`() =
        runBlocking {
            val closed = CompletableDeferred<Unit>()
            var closingStarts = 0
            val selected = mutableListOf<SectionOwner>()
            val navigation = PendingDrawerNavigation(
                CoroutineScope(coroutineContext), {
                    closingStarts++
                    closed.await()
                }, selected::add
            )
            navigation.select(SectionOwner.HISTORY)
            navigation.select(SectionOwner.SETTINGS)
            assertEquals(listOf(SectionOwner.HISTORY, SectionOwner.SETTINGS), selected)
            yield()
            assertEquals(1, closingStarts)
            closed.complete(Unit)
            yield()
            assertEquals(listOf(SectionOwner.HISTORY, SectionOwner.SETTINGS), selected)
            assertEquals(SectionOwner.SETTINGS, selected.last())
        }

    @Test
    fun `cancellation stops only the queued close and cannot retract an input`() = runBlocking {
        var closingStarts = 0
        val selected = mutableListOf<SectionOwner>()
        val navigation = PendingDrawerNavigation(
            CoroutineScope(coroutineContext), { closingStarts++ }, selected::add
        )
        navigation.select(SectionOwner.HISTORY)
        assertEquals(listOf(SectionOwner.HISTORY), selected)
        navigation.cancel()
        yield()
        assertEquals(0, closingStarts)
        assertEquals(listOf(SectionOwner.HISTORY), selected)
    }

    @Test
    fun `reentrant input retains ownership of the newest closing job`() = runBlocking {
        var closingStarts = 0
        val selected = mutableListOf<SectionOwner>()
        lateinit var navigation: PendingDrawerNavigation
        navigation = PendingDrawerNavigation(
            CoroutineScope(coroutineContext), { closingStarts++ }) { owner ->
            selected += owner
            if (owner == SectionOwner.HISTORY) navigation.select(SectionOwner.CURRENT)
        }
        navigation.select(SectionOwner.HISTORY)
        assertEquals(listOf(SectionOwner.HISTORY, SectionOwner.CURRENT), selected)
        navigation.cancel()
        yield()
        assertEquals(0, closingStarts)
        assertEquals(SectionOwner.CURRENT, selected.last())
    }

    @Test
    fun `external navigation after dispatch wins without a stale replay`() = runBlocking {
        var owner = SectionOwner.CURRENT
        val closed = CompletableDeferred<Unit>()
        val selected = mutableListOf<SectionOwner>()
        val navigation = PendingDrawerNavigation(
            CoroutineScope(coroutineContext), { closed.await() }) { destination ->
            selected += destination
            owner = destination
        }
        navigation.select(SectionOwner.HISTORY)
        assertEquals(SectionOwner.HISTORY, owner)
        yield()
        owner = SectionOwner.HELP
        closed.complete(Unit)
        yield()
        assertEquals(listOf(SectionOwner.HISTORY), selected)
        assertEquals(SectionOwner.HELP, owner)
    }

}
