package com.android.zdtd.service.components

import android.os.Build

/**
 * Upstream release sources for tools that publish ready-to-run Android ARM
 * binaries. Tools without such assets are intentionally NOT listed here: their
 * cards must not offer a version picker.
 */
enum class ToolArchiveKind { ZIP, TARGZ, GZ, RAW }

data class ToolReleaseUi(
  val version: String,
  val downloadUrl: String,
  val sha256: String,
  val publishedAt: String,
  val releaseNotes: String,
  val htmlUrl: String,
)

data class ToolReleaseSpec(
  val componentId: String,
  val installName: String,
  val repo: String,
  val assetRegexArm64: String,
  val assetRegexArm32: String,
  val archiveKind: ToolArchiveKind,
  val binaryPathArm64: String,
  val binaryPathArm32: String,
  val versionFromTag: Boolean = false,
  val versionTagStrip: String = "",
) {
  fun assetRegex(abi: String): String =
    if (abi == "arm64") assetRegexArm64 else assetRegexArm32

  fun binaryPath(abi: String, version: String): String {
    val raw = if (abi == "arm64") binaryPathArm64 else binaryPathArm32
    return raw.replace("{V}", version)
  }
}

object ToolReleaseCatalog {
  /** arm64 or arm32, or null when the device has no supported ABI. */
  fun currentAbi(): String? = when {
    Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64"
    Build.SUPPORTED_ABIS.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "arm32"
    else -> null
  }

  private const val NUM = "[0-9]+(?:\\.[0-9]+){1,3}"

  val specs: List<ToolReleaseSpec> = listOf(
    ToolReleaseSpec(
      componentId = "nfqws",
      installName = "nfqws",
      repo = "bol-van/zapret",
      assetRegexArm64 = "^zapret-v($NUM)\\.zip$",
      assetRegexArm32 = "^zapret-v($NUM)\\.zip$",
      archiveKind = ToolArchiveKind.ZIP,
      binaryPathArm64 = "zapret-v{V}/binaries/android-arm64/nfqws",
      binaryPathArm32 = "zapret-v{V}/binaries/android-arm/nfqws",
    ),
    ToolReleaseSpec(
      componentId = "nfqws2",
      installName = "nfqws2",
      repo = "bol-van/zapret2",
      assetRegexArm64 = "^zapret2-v($NUM)\\.zip$",
      assetRegexArm32 = "^zapret2-v($NUM)\\.zip$",
      archiveKind = ToolArchiveKind.ZIP,
      binaryPathArm64 = "zapret2-v{V}/binaries/android-arm64/nfqws2",
      binaryPathArm32 = "zapret2-v{V}/binaries/android-arm/nfqws2",
    ),
    ToolReleaseSpec(
      componentId = "byedpi",
      installName = "byedpi",
      repo = "hufrea/byedpi",
      assetRegexArm64 = "^byedpi-($NUM)-aarch64\\.tar\\.gz$",
      assetRegexArm32 = "^byedpi-($NUM)-armv7l\\.tar\\.gz$",
      archiveKind = ToolArchiveKind.TARGZ,
      binaryPathArm64 = "ciadpi-aarch64",
      binaryPathArm32 = "ciadpi-armv7l",
      versionFromTag = true,
    ),
    ToolReleaseSpec(
      componentId = "sing-box",
      installName = "sing-box",
      repo = "SagerNet/sing-box",
      assetRegexArm64 = "^sing-box-([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?)-android-arm64\\.tar\\.gz$",
      assetRegexArm32 = "^sing-box-([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?)-android-arm\\.tar\\.gz$",
      archiveKind = ToolArchiveKind.TARGZ,
      binaryPathArm64 = "sing-box-{V}-android-arm64/sing-box",
      binaryPathArm32 = "sing-box-{V}-android-arm/sing-box",
    ),
    ToolReleaseSpec(
      componentId = "mihomo",
      installName = "mihomo",
      repo = "MetaCubeX/mihomo",
      assetRegexArm64 = "^mihomo-android-arm64-v8-v([0-9]+\\.[0-9]+\\.[0-9]+)\\.gz$",
      assetRegexArm32 = "^mihomo-android-armv7-v([0-9]+\\.[0-9]+\\.[0-9]+)\\.gz$",
      archiveKind = ToolArchiveKind.GZ,
      binaryPathArm64 = "",
      binaryPathArm32 = "",
    ),
    ToolReleaseSpec(
      componentId = "hysteria2",
      installName = "hysteria2",
      repo = "apernet/hysteria",
      assetRegexArm64 = "^hysteria-android-arm64$",
      assetRegexArm32 = "^hysteria-android-armv7$",
      archiveKind = ToolArchiveKind.RAW,
      binaryPathArm64 = "",
      binaryPathArm32 = "",
      versionFromTag = true,
      versionTagStrip = "app/",
    ),
    ToolReleaseSpec(
      componentId = "dnscrypt",
      installName = "dnscrypt",
      repo = "DNSCrypt/dnscrypt-proxy",
      assetRegexArm64 = "^dnscrypt-proxy-android_arm64-([0-9]+\\.[0-9]+\\.[0-9]+)\\.zip$",
      assetRegexArm32 = "^dnscrypt-proxy-android_arm-([0-9]+\\.[0-9]+\\.[0-9]+)\\.zip$",
      archiveKind = ToolArchiveKind.ZIP,
      binaryPathArm64 = "android-arm64/dnscrypt-proxy",
      binaryPathArm32 = "android-arm/dnscrypt-proxy",
    ),
    ToolReleaseSpec(
      componentId = "operaproxy",
      installName = "opera-proxy",
      repo = "Alexey71/opera-proxy",
      assetRegexArm64 = "^opera-proxy\\.android-arm64$",
      assetRegexArm32 = "^opera-proxy\\.android-arm$",
      archiveKind = ToolArchiveKind.RAW,
      binaryPathArm64 = "",
      binaryPathArm32 = "",
      versionFromTag = true,
    ),
    ToolReleaseSpec(
      // Upstream publishes only arm64 Android binaries for mieru.
      componentId = "mieru",
      installName = "mieru",
      repo = "enfein/mieru",
      assetRegexArm64 = "^mieru_($NUM)_android_arm64\\.tar\\.gz$",
      assetRegexArm32 = "",
      archiveKind = ToolArchiveKind.TARGZ,
      binaryPathArm64 = "mieru",
      binaryPathArm32 = "",
    ),
  )

  fun forComponent(componentId: String, abi: String): ToolReleaseSpec? {
    val spec = specs.firstOrNull { it.componentId == componentId } ?: return null
    if (spec.assetRegex(abi).isEmpty()) return null
    return spec
  }

  /** Tools that have an upstream source for this device's ABI. */
  fun eligibleIds(abi: String?): Set<String> =
    if (abi == null) emptySet() else specs.filter { it.assetRegex(abi).isNotEmpty() }.map { it.componentId }.toSet()
}
