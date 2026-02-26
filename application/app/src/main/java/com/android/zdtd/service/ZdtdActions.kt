package com.android.zdtd.service

import org.json.JSONObject

/**
 * UI -> ViewModel actions.
 * (Kept as interface so UI code stays clean and testable.)
 */
interface ZdtdActions {
  // ----- Setup / installer -----
  fun acceptWelcome()
  fun beginModuleInstall()
  fun confirmManualInstall()
  fun dismissManualInstallDialog()
  fun continueAfterInstall()

  /** Remove module and uninstall the app (with reboot after uninstall). */
  fun beginModuleRemoval()
  fun rebootNow()

  // ----- Settings migration (after module update) -----
  /** User clicked the "Migrate settings" button. */
  fun requestSettingsMigration()

  /** Dismiss the current migration dialog (treated as "No"). */
  fun dismissSettingsMigrationDialog()

  /** Confirm the current migration dialog (advances to next step or starts migration). */
  fun confirmSettingsMigrationDialog()

  /** Close the migration progress dialog after completion/error. */
  fun closeSettingsMigrationProgress()

  // ----- Backup / Restore (working_folder) -----
  /** Refresh list of local backups from /storage/emulated/0/ZDT-D_Backups. */
  fun refreshBackups()

  /** Create a new backup of /data/adb/modules/ZDT-D/working_folder (directories only). */
  fun createBackup()

  /** Request importing a backup from a document provider (SAF). */
  fun requestBackupImport()

  /** Called after SAF returns a URI (or null if cancelled). */
  fun onBackupImportResult(uri: android.net.Uri?)

  /** Restore (apply) the selected backup file. */
  fun restoreBackup(name: String, ignoreVersionCode: Boolean = false)

  /** Delete the selected backup file. */
  fun deleteBackup(name: String)

  /** Share the selected backup file via Android Sharesheet. */
  fun shareBackup(name: String)

  /** Close the backup progress dialog after completion/error. */
  fun closeBackupProgress()


  // ----- Program updates (zapret / zapret2 via GitHub) -----
  /** Check zapret (nfqws) installed/latest versions. Requires the service to be stopped. */
  fun checkZapretNow()

  /** Download and install latest zapret (nfqws). Requires the service to be stopped. */
  fun updateZapretNow()

  /** Check zapret2 (nfqws2 + lua) installed/latest versions. Requires the service to be stopped. */
  fun checkZapret2Now()

  /** Download and install latest zapret2 (nfqws2 + lua). Requires the service to be stopped. */
  fun updateZapret2Now()

  /** Stop the daemon once and auto-run check for both zapret + zapret2 when it becomes OFF. */
  fun stopServiceForProgramUpdatesAndCheck()

  /** Load the full list of zapret releases (for selecting a specific version). */
  fun loadZapretReleases()

  /** Load the full list of zapret2 releases (for selecting a specific version). */
  fun loadZapret2Releases()

  /** Set a specific target release for zapret. Pass nulls to use Latest. */
  fun selectZapretRelease(version: String?, downloadUrl: String?)

  /** Set a specific target release for zapret2. Pass nulls to use Latest. */
  fun selectZapret2Release(version: String?, downloadUrl: String?)

  /** Reset transient errors/progress in program update UI. */
  fun resetProgramUpdatesUi()


  /**
   * Called when the app detects an outdated or suspicious module version.
   * Opens the installer flow.
   */
  fun openModuleInstaller()

  /** Dismiss the optional "Update available" prompt (shown again on next cold start). */
  fun dismissUpdatePrompt()

  fun retryRoot()
  fun toggleService()
  fun refreshStatus()
  fun refreshPrograms()
  fun clearLogs()

  /**
   * Read daemon log file tail (root).
   * The UI uses this for the Home screen logs card.
   */
  fun refreshDaemonLog()

  fun setProgramEnabled(programId: String, enabled: Boolean, onDone: (Boolean) -> Unit = {})
  fun setProfileEnabled(programId: String, profile: String, enabled: Boolean, onDone: (Boolean) -> Unit = {})
  fun deleteProfile(programId: String, profile: String, onDone: (Boolean) -> Unit = {})

  /**
   * Create the next profile for a program (server chooses the name).
   * Returns the created profile name (if detected), otherwise null.
   */
  fun createNextProfile(programId: String, onDone: (String?) -> Unit = {})

  /**
   * Create a profile with explicit name.
   * Returns the created profile name (if detected), otherwise null.
   */
  fun createNamedProfile(programId: String, profile: String, onDone: (String?) -> Unit = {})

  fun loadText(path: String, onDone: (String?) -> Unit)
  fun saveText(path: String, content: String, onDone: (Boolean) -> Unit)

  /** Root-only: read a text file from filesystem (e.g. /data/adb/...). */
  fun loadRootTextFile(path: String, onDone: (String?) -> Unit)

  /** Root-only: write a text file to filesystem (e.g. /data/adb/...). */
  fun saveRootTextFile(path: String, content: String, onDone: (Boolean) -> Unit)

  fun loadJsonData(path: String, onDone: (JSONObject?) -> Unit)
  fun saveJsonData(path: String, obj: JSONObject, onDone: (Boolean) -> Unit)

  // ----- Strategic files (zapret / zapret2) -----
  /** List files in strategic/<dir>. dir: list | bin | lua */
  fun listStrategicFiles(dir: String, onDone: (List<String>?) -> Unit)

  /** Read text file from strategic/<dir>/<filename>. dir: list | lua */
  fun loadStrategicText(dir: String, filename: String, onDone: (String?) -> Unit)

  /** Create/overwrite text file in strategic/<dir>/<filename>. dir: list | lua */
  fun saveStrategicText(dir: String, filename: String, content: String, onDone: (Boolean) -> Unit)

  /** Delete file in strategic/<dir>/<filename>. dir: list | bin | lua */
  fun deleteStrategicFile(dir: String, filename: String, onDone: (Boolean) -> Unit)

  /** Upload file into strategic/<dir> via multipart. dir: list | bin | lua */
  fun uploadStrategicFile(dir: String, filename: String, bytes: ByteArray, onDone: (Boolean) -> Unit)

  // ----- Strategic variants (prebuilt strategies -> apply to profile config) -----
  /** List prebuilt strategy variants for a program: nfqws|nfqws2|dpitunnel|byedpi */
  fun listStrategicVariants(programId: String, onDone: (List<com.android.zdtd.service.api.ApiModels.StrategyVariant>?) -> Unit)

  /** Apply a prebuilt strategy file to a profile (overwrites config/config.txt). */
  fun applyStrategicVariant(programId: String, profile: String, file: String, onDone: (Boolean) -> Unit)

  // ----- App update (GitHub) -----
  /** Enable/disable background update checks for the app. */
  fun setAppUpdateChecksEnabled(enabled: Boolean)

  /** Show/hide the app-owned notification about daemon status (running/stopped). */
  fun setDaemonStatusNotificationsEnabled(enabled: Boolean)

  /** Set app UI language: auto | ru | en. */
  fun setAppLanguageMode(mode: String)

  /** Trigger an immediate update check (ignores the 12h cooldown). */
  fun checkAppUpdateNow()

  /** Hide the update banner. */
  fun dismissAppUpdateBanner()

  /** Start downloading the latest APK and then request installation. */
  fun startAppUpdateDownload()

  /** Cancel an in-progress APK download. */
  fun cancelAppUpdateDownload()

  /** User chose to request "install unknown apps" permission. */
  fun requestUnknownSourcesPermission()

  /** User declined to grant permission / online install; fallback to browser. */
  fun declineUnknownSourcesPermission()

  /** Called after returning from the unknown-sources settings screen. */
  fun onUnknownSourcesPermissionResult(granted: Boolean)

  /** Called after the POST_NOTIFICATIONS runtime permission request (Android 13+). */
  fun onPostNotificationsPermissionResult(granted: Boolean)
}
