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

    fun select(owner: SectionOwner) {
        if (owner == selected && detail == null) return
        selected = owner
        detail = null
        detailOrigins.clear()
    }

    fun openDetail(owner: SectionOwner, route: String) {
        detailOrigins.add(selected to detail)
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
            }
            return navigator
        }
    }
}
