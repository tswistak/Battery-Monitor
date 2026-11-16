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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PendingDrawerNavigation(
    private val scope: CoroutineScope,
    private val closeDrawer: suspend () -> Unit,
    private val onSelect: (SectionOwner) -> Unit
) {
    private var pending: Job? = null

    fun select(owner: SectionOwner) {
        cancel()
        pending = scope.launch {
            closeDrawer()
        }
        onSelect(owner)
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
