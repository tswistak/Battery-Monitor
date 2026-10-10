/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

import android.os.Bundle

internal class SectionNavigator(initial: SectionOwner = SectionOwner.CURRENT) {
    var selected: SectionOwner = initial
        private set
    var detail: String? = null
        private set
    private val detailOrigins = mutableListOf<Pair<SectionOwner, String?>>()

    private val sectionStates = mutableMapOf<SectionOwner, SectionState>()

    fun state(owner: SectionOwner): SectionState = sectionStates[owner] ?: SectionState()

    fun update(owner: SectionOwner, state: SectionState) {
        sectionStates[owner] = state
    }

    fun select(owner: SectionOwner) {
        if (owner == selected && detail == null) return
        selected = owner
        detail = null
        detailOrigins.clear()
    }

    fun openDetail(owner: SectionOwner, route: String, origin: SectionOwner = selected) {
        detailOrigins.add(origin to if (origin == selected) detail else null)
        selected = owner
        detail = route
    }

    fun back(): Boolean {
        if (detail != null) {
            val origin = detailOrigins.removeLastOrNull() ?: (SectionOwner.CURRENT to null)
            selected = origin.first
            detail = origin.second
            return true
        }
        if (selected == SectionOwner.CURRENT) return false
        selected = SectionOwner.CURRENT
        return true
    }

    fun save(out: Bundle) {
        out.putString("nav_selected", selected.route)
        out.putString("nav_detail", detail)
        out.putStringArrayList("nav_detail_owners", ArrayList(detailOrigins.map { it.first.route }))
        out.putStringArrayList(
            "nav_detail_routes", ArrayList(detailOrigins.map { it.second ?: "" })
        )
        for ((owner, state) in sectionStates) {
            val key = "nav_${owner.route}_"
            out.putBoolean("${key}saved", true)
            out.putString("${key}tab", state.selectedTab)
            state.rangeStartMillis?.let { out.putLong("${key}start", it) }
            state.rangeEndMillis?.let { out.putLong("${key}end", it) }
            out.putStringArrayList("${key}filters", ArrayList(state.filters))
            out.putString("${key}item", state.selectedItemId)
            out.putInt("${key}scroll_index", state.scrollIndex)
            out.putInt("${key}scroll_offset", state.scrollOffset)
        }
    }

    companion object {
        fun restore(saved: Bundle?): SectionNavigator {
            val navigator =
                SectionNavigator(SectionRegistry.owner(saved?.getString("nav_selected")))
            if (saved == null) return navigator
            navigator.detail = saved.getString("nav_detail")
            val owners = saved.getStringArrayList("nav_detail_owners")
            val details = saved.getStringArrayList("nav_detail_routes")
            if (owners != null && details != null && owners.size == details.size) {
                owners.zip(details).forEach { (owner, route) ->
                    navigator.detailOrigins.add(SectionRegistry.owner(owner) to route.ifEmpty { null })
                }
            } else if (navigator.detail != null) {
                navigator.detailOrigins.add(SectionRegistry.owner(saved.getString("nav_return_to")) to null)
            }
            for (owner in SectionOwner.entries) {
                val key = "nav_${owner.route}_"
                if (!saved.getBoolean("${key}saved")) continue
                navigator.update(
                    owner, SectionState(
                        selectedTab = saved.getString("${key}tab"),
                    rangeStartMillis = saved.takeIf { it.containsKey("${key}start") }
                        ?.getLong("${key}start"),
                    rangeEndMillis = saved.takeIf { it.containsKey("${key}end") }
                        ?.getLong("${key}end"),
                    filters = saved.getStringArrayList("${key}filters")?.toSet() ?: emptySet(),
                    selectedItemId = saved.getString("${key}item"),
                    scrollIndex = saved.getInt("${key}scroll_index"),
                    scrollOffset = saved.getInt("${key}scroll_offset")))
            }
            return navigator
        }
    }
}
