/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.machiav3lli.backup.BlacklistListener
import com.machiav3lli.backup.Constants
import com.machiav3lli.backup.R
import com.machiav3lli.backup.activities.SchedulerActivityX
import com.machiav3lli.backup.handler.BackendController
import com.machiav3lli.backup.dbs.Schedule

class BlacklistDialogFragment : DialogFragment() {
    private val blacklistListeners = ArrayList<BlacklistListener>()

    fun addBlacklistListener(listener: BlacklistListener) {
        blacklistListeners.add(listener)
    }

    override fun onCreateDialog(savedInstance: Bundle?): Dialog {
        val pm = requireContext().packageManager
        val args = this.requireArguments()
        val blacklistId = args.getInt(Constants.BLACKLIST_ARGS_ID, SchedulerActivityX.GLOBALBLACKLISTID)
        val blacklistedPackages = args.getStringArrayList(Constants.BLACKLIST_ARGS_PACKAGES)
        var packageInfoList = BackendController.getPackageInfoList(requireContext(), Schedule.Mode.ALL)
        packageInfoList = packageInfoList.sortedWith { pi1: PackageInfo, pi2: PackageInfo ->
            val b1 = blacklistedPackages!!.contains(pi1.packageName)
            val b2 = blacklistedPackages.contains(pi2.packageName)
            if (b1 != b2)
                if (b1) -1 else 1
            else {
                val l1 = pi1.applicationInfo.loadLabel(pm).toString()
                val l2 = pi2.applicationInfo.loadLabel(pm).toString()
                l1.compareTo(l2, ignoreCase = true)
            }
        }
        val labels = ArrayList<String>()
        val checkedPackages = BooleanArray(packageInfoList.size)
        val selections = ArrayList<String>()
        for ((i, packageInfo) in packageInfoList.withIndex()) {
            labels.add(packageInfo.applicationInfo.loadLabel(pm).toString())
            if (blacklistedPackages!!.contains(packageInfo.packageName)) {
                checkedPackages[i] = true
                selections.add(packageInfo.packageName)
            }
        }
        return AlertDialog.Builder(requireActivity()).setTitle(R.string.sched_blacklist)
                .setMultiChoiceItems(labels.toTypedArray<CharSequence>(),
                        checkedPackages) { _: DialogInterface?, which: Int, isChecked: Boolean ->
                    val packageName = packageInfoList[which].packageName
                    if (isChecked) selections.add(packageName) else selections.remove(packageName)
                }
                .setPositiveButton(R.string.dialogOK) { _: DialogInterface?, _: Int ->
                    for (listener in blacklistListeners) {
                        listener.onBlacklistChanged(selections.toTypedArray(), blacklistId)
                    }
                }
                .setNegativeButton(R.string.dialogCancel) { _: DialogInterface?, _: Int -> }.create()
    }
}