/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.logs

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.CursorWrapper
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.ListFragment
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.BufferedWriter
import java.io.FileWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class LogViewFragment : ListFragment() {
    companion object {
        private var pFrag: PersistentFragment? = null

        private val CSV_ORDER = arrayOf<String?>(
            LogDatabase.KEY_TIME,
            LogDatabase.KEY_STATUS_CODE,
            LogDatabase.KEY_CHARGE,
            LogDatabase.KEY_TEMPERATURE,
            LogDatabase.KEY_VOLTAGE
        )

        private const val KEY_SHOW_SECONDS = "show_seconds"
        private const val KEY_TIME_DELTA = "time_delta"

        private const val CREATE_CSV_FILE = 1

        private fun sanitizeFileNamePart(value: String): String {
            return value.trim().replace("[^\\p{L}\\p{N}._-]+".toRegex(), "-")
                .replace("-+".toRegex(), "-")
        }

    }

    private var logs: LogDatabase? = null
    private var col: Col? = null
    private var completeCursor: Cursor? = null
    private var filteredCursor: Cursor? = null
    private var timeDeltaCursor: Cursor? = null
    private var mInflater: LayoutInflater? = null
    private var mAdapter: LogAdapter? = null
    private var headerText: TextView? = null
    private var convertF = false

    private var reversed = false
    private var noDB = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        mInflater = inflater

        val logsHeader = View.inflate(activity, R.layout.logs_header, null)
        headerText = logsHeader.findViewById<View?>(R.id.header_text) as TextView
        val lv = view!!.findViewById<View?>(android.R.id.list) as ListView
        lv.addHeaderView(logsHeader, null, false)
        lv.isFastScrollEnabled = true
        if (noDB) return view
        setHeaderText()
        setListAdapter(mAdapter)

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        pFrag = PersistentFragment.getInstance(parentFragmentManager)

        logs = LogDatabase(requireActivity().applicationContext)
        completeCursor = logs!!.getAllLogs(false)

        if (completeCursor == null) {
            noDB = true
            return
        }

        timeDeltaCursor = TimeDeltaCursor(completeCursor!!)
        filteredCursor = FilteredCursor(timeDeltaCursor!!)

        mAdapter = LogAdapter(context, filteredCursor!!)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        convertF = pFrag!!.settings.getBoolean(
            SettingsContract.KEY_CONVERT_F,
            pFrag!!.res.getBoolean(R.bool.default_convert_to_fahrenheit)
        )
        col = Col()

        if (!pFrag!!.spMain.getBoolean(
                "log_filters_migrated_to_sp_main", false
            )
        ) migrateFiltersToSpMain()
    }

    private fun migrateFiltersToSpMain() {
        pFrag!!.spMain.edit {
            for (i in DisplayStrings.logFilterPrefKeys.indices) {
                putBoolean(
                    DisplayStrings.logFilterPrefKeys[i],
                    pFrag!!.settings.getBoolean(DisplayStrings.logFilterPrefKeys[i], true)
                )
            }
            putBoolean("log_filters_migrated_to_sp_main", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (completeCursor != null) completeCursor!!.close()

        logs!!.close()
    }

    override fun onResume() {
        super.onResume()

        pFrag!!.setLVF(this)

        convertF = pFrag!!.settings.getBoolean(
            SettingsContract.KEY_CONVERT_F,
            pFrag!!.res.getBoolean(R.bool.default_convert_to_fahrenheit)
        )
    }

    override fun onPause() {
        super.onPause()

        pFrag!!.setLVF(null)
    }

    class ConfirmClearLogsDialogFragment : DialogFragment() {
        @Suppress("DEPRECATION")
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity)
                .setTitle(pFrag!!.res.getString(R.string.confirm_clear_logs)).setPositiveButton(
                    pFrag!!.res.getString(R.string.yes)
                ) { di, _ ->
                    val lvf = targetFragment as LogViewFragment?
                    lvf!!.logs!!.clearAllLogs()
                    lvf.reloadList(false)
                    di.cancel()
                }.setNegativeButton(
                    pFrag!!.res.getString(R.string.cancel)
                ) { di, _ -> di.cancel() }.create()
        }
    }

    class ConfigureLogFilterDialogFragment : DialogFragment() {
        val checkedItems: BooleanArray = BooleanArray(DisplayStrings.logFilterPrefKeys.size)

        @Suppress("DEPRECATION")
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            for (i in checkedItems.indices) {
                checkedItems[i] =
                    pFrag!!.spMain.getBoolean(DisplayStrings.logFilterPrefKeys[i], true)
            }

            return AlertDialog.Builder(activity)
                .setTitle(pFrag!!.res.getString(R.string.configure_log_filter)).setMultiChoiceItems(
                    R.array.log_filters, checkedItems
                ) { _, id, isChecked ->
                    checkedItems[id] = isChecked
                    val lvf = targetFragment as LogViewFragment?
                    lvf!!.setFilters(checkedItems)
                }.setPositiveButton(
                    pFrag!!.res.getString(R.string.okay)
                ) { di, _ -> di.cancel() }.create()
        }
    }

    private fun setFilters(checked_items: BooleanArray) {
        pFrag!!.spMain.edit {
            for (i in checked_items.indices) {
                putBoolean(DisplayStrings.logFilterPrefKeys[i], checked_items[i])
            }
        }

        reloadList(false)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.logs, menu)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        if (filteredCursor == null) {
            menu.findItem(R.id.menu_clear).isEnabled = false
            menu.findItem(R.id.menu_export).isEnabled = false
            menu.findItem(R.id.menu_reverse).isEnabled = false
            return
        }

        when (filteredCursor!!.count) {
            0 -> {
                menu.findItem(R.id.menu_clear).isEnabled = false
                menu.findItem(R.id.menu_export).isEnabled = false
                menu.findItem(R.id.menu_reverse).isEnabled = false
            }

            1 -> {
                menu.findItem(R.id.menu_clear).isEnabled = true
                menu.findItem(R.id.menu_export).isEnabled = true
                menu.findItem(R.id.menu_reverse).isEnabled = false
            }

            else -> {
                menu.findItem(R.id.menu_clear).isEnabled = true
                menu.findItem(R.id.menu_export).isEnabled = true
                menu.findItem(R.id.menu_reverse).isEnabled = true
            }
        }

        if (pFrag!!.spMain.getBoolean(KEY_SHOW_SECONDS, false)) {
            menu.findItem(R.id.menu_show_seconds).isVisible = false
            menu.findItem(R.id.menu_hide_seconds).isVisible = true
        } else {
            menu.findItem(R.id.menu_show_seconds).isVisible = true
            menu.findItem(R.id.menu_hide_seconds).isVisible = false
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val df: DialogFragment?
        val spmEditor: SharedPreferences.Editor

        if (item.itemId == R.id.menu_clear) {
            df = ConfirmClearLogsDialogFragment()
            df.setTargetFragment(this, 0)
            df.show(parentFragmentManager, "TODO: What is this string for?")

            return true
        }

        if (item.itemId == R.id.menu_log_filter) {
            df = ConfigureLogFilterDialogFragment()
            df.setTargetFragment(this, 0)
            df.show(parentFragmentManager, "TODO: What is this string for?2")

            return true
        }

        if (item.itemId == R.id.menu_export) {
            exportCSV()

            return true
        }

        if (item.itemId == R.id.menu_reverse) {
            reversed = !reversed
            reloadList(true)

            return true
        }

        if (item.itemId == R.id.menu_show_seconds) {
            pFrag!!.spMain.edit {
                putBoolean(KEY_SHOW_SECONDS, true)
            }

            reloadList(false)

            return true
        }

        if (item.itemId == R.id.menu_hide_seconds) {
            pFrag!!.spMain.edit {
                putBoolean(KEY_SHOW_SECONDS, false)
            }

            reloadList(false)

            return true
        }

        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    fun reloadList(newQuery: Boolean) {
        if (newQuery) {
            completeCursor!!.close()
            completeCursor = logs!!.getAllLogs(reversed)
            timeDeltaCursor = TimeDeltaCursor(completeCursor!!)
            filteredCursor = FilteredCursor(timeDeltaCursor!!)

            mAdapter!!.changeCursor(filteredCursor)
        } else {
            filteredCursor!!.requery()
            mAdapter!!.notifyDataSetChanged()
        }

        setHeaderText()
    }

    private fun setHeaderText() {
        val count = filteredCursor!!.count

        if (count == 0) headerText!!.text = DisplayStrings.logsEmpty
        else headerText!!.text = DisplayStrings.nLogItems(count)
    }

    fun batteryInfoUpdated() {
        reloadList(false)
    }

    @Suppress("DEPRECATION")
    private fun createNewCSVFile() {
        val ts = SimpleDateFormat(
            "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
        ).format(Date())
        val device: String = sanitizeFileNamePart(Build.MANUFACTURER + "-" + Build.MODEL)
        val csvFileName = "Battery_Monitor-Logs-$device-$ts.csv"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("text/csv")
        intent.putExtra(Intent.EXTRA_TITLE, csvFileName)

        startActivityForResult(intent, CREATE_CSV_FILE)
    }

    private fun exportCSV() {
        val state = Environment.getExternalStorageState()

        if (state != null && state == Environment.MEDIA_MOUNTED_READ_ONLY) {
            Toast.makeText(activity, DisplayStrings.readOnlyStorage, Toast.LENGTH_SHORT).show()
            return
        } else if (state == null || state != Environment.MEDIA_MOUNTED) {
            Toast.makeText(activity, DisplayStrings.inaccessibleWReason + state, Toast.LENGTH_SHORT)
                .show()
            return
        }

        createNewCSVFile()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode != CREATE_CSV_FILE) return

        if (resultData == null) {
            return
        }

        val d = Date()
        val uri = resultData.data

        val csvFields = arrayOf<String?>(
            DisplayStrings.date,
            DisplayStrings.time,
            DisplayStrings.status,
            DisplayStrings.charge,
            DisplayStrings.temperature,
            DisplayStrings.temperatureF,
            DisplayStrings.voltage
        )

        try {
            val pfd = requireActivity().contentResolver.openFileDescriptor(uri!!, "w")
            val fileWriter = FileWriter(pfd!!.fileDescriptor)
            val buf = BufferedWriter(fileWriter)

            var cols = csvFields.size
            var i: Int
            i = 0
            while (i < cols) {
                buf.write(csvFields[i])
                if (i != cols - 1) buf.write(",")
                i++
            }
            buf.write("\r\n")

            var statusCode: Int
            var statusCodes: IntArray?
            var status: Int
            var plugged: Int
            var status_age: Int
            var s: String?

            completeCursor!!.moveToFirst()
            while (!completeCursor!!.isAfterLast) {
                cols = CSV_ORDER.size
                i = 0
                while (i < cols) {
                    if (CSV_ORDER[i] == LogDatabase.KEY_TIME) {
                        d.setTime(completeCursor!!.getLong(mAdapter!!.timeIndex))
                        buf.write(
                            mAdapter!!.dateFormat.format(d) + "," + DisplayStrings.formatTime(
                                requireContext(), d, includeSeconds = true
                            ) + ","
                        )
                    } else if (CSV_ORDER[i] == LogDatabase.KEY_STATUS_CODE) {
                        statusCode = completeCursor!!.getInt(mAdapter!!.statusCodeIndex)
                        statusCodes = LogDatabase.decodeStatus(statusCode)
                        status = statusCodes!![0]
                        plugged = statusCodes[1]
                        status_age = statusCodes[2]

                        s =
                            if (status == LogDatabase.STATUS_BOOT_COMPLETED) DisplayStrings.statusBootCompleted
                            else if (status_age == LogDatabase.STATUS_OLD) DisplayStrings.logStatusesOld[status]
                            else DisplayStrings.logStatuses[status]

                        if (plugged > 0) s += " " + DisplayStrings.pluggeds[plugged]

                        buf.write("$s,")
                    } else if (CSV_ORDER[i] == LogDatabase.KEY_CHARGE) {
                        buf.write(completeCursor!!.getInt(mAdapter!!.chargeIndex).toString() + ",")
                    } else if (CSV_ORDER[i] == LogDatabase.KEY_TEMPERATURE) {
                        val temperature = completeCursor!!.getInt(mAdapter!!.temperatureIndex)
                        buf.write((temperature / 10.0).toString() + ",")
                        buf.write(((temperature * 9 / 5.0).roundToInt() / 10.0 + 32.0).toString() + ",")
                    } else if (CSV_ORDER[i] == LogDatabase.KEY_VOLTAGE) {
                        buf.write((completeCursor!!.getInt(mAdapter!!.voltageIndex) / 1000.0).toString())
                    }
                    i++
                }
                buf.write("\r\n")
                completeCursor!!.moveToNext()
            }

            buf.close()
            fileWriter.close()
            pfd.close()
        } catch (e: Exception) {
            Toast.makeText(activity, DisplayStrings.inaccessibleStorage, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, DisplayStrings.fileWritten, Toast.LENGTH_SHORT).show()
    }

    // Based on http://stackoverflow.com/a/7343721/1427098
    private class FilteredCursor(private val wrappedCursor: Cursor) : CursorWrapper(
        wrappedCursor
    ) {
        private val shownIDs: ArrayList<Int?> = ArrayList()
        private var len = 0
        private var pos = 0

        init {
            refilter()
        }

        fun refilter() {
            if (wrappedCursor.isClosed()) return

            shownIDs.clear()

            val wrappedCursorPos = wrappedCursor.getPosition()
            val statusCodeIndex = wrappedCursor.getColumnIndexOrThrow(LogDatabase.KEY_STATUS_CODE)
            val showPluggedIn: Boolean = pFrag!!.spMain.getBoolean("plugged_in", true)
            val showUnplugged: Boolean = pFrag!!.spMain.getBoolean("unplugged", true)
            val showCharging: Boolean = pFrag!!.spMain.getBoolean("charging", true)
            val showDischarging: Boolean = pFrag!!.spMain.getBoolean("discharging", true)
            val showFullyCharged: Boolean = pFrag!!.spMain.getBoolean("fully_charged", true)
            val showBoot: Boolean = pFrag!!.spMain.getBoolean("boot_completed", true)
            val showUnknown: Boolean = pFrag!!.spMain.getBoolean("unknown", true)
            val showNotCharging: Boolean = pFrag!!.spMain.getBoolean("not_charging", true)

            wrappedCursor.moveToFirst()
            while (!wrappedCursor.isAfterLast()) {
                val statusCode = wrappedCursor.getInt(statusCodeIndex)
                val statusCodes: IntArray = LogDatabase.decodeStatus(statusCode)
                val status = statusCodes[0]
                val statusAge = statusCodes[2]

                if (status == BatteryInfo.STATUS_FULLY_CHARGED && showFullyCharged) {
                    shownIDs.add(wrappedCursor.getPosition())
                } else if (status == LogDatabase.STATUS_BOOT_COMPLETED && showBoot) {
                    shownIDs.add(wrappedCursor.getPosition())
                } else if (status == BatteryInfo.STATUS_NOT_CHARGING && showNotCharging) {
                    shownIDs.add(wrappedCursor.getPosition())
                } else if ((status == BatteryInfo.STATUS_UNKNOWN || status == BatteryInfo.STATUS_DISCHARGING || status > BatteryInfo.STATUS_MAX) && showUnknown) {
                    shownIDs.add(wrappedCursor.getPosition())
                } else if (statusAge == LogDatabase.STATUS_OLD) {
                    if ((status == BatteryInfo.STATUS_UNPLUGGED && showDischarging) || (status == BatteryInfo.STATUS_CHARGING && showCharging)) shownIDs.add(
                        wrappedCursor.getPosition()
                    )
                } else if (statusAge == LogDatabase.STATUS_NEW) {
                    if ((status == BatteryInfo.STATUS_UNPLUGGED && showUnplugged) || (status == BatteryInfo.STATUS_CHARGING && showPluggedIn)) shownIDs.add(
                        wrappedCursor.getPosition()
                    )
                }
                wrappedCursor.moveToNext()
            }

            wrappedCursor.moveToPosition(wrappedCursorPos)

            len = shownIDs.size
            pos = -1
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun requery(): Boolean {
            val ret = super.requery()
            refilter()
            return ret
        }

        override fun getCount(): Int {
            return len
        }

        override fun moveToPosition(newPos: Int): Boolean {
            val moved = super.moveToPosition(shownIDs[newPos]!!)

            if (moved) pos = newPos

            return moved
        }

        override fun move(offset: Int): Boolean {
            return moveToPosition(pos + offset)
        }

        override fun moveToFirst(): Boolean {
            return moveToPosition(0)
        }

        override fun moveToLast(): Boolean {
            return moveToPosition(len - 1)
        }

        override fun moveToNext(): Boolean {
            return moveToPosition(pos + 1)
        }

        override fun moveToPrevious(): Boolean {
            return moveToPosition(pos - 1)
        }

        override fun isFirst(): Boolean {
            return len != 0 && pos == 0
        }

        override fun isLast(): Boolean {
            return len != 0 && pos == len - 1
        }

        override fun isBeforeFirst(): Boolean {
            return len == 0 || pos == -1
        }

        override fun isAfterLast(): Boolean {
            return len == 0 || pos == len
        }

        override fun getPosition(): Int {
            return pos
        }
    }

    private class TimeDeltaCursor(private val wrappedCursor: Cursor) : CursorWrapper(
        wrappedCursor
    ) {
        private val deltaColumnIndex: Int = super.getColumnCount()
        private val deltaColumnName = KEY_TIME_DELTA

        private var statusCodeIndex = 0
        private var timeIndex = 0

        private var lastPlugged: Long = 0
        private var lastUnplugged: Long = 0

        private val deltas: ArrayList<Long?> = ArrayList()

        init {
            genDeltas()
        }

        fun genDelta() {
            val time = wrappedCursor.getLong(timeIndex)
            val statusCode = wrappedCursor.getInt(statusCodeIndex)
            val statusCodes: IntArray = LogDatabase.decodeStatus(statusCode)
            val status = statusCodes[0]
            val status_age = statusCodes[2]

            if (status == BatteryInfo.STATUS_FULLY_CHARGED) {
                if (lastPlugged > 0) deltas.add(time - lastPlugged)
                else deltas.add(-1L)
            } else if (status_age == LogDatabase.STATUS_NEW && status == BatteryInfo.STATUS_UNPLUGGED) {
                if (lastPlugged > 0) deltas.add(time - lastPlugged)
                else deltas.add(-1L)

                lastUnplugged = time
            } else if (status_age == LogDatabase.STATUS_NEW && status == BatteryInfo.STATUS_CHARGING) {
                if (lastUnplugged > 0) deltas.add(time - lastUnplugged)
                else deltas.add(-1L)

                lastPlugged = time
            } else {
                deltas.add(-1L)
            }
        }

        fun wrappedIsChronological(): Boolean {
            if (wrappedCursor.getCount() < 2) return true
            var chrono = true

            val pos = wrappedCursor.getPosition()

            wrappedCursor.moveToFirst()
            val time1 = wrappedCursor.getLong(timeIndex)
            wrappedCursor.moveToNext()
            var time2 = wrappedCursor.getLong(timeIndex)

            while (time2 == time1 && !wrappedCursor.isAfterLast()) {
                wrappedCursor.moveToNext()
                time2 = wrappedCursor.getLong(timeIndex)
            }

            if (time2 < time1) chrono = false

            wrappedCursor.moveToPosition(pos)

            return chrono
        }

        fun genDeltas() {
            if (wrappedCursor.isClosed()) return

            deltas.clear()

            lastPlugged = -1
            lastUnplugged = -1

            val wrappedCursorPos = wrappedCursor.getPosition()
            statusCodeIndex = wrappedCursor.getColumnIndexOrThrow(LogDatabase.KEY_STATUS_CODE)
            timeIndex = wrappedCursor.getColumnIndexOrThrow(LogDatabase.KEY_TIME)

            if (wrappedIsChronological()) {
                wrappedCursor.moveToFirst()
                while (!wrappedCursor.isAfterLast()) {
                    genDelta()
                    wrappedCursor.moveToNext()
                }
            } else {
                wrappedCursor.moveToLast()
                while (!wrappedCursor.isBeforeFirst()) {
                    genDelta()
                    wrappedCursor.moveToPrevious()
                }
            }

            wrappedCursor.moveToPosition(wrappedCursorPos)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun requery(): Boolean {
            val ret = super.requery()
            genDeltas()
            return ret
        }

        override fun getColumnCount(): Int {
            return deltaColumnIndex + 1
        }

        override fun getColumnIndex(columnName: String?): Int {
            return if (deltaColumnName == columnName) deltaColumnIndex
            else super.getColumnIndex(columnName)
        }

        @Throws(IllegalArgumentException::class)
        override fun getColumnIndexOrThrow(columnName: String?): Int {
            return if (deltaColumnName == columnName) deltaColumnIndex
            else super.getColumnIndexOrThrow(columnName)
        }

        override fun getColumnName(columnIndex: Int): String? {
            return if (columnIndex == deltaColumnIndex) deltaColumnName
            else super.getColumnName(columnIndex)
        }

        override fun getColumnNames(): Array<String?> {
            val a = super.getColumnNames()
            val b = arrayOfNulls<String>(a.size + 1)

            System.arraycopy(a, 0, b, 0, a.size)

            b[a.size] = deltaColumnName

            return b
        }

        override fun getLong(columnIndex: Int): Long {
            if (columnIndex == deltaColumnIndex) {
                var pos = position

                if (!wrappedIsChronological()) pos = count - 1 - pos

                return deltas[pos]!!
            } else return super.getLong(columnIndex)
        }

        override fun isNull(columnIndex: Int): Boolean {
            return if (columnIndex == deltaColumnIndex) false
            else super.isNull(columnIndex)
        }

    }

    private class LogItemViewHolder {
        var statusTv: TextView? = null
        var percentTv: TextView? = null
        var timeTv: TextView? = null
        var tempVoltTv: TextView? = null
        var timeDiffTv: TextView? = null
    }

    @Suppress("DEPRECATION")
    private inner class LogAdapter(context: Context?, cursor: Cursor) :
        CursorAdapter(context, cursor) {
        var statusCodeIndex: Int = cursor.getColumnIndexOrThrow(LogDatabase.KEY_STATUS_CODE)
        var chargeIndex: Int = cursor.getColumnIndexOrThrow(LogDatabase.KEY_CHARGE)
        var timeIndex: Int = cursor.getColumnIndexOrThrow(LogDatabase.KEY_TIME)
        var temperatureIndex: Int = cursor.getColumnIndexOrThrow(LogDatabase.KEY_TEMPERATURE)
        var voltageIndex: Int = cursor.getColumnIndexOrThrow(LogDatabase.KEY_VOLTAGE)
        var timeDeltaIndex: Int = cursor.getColumnIndexOrThrow(KEY_TIME_DELTA)
        var dateFormat: DateFormat = android.text.format.DateFormat.getDateFormat(context)

        private val d = Date()

        override fun newView(context: Context?, cursor: Cursor?, parent: ViewGroup?): View {
            val v = mInflater!!.inflate(R.layout.log_item, parent, false)
            val vh = LogItemViewHolder()

            vh.statusTv = v.findViewById<View?>(R.id.status) as TextView
            vh.percentTv = v.findViewById<View?>(R.id.percent) as TextView
            vh.timeTv = v.findViewById<View?>(R.id.time) as TextView
            vh.tempVoltTv = v.findViewById<View?>(R.id.temp_volt) as TextView
            vh.timeDiffTv = v.findViewById<View?>(R.id.time_diff) as TextView

            v.tag = vh

            return v
        }

        @SuppressLint("SetTextI18n")
        override fun bindView(view: View, context: Context?, cursor: Cursor) {
            val vh: LogItemViewHolder = view.tag as LogItemViewHolder

            val statusTv = vh.statusTv!!
            val percentTv = vh.percentTv!!
            val timeTv = vh.timeTv!!
            val tempVoltTv = vh.tempVoltTv!!
            val timeDiffTv = vh.timeDiffTv!!

            val statusCode = cursor.getInt(statusCodeIndex)
            val statusCodes: IntArray = LogDatabase.decodeStatus(statusCode)
            val status = statusCodes[0]
            val plugged = statusCodes[1]
            val statusAge = statusCodes[2]

            var s: String?

            if (status == LogDatabase.STATUS_BOOT_COMPLETED) percentTv.visibility = View.GONE
            else percentTv.visibility = View.VISIBLE

            if (status == LogDatabase.STATUS_BOOT_COMPLETED) {
                statusTv.setTextColor(col!!.boot)
                s = DisplayStrings.statusBootCompleted

                timeDiffTv.visibility = View.GONE
            } else if (statusAge == LogDatabase.STATUS_OLD) {
                statusTv.setTextColor(col!!.oldStatus)
                percentTv.setTextColor(col!!.oldStatus)
                s = DisplayStrings.logStatusesOld[status]

                timeDiffTv.visibility = View.GONE
            } else {
                when (status) {
                    0 -> {
                        statusTv.setTextColor(col!!.unplugged)
                        percentTv.setTextColor(col!!.unplugged)
                    }

                    2 -> {
                        statusTv.setTextColor(col!!.plugged)
                        percentTv.setTextColor(col!!.plugged)
                    }

                    5 -> {
                        statusTv.setTextColor(col!!.charged)
                        percentTv.setTextColor(col!!.charged)
                    }

                    else -> {
                        statusTv.setTextColor(col!!.unknown)
                        percentTv.setTextColor(col!!.unknown)
                    }
                }

                s = DisplayStrings.logStatuses[status]
                val delta: Long
                val secs: Long
                val mins: Long

                when (status) {
                    0, 5 -> {
                        delta = cursor.getLong(timeDeltaIndex)

                        if (delta < 0) {
                            timeDiffTv.visibility = View.GONE
                        } else {
                            secs = delta / 1000
                            mins = secs / 60

                            if (mins >= 60) timeDiffTv.text = String.format(
                                pFrag!!.res.getString(R.string.after_nh_mm_plugged_in),
                                mins / 60,
                                mins % 60
                            )
                            else timeDiffTv.text = String.format(
                                pFrag!!.res.getString(R.string.after_nm_ms_plugged_in),
                                mins,
                                secs % 60
                            )

                            timeDiffTv.visibility = View.VISIBLE
                        }
                    }

                    2 -> {
                        delta = cursor.getLong(timeDeltaIndex)

                        if (delta < 0) {
                            timeDiffTv.visibility = View.GONE
                        } else {
                            secs = delta / 1000
                            mins = secs / 60

                            if (mins >= 60) timeDiffTv.text = String.format(
                                pFrag!!.res.getString(R.string.after_nh_mm_unplugged),
                                mins / 60,
                                mins % 60
                            )
                            else timeDiffTv.text = String.format(
                                pFrag!!.res.getString(R.string.after_nm_ms_unplugged),
                                mins,
                                secs % 60
                            )

                            timeDiffTv.visibility = View.VISIBLE
                        }
                    }

                    else -> timeDiffTv.visibility = View.GONE
                }
            }

            if (plugged > 0) s += " " + DisplayStrings.pluggeds[plugged]

            statusTv.text = s

            percentTv.text = "" + cursor.getInt(chargeIndex) + "%"

            d.setTime(cursor.getLong(timeIndex))

            val includeSeconds = pFrag!!.spMain.getBoolean(KEY_SHOW_SECONDS, false)
            timeTv.text = dateFormat.format(d) + "  " + DisplayStrings.formatTime(
                requireNotNull(context), d, includeSeconds
            )

            val temperature = cursor.getInt(temperatureIndex)
            if (temperature != 0) tempVoltTv.text =
                "" + DisplayStrings.formatTemp(temperature, convertF)
            else tempVoltTv.text = ""

            val voltage = cursor.getInt(voltageIndex)
            if (voltage != 0) tempVoltTv.text =
                tempVoltTv.getText().toString() + " / " + DisplayStrings.formatVoltage(voltage)
        }
    }

    private class Col {
        var oldStatus: Int = pFrag!!.res.getColor(R.color.log_old_status, null)
        var charged: Int = pFrag!!.res.getColor(R.color.log_charged, null)
        var plugged: Int = pFrag!!.res.getColor(R.color.log_plugged, null)
        var unplugged: Int = pFrag!!.res.getColor(R.color.log_unplugged, null)
        var unknown: Int = pFrag!!.res.getColor(R.color.log_unknown, null)
        var boot: Int = pFrag!!.res.getColor(R.color.log_boot_completed, null)
    }
}
