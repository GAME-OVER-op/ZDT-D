package com.android.zdtd.service.xposed

import android.os.Binder
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * LSPosed self-protection hook for ZDT-D.
 *
 * Scope: android / System framework.
 * Goal: hide only com.android.zdtd.service from ordinary user applications.
 *
 * The hook is intentionally settings-free: enabling/disabling is controlled by LSPosed.
 */
class ZdtdHideHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        runCatching {
            hookPackageManagerClass(lpparam, "com.android.server.pm.ComputerEngine")
            hookPackageManagerClass(lpparam, "com.android.server.pm.PackageManagerService")
            writeLsposedStatus("installed", "install", Binder.getCallingUid(), false)
            log("installed for PackageManager hooks")
        }.onFailure {
            log("install error: ${it.stackTraceToString()}")
        }
    }

    private fun hookPackageManagerClass(lpparam: XC_LoadPackage.LoadPackageParam, className: String) {
        val cls = runCatching { XposedHelpers.findClass(className, lpparam.classLoader) }.getOrNull() ?: run {
            log("$className not found")
            return
        }

        hookAfterAll(cls, "getInstalledPackages") { param -> filterPackageListResult(param, "getInstalledPackages") }
        hookAfterAll(cls, "getInstalledApplications") { param -> filterApplicationListResult(param, "getInstalledApplications") }

        hookAfterAll(cls, "queryIntentActivities") { param -> filterResolveListResult(param, "queryIntentActivities") }
        hookAfterAll(cls, "queryIntentActivitiesInternal") { param -> filterResolveListResult(param, "queryIntentActivitiesInternal") }
        hookAfterAll(cls, "queryIntentServices") { param -> filterResolveListResult(param, "queryIntentServices") }
        hookAfterAll(cls, "queryIntentReceivers") { param -> filterResolveListResult(param, "queryIntentReceivers") }
        hookAfterAll(cls, "queryIntentContentProviders") { param -> filterResolveListResult(param, "queryIntentContentProviders") }
        hookAfterAll(cls, "queryContentProviders") { param -> filterProviderListResult(param, "queryContentProviders") }
        hookAfterAll(cls, "getPackagesHoldingPermissions") { param -> filterPackageListResult(param, "getPackagesHoldingPermissions") }
        hookAfterAll(cls, "getPersistentApplications") { param -> filterApplicationListResult(param, "getPersistentApplications") }
        hookAfterAll(cls, "getPreferredPackages") { param -> filterPackageListResult(param, "getPreferredPackages") }

        hookBeforeDirectPackage(cls, "getPackageInfo")
        hookBeforeDirectPackage(cls, "getPackageInfoInternal")
        hookBeforeDirectPackage(cls, "getPackageInfoVersioned")
        hookBeforeDirectPackage(cls, "getApplicationInfo")
        hookBeforeDirectPackage(cls, "getApplicationInfoInternal")
        hookBeforeDirectPackage(cls, "getPackageUid")
        hookBeforeDirectPackage(cls, "getPackageUidInternal")
        hookBeforeDirectPackage(cls, "getPackageGids")
        hookBeforeDirectPackage(cls, "getPackageGidsInternal")
        hookBeforeDirectPackage(cls, "getInstallerPackageName")
        hookBeforeDirectPackage(cls, "getInstallSourceInfo")
        hookBeforeDirectPackage(cls, "isPackageAvailable")
        hookBeforeDirectPackage(cls, "isPackageSuspendedForUser")
        hookBeforeDirectPackage(cls, "getApplicationEnabledSetting")
        hookBeforeDirectPackage(cls, "getTargetSdkVersion")
        hookBeforeDirectPackage(cls, "checkSignatures")

        hookBeforeDirectComponent(cls, "getActivityInfo")
        hookBeforeDirectComponent(cls, "getReceiverInfo")
        hookBeforeDirectComponent(cls, "getServiceInfo")
        hookBeforeDirectComponent(cls, "getProviderInfo")
        hookBeforeDirectComponent(cls, "getComponentEnabledSetting")
        hookBeforeDirectComponent(cls, "activitySupportsIntent")

        hookAfterAll(cls, "getPackagesForUid", ::filterStringArrayResult)
        hookAfterAll(cls, "getNamesForUids", ::filterNamesForUidsResult)
        hookAfterAll(cls, "getNameForUid", ::filterNameForUidResult)

        log("hooks registered for $className")
    }

    private fun hookAfterAll(
        cls: Class<*>,
        methodName: String,
        after: (XC_MethodHook.MethodHookParam) -> Unit,
    ) {
        val methods = cls.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return
        XposedBridge.hookAllMethods(
            cls,
            methodName,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (insideHook.get()) return
                    if (!shouldHideFromCaller(methodName)) return
                    runCatching {
                        insideHook.set(true)
                        after(param)
                    }.onFailure {
                        log("$methodName after-hook error: ${it.stackTraceToString()}")
                    }.also {
                        insideHook.set(false)
                    }
                }
            },
        )
        log("hooked ${cls.name}.$methodName x${methods.size}")
    }

    private fun hookBeforeDirectPackage(cls: Class<*>, methodName: String) {
        val methods = cls.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return

        XposedBridge.hookAllMethods(
            cls,
            methodName,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (insideHook.get()) return
                    if (!shouldHideFromCaller(methodName)) return
                    if (!param.args.any { directPackageName(it) == ZDTD_PACKAGE }) return

                    runCatching {
                        writeLsposedStatus("direct_hide", methodName, Binder.getCallingUid(), true)
                        param.result = hiddenDirectResult(param.method as? Method)
                    }.onFailure {
                        log("$methodName before-hook error: ${it.stackTraceToString()}")
                    }
                }
            },
        )
        log("hooked ${cls.name}.$methodName direct x${methods.size}")
    }

    private fun hookBeforeDirectComponent(cls: Class<*>, methodName: String) {
        val methods = cls.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return

        XposedBridge.hookAllMethods(
            cls,
            methodName,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (insideHook.get()) return
                    if (!shouldHideFromCaller(methodName)) return
                    if (!param.args.any { componentPackageName(it) == ZDTD_PACKAGE }) return

                    runCatching {
                        writeLsposedStatus("component_hide", methodName, Binder.getCallingUid(), true)
                        param.result = hiddenDirectResult(param.method as? Method)
                    }.onFailure {
                        log("$methodName component before-hook error: ${it.stackTraceToString()}")
                    }
                }
            },
        )
        log("hooked ${cls.name}.$methodName component x${methods.size}")
    }

    private fun shouldHideFromCaller(methodName: String): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == 0 || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID || uid == Process.PHONE_UID) {
            return false
        }

        // Only ordinary Android application appIds are filtered. This keeps core framework/system callers safe.
        val appId = uid % PER_USER_RANGE
        if (appId < Process.FIRST_APPLICATION_UID) return false

        val callerPackages = resolvePackagesForUid(uid)
        if (callerPackages.any { it == ZDTD_PACKAGE || it in TRUSTED_CALLER_PACKAGES }) return false

        val selected = shouldHideForLsposedPrefs(uid, callerPackages)
        if (selected) writeLsposedStatus("matched", methodName, uid, false)
        return selected
    }

    private fun shouldHideForLsposedPrefs(uid: Int, callerPackages: Array<String>): Boolean {
        if (uid <= 0) return false
        val now = System.currentTimeMillis()
        val targets = synchronized(targetCacheLock) {
            if (now - targetCacheLoadedAtMs > TARGET_CACHE_TTL_MS) {
                targetCache = readLsposedTargetsOrNull()
                targetCacheLoadedAtMs = now
            }
            targetCache
        }

        // If LSPosed preferences cannot be read for any reason, keep the old known-good
        // behaviour: hide ZDT-D from ordinary untrusted apps instead of disabling protection.
        return targets?.let { prefs ->
            uid in prefs.uids || callerPackages.any { it in prefs.packages }
        } ?: true
    }

    private fun readLsposedTargetsOrNull(): TargetPrefs? {
        val prefs = runCatching { XSharedPreferences(ZDTD_PACKAGE, LSPOSED_HIDE_PREFS_NAME) }
            .getOrElse {
                log("prefs open failed: ${it.message ?: it}")
                return null
            }
        return runCatching {
            val prefFile = runCatching { prefs.file }.getOrNull()
            if (prefFile != null && !prefFile.canRead()) {
                log("prefs not readable: ${prefFile.absolutePath}")
                return null
            }
            prefs.reload()
            if (!prefs.getBoolean(LSPOSED_HIDE_PREF_ENABLED, true)) {
                return TargetPrefs(emptySet(), emptySet())
            }
            val uidSet = (prefs.getStringSet(LSPOSED_HIDE_PREF_UIDS, emptySet<String>()) ?: emptySet())
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .toSet()
            val packageSet = (prefs.getStringSet(LSPOSED_HIDE_PREF_PACKAGES, emptySet<String>()) ?: emptySet())
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != ZDTD_PACKAGE }
                .toSet()
            TargetPrefs(uidSet, packageSet)
        }.getOrElse {
            log("prefs read failed: ${it.message ?: it}")
            null
        }
    }

    private fun writeLsposedStatus(reason: String, methodName: String, callerUid: Int, filtered: Boolean) {
        val now = System.currentTimeMillis()
        if (!filtered && reason != "matched" && now - lastStatusWriteMs < STATUS_WRITE_TTL_MS) return
        lastStatusWriteMs = now
        val targetCount = synchronized(targetCacheLock) { targetCache?.let { it.uids.size + it.packages.size } ?: -1 }
        log("status reason=$reason method=$methodName caller_uid=$callerUid filtered=$filtered targets_loaded=$targetCount")
    }

    private fun resolvePackagesForUid(uid: Int): Array<String> {
        return runCatching {
            insideHook.set(true)
            val appGlobals = Class.forName("android.app.AppGlobals")
            val pm = appGlobals.getMethod("getPackageManager").invoke(null) ?: return@runCatching emptyArray<String>()
            @Suppress("UNCHECKED_CAST")
            (XposedHelpers.callMethod(pm, "getPackagesForUid", uid) as? Array<String>) ?: emptyArray()
        }.getOrElse {
            emptyArray()
        }.also {
            insideHook.set(false)
        }
    }

    private fun hiddenDirectResult(method: Method?): Any? {
        val returnType = method?.returnType ?: return null
        return when {
            returnType == java.lang.Boolean.TYPE -> false
            returnType == java.lang.Integer.TYPE -> -1
            returnType == java.lang.Long.TYPE -> -1L
            returnType == java.lang.Short.TYPE -> (-1).toShort()
            returnType == java.lang.Byte.TYPE -> (-1).toByte()
            returnType == java.lang.Float.TYPE -> -1f
            returnType == java.lang.Double.TYPE -> -1.0
            returnType == java.lang.Character.TYPE -> 0.toChar()
            returnType == java.lang.Void.TYPE -> null
            returnType.isArray -> java.lang.reflect.Array.newInstance(returnType.componentType, 0)
            else -> null
        }
    }

    private fun filterPackageListResult(param: XC_MethodHook.MethodHookParam, methodName: String) {
        param.result = filterListLikeResult(param.result, methodName) { item ->
            readObjectStringField(item, "packageName") != ZDTD_PACKAGE
        }
    }

    private fun filterApplicationListResult(param: XC_MethodHook.MethodHookParam, methodName: String) {
        param.result = filterListLikeResult(param.result, methodName) { item ->
            readObjectStringField(item, "packageName") != ZDTD_PACKAGE
        }
    }

    private fun filterResolveListResult(param: XC_MethodHook.MethodHookParam, methodName: String) {
        param.result = filterListLikeResult(param.result, methodName) { item ->
            resolveInfoPackageName(item) != ZDTD_PACKAGE
        }
    }

    private fun filterProviderListResult(param: XC_MethodHook.MethodHookParam, methodName: String) {
        param.result = filterListLikeResult(param.result, methodName) { item ->
            readObjectStringField(item, "packageName") != ZDTD_PACKAGE
        }
    }

    private fun filterStringArrayResult(param: XC_MethodHook.MethodHookParam) {
        val arr = param.result as? Array<*> ?: return
        val filtered = arr.filter { it != ZDTD_PACKAGE }.mapNotNull { it as? String }.toTypedArray()
        param.result = if (filtered.isEmpty()) null else filtered
    }

    private fun filterNamesForUidsResult(param: XC_MethodHook.MethodHookParam) {
        val arr = param.result as? Array<*> ?: return
        param.result = arr.map { value ->
            val text = value as? String
            if (nameForUidContainsHiddenPackage(text)) null else text
        }.toTypedArray()
    }

    private fun filterNameForUidResult(param: XC_MethodHook.MethodHookParam) {
        if (nameForUidContainsHiddenPackage(param.result as? String)) param.result = null
    }

    private fun filterStringResult(param: XC_MethodHook.MethodHookParam) {
        if (param.result == ZDTD_PACKAGE) param.result = null
    }

    private fun nameForUidContainsHiddenPackage(value: String?): Boolean {
        if (value == null) return false
        return value == ZDTD_PACKAGE || value.endsWith(":$ZDTD_PACKAGE") || value.split(":").contains(ZDTD_PACKAGE)
    }

    private fun filterListLikeResult(result: Any?, methodName: String, keep: (Any?) -> Boolean): Any? {
        if (result == null) return null

        if (result is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            val filtered = (result as MutableList<Any?>).filterTo(ArrayList()) { keep(it) }
            if (filtered.size != result.size) writeLsposedStatus("filtered_list", methodName, Binder.getCallingUid(), true)
            return filtered
        }
        if (result is List<*>) {
            val filtered = result.filterTo(ArrayList()) { keep(it) }
            if (filtered.size != result.size) writeLsposedStatus("filtered_list", methodName, Binder.getCallingUid(), true)
            return filtered
        }

        if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
            val list = readParceledListSliceList(result) ?: return result
            val filtered = list.filterTo(ArrayList()) { keep(it) }
            if (filtered.size == list.size) return result
            writeLsposedStatus("filtered_slice", methodName, Binder.getCallingUid(), true)
            return newParceledListSlice(result.javaClass, filtered)
                ?: clearParceledListSliceInPlace(result, filtered)
                ?: result
        }

        writeLsposedStatus("unknown_result", methodName, Binder.getCallingUid(), false)
        return result
    }

    private fun readParceledListSliceList(result: Any): List<*>? {
        return runCatching { XposedHelpers.callMethod(result, "getList") as? List<*> }.getOrNull()
            ?: runCatching {
                val field = result.javaClass.getDeclaredField("mList")
                if (!field.isAccessible) field.isAccessible = true
                field.get(result) as? List<*>
            }.getOrNull()
    }

    private fun newParceledListSlice(cls: Class<*>, list: List<*>): Any? {
        // Thanox returns a fresh ParceledListSlice. Do the same, but through reflection so the
        // app still compiles against the public SDK where ParceledListSlice is hidden.
        return runCatching {
            val ctor = cls.declaredConstructors.firstOrNull { ctor ->
                ctor.parameterTypes.size == 1 && List::class.java.isAssignableFrom(ctor.parameterTypes[0])
            } ?: return@runCatching null
            if (!Modifier.isPublic(ctor.modifiers) || !ctor.isAccessible) ctor.isAccessible = true
            ctor.newInstance(list)
        }.getOrNull()
    }

    private fun clearParceledListSliceInPlace(result: Any, filtered: List<*>): Any? {
        return runCatching {
            val field = result.javaClass.getDeclaredField("mList")
            if (!field.isAccessible) field.isAccessible = true
            field.set(result, filtered)
            result
        }.getOrNull()
    }

    private fun resolveInfoPackageName(resolveInfo: Any?): String? {
        if (resolveInfo == null) return null
        for (fieldName in arrayOf("activityInfo", "serviceInfo", "providerInfo")) {
            val componentInfo = runCatching { XposedHelpers.getObjectField(resolveInfo, fieldName) }.getOrNull()
            val packageName = readObjectStringField(componentInfo, "packageName")
            if (packageName != null) return packageName
        }
        return null
    }

    private fun directPackageName(arg: Any?): String? {
        if (arg == null) return null
        if (arg is String) return arg
        componentPackageName(arg)?.let { return it }
        if (arg.javaClass.name == "android.content.pm.VersionedPackage") {
            return runCatching { XposedHelpers.callMethod(arg, "getPackageName") as? String }.getOrNull()
        }
        return readObjectStringField(arg, "packageName")
    }

    private fun componentPackageName(componentName: Any?): String? {
        if (componentName == null || componentName.javaClass.name != "android.content.ComponentName") return null
        return runCatching { XposedHelpers.callMethod(componentName, "getPackageName") as? String }.getOrNull()
    }

    private fun readObjectStringField(obj: Any?, fieldName: String): String? {
        if (obj == null) return null
        return runCatching { XposedHelpers.getObjectField(obj, fieldName) as? String }.getOrNull()
    }

    private fun log(message: String) {
        XposedBridge.log("ZDT-D HideHook: $message")
    }

    private data class TargetPrefs(
        val uids: Set<Int>,
        val packages: Set<String>,
    )

    private companion object {
        const val ZDTD_PACKAGE = "com.android.zdtd.service"
        const val PER_USER_RANGE = 100000
        const val LSPOSED_HIDE_PREFS_NAME = "zdtd_hide_targets"
        const val LSPOSED_HIDE_PREF_ENABLED = "enabled"
        const val LSPOSED_HIDE_PREF_PACKAGES = "packages"
        const val LSPOSED_HIDE_PREF_UIDS = "uids"
        const val TARGET_CACHE_TTL_MS = 2_000L
        const val STATUS_WRITE_TTL_MS = 5_000L
        var targetCacheLoadedAtMs = 0L
        var targetCache: TargetPrefs? = null
        var lastStatusWriteMs = 0L
        val targetCacheLock = Any()

        val TRUSTED_CALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "com.topjohnwu.magisk",
        )

        val insideHook = ThreadLocal.withInitial { false }
    }
}
