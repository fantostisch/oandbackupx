package com.machiav3lli.backup.items

import android.app.usage.StorageStats
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.machiav3lli.backup.Constants
import com.machiav3lli.backup.Constants.classTag
import com.machiav3lli.backup.handler.BackendController
import com.machiav3lli.backup.items.BackupItem.BrokenBackupException
import com.machiav3lli.backup.utils.DocumentUtils.deleteRecursive
import com.machiav3lli.backup.utils.DocumentUtils.ensureDirectory
import com.machiav3lli.backup.utils.DocumentUtils.getBackupRoot
import com.machiav3lli.backup.utils.FileUtils.BackupLocationIsAccessibleException
import com.machiav3lli.backup.utils.LogUtils
import com.machiav3lli.backup.utils.LogUtils.Companion.logErrors
import com.machiav3lli.backup.utils.PrefUtils.StorageLocationNotConfiguredException
import java.io.File
import java.io.FileNotFoundException

/**
 * Information container for regular and system apps.
 * It knows if an app is installed and if it has backups to restore.
 */
class AppInfoX {
    private val context: Context
    val packageName: String
    var appInfo: AppMetaInfo? = null
        private set
    var backupHistory: MutableList<BackupItem> = arrayListOf()
        private set
    private var backupDir: Uri? = null
    private var storageStats: StorageStats? = null
    var packageInfo: PackageInfo? = null
        private set

    /**
     * This method is used to inject external created AppMetaInfo objects for example for
     * virtual (special) packages
     *
     * @param context  Context object of the app
     * @param metaInfo Constructed information object that describes the package
     */
    internal constructor(context: Context, metaInfo: AppMetaInfo) {
        this.context = context
        appInfo = metaInfo
        packageName = metaInfo.packageName.toString()
        val backupDoc = getBackupRoot(context).findFile(packageName)
        var getHistory = Thread()
        if (backupDoc != null) {
            backupDir = backupDoc.uri
            getHistory = Thread { backupHistory = getBackupHistory(context, backupDir) }
            getHistory.start()
            // backupHistory = getBackupHistory(context, backupDir)
        } else {
            backupHistory = arrayListOf()
        }
        getHistory.join()
    }

    constructor(context: Context, packageInfo: PackageInfo) {
        this.context = context
        packageName = packageInfo.packageName
        this.packageInfo = packageInfo
        val backupDoc = getBackupRoot(context).findFile(packageName)
        if (backupDoc != null) {
            backupDir = backupDoc.uri
        }
        refreshStorageStats()
    }

    constructor(context: Context, backupRoot: Uri) {
        this.context = context
        backupDir = backupRoot
        backupHistory = getBackupHistory(context, backupRoot)
        packageName = StorageFile.fromUri(context, backupRoot).name!!
        try {
            packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            appInfo = AppMetaInfo(context, packageInfo as PackageInfo)
            refreshStorageStats()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "$packageName is not installed.")
            if (backupHistory.isNullOrEmpty()) {
                throw AssertionError("Backup History is empty and package is not installed. The package is completely unknown?", e)
            }
            appInfo = latestBackup!!.backupProperties
        }
    }

    constructor(context: Context, packageInfo: PackageInfo, backupRoot: Uri?) {
        this.context = context
        packageName = packageInfo.packageName
        this.packageInfo = packageInfo
        val packageBackupRoot = StorageFile.fromUri(context, backupRoot!!).findFile(packageName)
        var getHistory = Thread()
        if (packageBackupRoot != null) {
            backupDir = packageBackupRoot.uri
            getHistory = Thread { backupHistory = getBackupHistory(context, backupDir) }
            getHistory.start()
            // backupHistory = getBackupHistory(context, backupDir)
        }
        getHistory.join()
        appInfo = AppMetaInfo(context, packageInfo)
        refreshStorageStats()
    }

    private fun refreshStorageStats(): Boolean {
        return try {
            storageStats = BackendController.getPackageStorageStats(context, packageName)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not refresh StorageStats. Package was not found: " + e.message)
            false
        }
    }

    fun refreshFromPackageManager(context: Context): Boolean {
        Log.d(TAG, "Trying to refresh package information for $packageName from PackageManager")
        try {
            packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            appInfo = AppMetaInfo(context, packageInfo as PackageInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "$packageName is not installed. Refresh failed")
            return false
        }
        return true
    }

    fun refreshBackupHistory() {
        backupHistory = getBackupHistory(context, backupDir)
    }

    fun addBackup(backupItem: BackupItem) {
        Log.d(TAG, "[$packageName] Adding backup: $backupItem")
        backupHistory.add(backupItem)
    }

    fun deleteAllBackups() {
        Log.i(TAG, "Deleting ${backupHistory.size} backups of $this")
        StorageFile.fromUri(this.context, backupDir!!).delete()
        backupHistory.clear()
        backupDir = null
    }

    fun delete(backupItem: BackupItem, directBoolean: Boolean = true) {
        if (backupItem.backupProperties.packageName != packageName) {
            throw RuntimeException("Asked to delete a backup of ${backupItem.backupProperties.packageName} but this object is for $packageName")
        }
        Log.d(TAG, "[$packageName] Deleting backup revision $backupItem")
        val propertiesFileName = String.format(BackupProperties.BACKUP_INSTANCE_PROPERTIES,
                Constants.BACKUP_DATE_TIME_FORMATTER.format(backupItem.backupProperties.backupDate), backupItem.backupProperties.profileId)
        try {
            deleteRecursive(context, backupItem.backupLocation)
            StorageFile.fromUri(context, backupDir!!).findFile(propertiesFileName)!!.delete()
        } catch (e: Throwable) {
            LogUtils.unhandledException(e, backupItem.backupProperties.packageName)
        }
        if (directBoolean) backupHistory.remove(backupItem)
    }

    @Throws(BackupLocationIsAccessibleException::class, StorageLocationNotConfiguredException::class)
    fun getBackupDir(create: Boolean): Uri? {
        if (create && backupDir == null) {
            backupDir = ensureDirectory(getBackupRoot(context), packageName)!!.uri
        }
        return backupDir
    }

    val isInstalled: Boolean
        get() = packageInfo != null || appInfo!!.isSpecial
    val isDisabled: Boolean
        get() = !appInfo!!.isSpecial && packageInfo != null && !packageInfo!!.applicationInfo.enabled
    val isSystem: Boolean
        get() = packageInfo != null && appInfo!!.isSystem || appInfo!!.isSpecial
    val isSpecial: Boolean
        get() = appInfo!!.isSpecial
    val packageLabel: String
        get() = if (appInfo!!.packageLabel != null) appInfo!!.packageLabel!! else packageName
    val versionCode: Int
        get() = appInfo!!.versionCode
    val versionName: String?
        get() = appInfo!!.versionName

    val hasBackups: Boolean
        get() = backupHistory.isNotEmpty()

    val latestBackup: BackupItem?
        get() = if (backupHistory.isNotEmpty()) {
            backupHistory[backupHistory.size - 1]
        } else null

    val dataDir: String
        get() = packageInfo!!.applicationInfo.dataDir
    val deviceProtectedDataDir: String
        get() = packageInfo!!.applicationInfo.deviceProtectedDataDir

    // Uses the context to get own external data directory
    // e.g. /storage/emulated/0/Android/data/com.machiav3lli.backup/files
    // Goes to the parent two times to the leave own directory
    // e.g. /storage/emulated/0/Android/data
    val externalDataDir: String
        // Add the package name to the path assuming that if the name of dataDir does not equal the
        // package name and has a prefix or a suffix to use it.
        get() {
            // Uses the context to get own external data directory
            // e.g. /storage/emulated/0/Android/data/com.machiav3lli.backup/files
            // Goes to the parent two times to the leave own directory
            // e.g. /storage/emulated/0/Android/data
            val externalFilesPath = context.getExternalFilesDir(null)!!.parentFile!!.parentFile!!.absolutePath
            // Add the package name to the path assuming that if the name of dataDir does not equal the
            // package name and has a prefix or a suffix to use it.
            return File(externalFilesPath, File(dataDir).name).absolutePath
        }

    // Uses the context to get own obb data directory
    // e.g. /storage/emulated/0/Android/obb/com.machiav3lli.backup
    // Goes to the parent two times to the leave own directory
    // e.g. /storage/emulated/0/Android/obb
    val obbFilesDir: String
        // Add the package name to the path assuming that if the name of dataDir does not equal the
        // package name and has a prefix or a suffix to use it.
        get() {
            // Uses the context to get own obb data directory
            // e.g. /storage/emulated/0/Android/obb/com.machiav3lli.backup
            // Goes to the parent two times to the leave own directory
            // e.g. /storage/emulated/0/Android/obb
            val obbFilesPath = context.obbDir.parentFile!!.absolutePath
            // Add the package name to the path assuming that if the name of dataDir does not equal the
            // package name and has a prefix or a suffix to use it.
            return File(obbFilesPath, File(dataDir).name).absolutePath
        }
    val apkPath: String
        get() = packageInfo!!.applicationInfo.sourceDir
    val dataBytes: Long
        get() = if (appInfo!!.isSpecial) 0 else storageStats!!.dataBytes

    /**
     * Returns the list of additional apks (excluding the main apk), if the app is installed
     *
     * @return array of with absolute filepaths pointing to one or more split apks or null if
     * the app is not splitted
     */
    val apkSplits: Array<String>?
        get() = appInfo!!.splitSourceDirs
    val isUpdated: Boolean
        get() = (backupHistory.isNotEmpty()
                && latestBackup!!.backupProperties.versionCode < versionCode)

    val hasApk: Boolean
        get() = backupHistory.stream().anyMatch { backupItem: BackupItem -> backupItem.backupProperties.hasApk }

    val hasAppData: Boolean
        get() = backupHistory.stream().anyMatch { backupItem: BackupItem -> backupItem.backupProperties.hasAppData }

    val hasExternalData: Boolean
        get() = backupHistory.stream().anyMatch { backupItem: BackupItem -> backupItem.backupProperties.hasExternalData }

    val hasDevicesProtectedData: Boolean
        get() = backupHistory.stream().anyMatch { backupItem: BackupItem -> backupItem.backupProperties.hasDevicesProtectedData }

    val hasObbData: Boolean
        get() = backupHistory.stream().anyMatch { backupItem: BackupItem -> backupItem.backupProperties.hasObbData }

    override fun toString(): String {
        return packageName
    }

    companion object {
        private val TAG = classTag(".AppInfoX")

        // TODO cause of huge part of cpu time
        private fun getBackupHistory(context: Context, backupDir: Uri?): MutableList<BackupItem> {
            val appBackupDir = StorageFile.fromUri(context, backupDir!!)
            val backupHistory: MutableList<BackupItem> = mutableListOf()
            try {
                for (file in appBackupDir.listFiles()) {
                    if (file.isPropertyFile)
                        try {
                            backupHistory.add(BackupItem(context, file))
                        } catch (e: BrokenBackupException) {
                            val message = "Incomplete backup or wrong structure found in ${backupDir.encodedPath}."
                            Log.w(TAG, message)
                            logErrors(context, message)
                        } catch (e: NullPointerException) {
                            val message = "(Null) Incomplete backup or wrong structure found in ${backupDir.encodedPath}."
                            Log.w(TAG, message)
                            logErrors(context, message)
                        } catch (e: Throwable) {
                            val message = "(catchall) Incomplete backup or wrong structure found in ${backupDir.encodedPath}."
                            LogUtils.unhandledException(e, file.uri)
                            logErrors(context, message)
                        }
                }
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Failed getting backup history: $e")
                return backupHistory
            } catch (e: Throwable) {
                LogUtils.unhandledException(e, backupDir.encodedPath)
                return backupHistory
            }
            return backupHistory
        }
    }
}