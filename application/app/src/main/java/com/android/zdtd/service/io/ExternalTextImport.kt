package com.android.zdtd.service.io

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/**
 * Canonicalizes text selected through Android's document picker before it is
 * sent to zdtd. Internal text files are always UTF-8 with LF line endings and
 * no BOM. The daemon repeats the same normalization as a second safety layer.
 */
object ExternalTextImport {
  private const val DEFAULT_LIMIT_BYTES = 16 * 1024 * 1024

  fun readText(context: Context, uri: Uri, maxBytes: Int = DEFAULT_LIMIT_BYTES): Result<String> = runCatching {
    val bytes = readBytes(context, uri, maxBytes).getOrThrow()
    decode(bytes)
  }

  fun readBytes(context: Context, uri: Uri, maxBytes: Int = DEFAULT_LIMIT_BYTES): Result<ByteArray> = runCatching {
    require(maxBytes > 0) { "maxBytes must be positive" }
    context.contentResolver.openInputStream(uri)?.use { input ->
      val out = ByteArrayOutputStream(minOf(64 * 1024, maxBytes))
      val buffer = ByteArray(32 * 1024)
      var total = 0
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "selected file is too large" }
        out.write(buffer, 0, read)
      }
      out.toByteArray()
    } ?: error("cannot open selected file")
  }

  fun writeUtf8Temp(context: Context, prefix: String, suffix: String, text: String): Result<File> = runCatching {
    val file = File.createTempFile(prefix, suffix, context.cacheDir)
    try {
      file.writeText(normalize(text), Charsets.UTF_8)
      file
    } catch (t: Throwable) {
      runCatching { file.delete() }
      throw t
    }
  }

  fun decode(bytes: ByteArray): String {
    val text = when {
      bytes.startsWith(0xEF, 0xBB, 0xBF) -> decodeStrict(bytes, 3, Charsets.UTF_8)
      bytes.startsWith(0xFF, 0xFE) -> decodeStrict(bytes, 2, Charsets.UTF_16LE)
      bytes.startsWith(0xFE, 0xFF) -> decodeStrict(bytes, 2, Charsets.UTF_16BE)
      else -> {
        decodeStrictOrNull(bytes, 0, Charsets.UTF_8)
          ?: detectUtf16WithoutBom(bytes)?.let { decodeStrict(bytes, 0, it) }
          ?: decodeStrict(bytes, 0, Charset.forName("windows-1251"))
      }
    }
    return normalize(text)
  }

  fun normalize(input: String): String {
    var text = input.trimStart('\uFEFF')
    require(!text.contains('\u0000')) { "text contains NUL characters" }
    text = text.replace("\r\n", "\n").replace('\r', '\n')
    require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) {
      "text contains unsupported control characters"
    }
    return text
  }

  private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String =
    decodeStrictOrNull(bytes, offset, charset) ?: throw CharacterCodingException()

  private fun decodeStrictOrNull(bytes: ByteArray, offset: Int, charset: Charset): String? = runCatching {
    charset.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
      .toString()
  }.getOrNull()

  private fun detectUtf16WithoutBom(bytes: ByteArray): Charset? {
    if (bytes.size < 8 || bytes.size % 2 != 0) return null
    val pairs = bytes.size / 2
    var zeroEven = 0
    var zeroOdd = 0
    var i = 0
    while (i < bytes.size) {
      if (bytes[i].toInt() == 0) zeroEven++
      if (bytes[i + 1].toInt() == 0) zeroOdd++
      i += 2
    }
    return when {
      zeroOdd * 3 >= pairs && zeroEven * 10 < pairs -> Charsets.UTF_16LE
      zeroEven * 3 >= pairs && zeroOdd * 10 < pairs -> Charsets.UTF_16BE
      else -> null
    }
  }

  private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
  }
}
