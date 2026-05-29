package com.android.zdtd.service.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import com.android.zdtd.service.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal object ZdtdWidgetHudRenderer {
  // 02_08: Control Pro reflow with clean ZDT-D header, process count, large action block, CPU/RAM side column.
  private const val CONTROL_FALLBACK_W_DP = 380
  private const val CONTROL_FALLBACK_H_DP = 224
  private const val MINI_FALLBACK_W_DP = 320
  private const val MINI_FALLBACK_H_DP = 142

  fun renderControlPro(context: Context, snapshot: ZdtdWidgetSnapshot, options: Bundle?): Bitmap {
    val size = resolveSize(context, options, CONTROL_FALLBACK_W_DP, CONTROL_FALLBACK_H_DP)
    return drawBitmap(size.width, size.height) { canvas, paint ->
      drawControlPro(canvas, paint, context, snapshot, size.width.toFloat(), size.height.toFloat())
    }
  }

  fun renderMiniDashboard(context: Context, snapshot: ZdtdWidgetSnapshot, options: Bundle?): Bitmap {
    val size = resolveSize(context, options, MINI_FALLBACK_W_DP, MINI_FALLBACK_H_DP)
    return drawBitmap(size.width, size.height) { canvas, paint ->
      val aspect = size.width.toFloat() / size.height.toFloat().coerceAtLeast(1f)
      if (aspect > 3.05f || size.height < size.width * 0.36f) {
        drawMiniStrip(canvas, paint, context, snapshot, size.width.toFloat(), size.height.toFloat())
      } else {
        drawMiniFull(canvas, paint, context, snapshot, size.width.toFloat(), size.height.toFloat())
      }
    }
  }

  private data class RenderSize(val width: Int, val height: Int)

  private fun resolveSize(context: Context, options: Bundle?, fallbackWdp: Int, fallbackHdp: Int): RenderSize {
    val density = context.resources.displayMetrics.density
    val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallbackWdp) ?: fallbackWdp
    val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW) ?: minW
    val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallbackHdp) ?: fallbackHdp
    val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH) ?: minH
    val widthDp = max(maxW, minW).coerceAtLeast(fallbackWdp)
    val heightDp = max(maxH, minH).coerceAtLeast(fallbackHdp)
    val scale = density.coerceIn(1.5f, 2.15f)
    val width = (widthDp * scale).roundToInt().coerceIn(620, 1100)
    val height = (heightDp * scale).roundToInt().coerceIn(300, 660)
    return RenderSize(width, height)
  }

  private inline fun drawBitmap(width: Int, height: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    canvas.drawColor(Color.TRANSPARENT)
    draw(canvas, paint)
    return bitmap
  }

  private fun drawControlPro(canvas: Canvas, paint: Paint, context: Context, snapshot: ZdtdWidgetSnapshot, w: Float, h: Float) {
    val running = snapshot.serviceOn
    val blocked = snapshot.testerActive && !running
    val statusTitle = when {
      blocked -> context.getString(R.string.widget_status_tester_active)
      snapshot.unavailable -> context.getString(R.string.widget_status_unknown)
      running -> context.getString(R.string.widget_status_running)
      else -> context.getString(R.string.widget_status_stopped)
    }
    val statusSubtitle = when {
      blocked -> context.getString(R.string.widget_status_tester_active_subtitle)
      snapshot.unavailable -> context.getString(R.string.widget_status_unknown_subtitle)
      running -> context.getString(R.string.widget_status_running_subtitle)
      else -> context.getString(R.string.widget_status_stopped_subtitle)
    }
    val actionTitle = context.getString(if (running) R.string.widget_action_stop else R.string.widget_action_start)
    val actionSubtitle = context.getString(if (running) R.string.widget_action_stop_hint else R.string.widget_action_start_hint)

    val m = min(w, h) * 0.035f
    val headerH = h * 0.235f
    val top = m
    val panel = RectF(m, top, w - m, h - m)
    drawPanel(canvas, paint, panel, cut = min(w, h) * 0.042f, fillAlpha = 238, stroke = 0xEFFF2358.toInt(), strokeW = h * 0.0048f)
    drawGlassSweep(canvas, paint, panel)
    drawTechTicks(canvas, paint, panel)

    // Clean header: logo + ZDT-D on the left, processes on the right.
    val header = RectF(panel.left + w * 0.020f, panel.top + h * 0.020f, panel.right - w * 0.020f, panel.top + headerH * 0.94f)
    drawHeaderStrip(canvas, paint, header)
    val logoR = header.height() * 0.38f
    val logoCx = header.left + logoR + w * 0.025f
    val logoCy = header.centerY()
    drawReactorLogo(canvas, paint, context, logoCx, logoCy, logoR * 0.94f, strong = true)

    val dividerX = header.left + header.width() * 0.665f
    drawVerticalHudDivider(canvas, paint, dividerX, header.top + header.height() * 0.18f, header.bottom - header.height() * 0.18f, header.height())

    val titleX = logoCx + logoR + w * 0.035f
    drawText(canvas, paint, "ZDT-D", titleX, header.top + header.height() * 0.62f, h * 0.083f, 0xFFF8F4F6.toInt(), bold = true, condensed = true, maxWidth = dividerX - titleX - w * 0.030f)
    drawHudLine(canvas, paint, titleX, header.top + header.height() * 0.76f, dividerX - w * 0.045f, header.top + header.height() * 0.76f, alpha = 46)

    val procText = "ПРОЦЕССЫ: ${snapshot.processes}"
    drawText(canvas, paint, procText, header.right - header.width() * 0.045f, header.top + header.height() * 0.60f, h * 0.047f, 0xFFF8F1F4.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = header.width() * 0.285f)
    drawHudLine(canvas, paint, dividerX + header.width() * 0.040f, header.top + header.height() * 0.76f, header.right - header.width() * 0.045f, header.top + header.height() * 0.76f, alpha = 54)

    val mainTop = header.bottom + h * 0.034f
    val mainBottom = panel.bottom - h * 0.030f
    val main = RectF(panel.left + w * 0.028f, mainTop, panel.right - w * 0.028f, mainBottom)
    val gap = w * 0.022f
    val rightW = main.width() * 0.292f
    val action = RectF(main.left, main.top, main.right - rightW - gap, main.bottom)
    val side = RectF(action.right + gap, main.top, main.right, main.bottom)
    val sideGap = h * 0.020f
    val sideH = (side.height() - sideGap) / 2f
    val cpu = RectF(side.left, side.top, side.right, side.top + sideH)
    val ram = RectF(side.left, cpu.bottom + sideGap, side.right, side.bottom)

    drawControlActionPanel(canvas, paint, action, actionTitle, actionSubtitle, statusTitle, statusSubtitle, running, h)
    drawControlSideMetric(canvas, paint, cpu, "CPU", formatCpu(snapshot.cpuPercent), h)
    drawControlSideMetric(canvas, paint, ram, "RAM", formatRam(snapshot.ramMb), h)
  }

  private fun drawControlActionPanel(
    canvas: Canvas,
    paint: Paint,
    r: RectF,
    actionTitle: String,
    actionSubtitle: String,
    statusTitle: String,
    statusSubtitle: String,
    running: Boolean,
    widgetH: Float,
  ) {
    drawInnerLinePanel(canvas, paint, r, cut = r.height() * 0.105f, alpha = if (running) 28 else 9, strokeAlpha = if (running) 178 else 154)
    drawCornerTicks(canvas, paint, r, alpha = if (running) 128 else 92)

    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.shader = LinearGradient(
      r.left,
      r.top,
      r.right,
      r.bottom,
      intArrayOf(
        Color.argb(if (running) 58 else 22, 255, 35, 88),
        Color.argb(10, 255, 35, 88),
        Color.argb(0, 0, 0, 0),
      ),
      floatArrayOf(0f, 0.46f, 1f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawPath(beveledPath(r, r.height() * 0.105f), paint)
    paint.shader = null

    val powerR = min(r.width(), r.height()) * 0.155f
    val powerCx = r.left + r.width() * 0.175f
    val powerCy = r.centerY() - r.height() * 0.020f
    drawPowerButton(canvas, paint, powerCx, powerCy, powerR, running)

    val textX = r.left + r.width() * 0.335f
    val titleY = r.top + r.height() * 0.385f
    drawText(canvas, paint, actionTitle, textX, titleY, widgetH * 0.074f, 0xFFFFFFFF.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.58f)
    drawText(canvas, paint, statusTitle, textX, r.top + r.height() * 0.585f, widgetH * 0.049f, 0xFFFF365F.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.58f)
    drawText(canvas, paint, actionSubtitle.lowercase(), textX, r.top + r.height() * 0.745f, widgetH * 0.034f, 0xBFFFF1F5.toInt(), bold = false, condensed = false, maxWidth = r.width() * 0.58f)
    drawText(canvas, paint, statusSubtitle, textX, r.top + r.height() * 0.865f, widgetH * 0.030f, 0x9EFFF1F5.toInt(), bold = false, condensed = false, maxWidth = r.width() * 0.58f)

    drawHudLine(canvas, paint, r.left + r.width() * 0.070f, r.top + r.height() * 0.175f, r.right - r.width() * 0.070f, r.top + r.height() * 0.175f, alpha = 64)
    drawHudLine(canvas, paint, r.left + r.width() * 0.070f, r.bottom - r.height() * 0.145f, r.right - r.width() * 0.070f, r.bottom - r.height() * 0.145f, alpha = 54)
    drawAccentSlash(canvas, paint, r.right - r.width() * 0.105f, r.bottom - r.height() * 0.215f, r.height() * 0.045f)
  }

  private fun drawControlSideMetric(canvas: Canvas, paint: Paint, r: RectF, label: String, value: String, widgetH: Float) {
    drawInnerLinePanel(canvas, paint, r, cut = r.height() * 0.145f, alpha = 8, strokeAlpha = 142)
    drawCornerTicks(canvas, paint, r, alpha = 82)
    drawText(canvas, paint, label, r.left + r.width() * 0.125f, r.top + r.height() * 0.330f, widgetH * 0.043f, 0xFFFF4F7E.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.55f)
    drawText(canvas, paint, value, r.left + r.width() * 0.125f, r.top + r.height() * 0.705f, widgetH * 0.065f, 0xFFFFFFFF.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.78f)
    drawHudLine(canvas, paint, r.left + r.width() * 0.125f, r.top + r.height() * 0.455f, r.right - r.width() * 0.125f, r.top + r.height() * 0.455f, alpha = 52)
    drawHudLine(canvas, paint, r.left + r.width() * 0.125f, r.bottom - r.height() * 0.180f, r.right - r.width() * 0.125f, r.bottom - r.height() * 0.180f, alpha = 38)
  }

  private fun drawVerticalHudDivider(canvas: Canvas, paint: Paint, x: Float, top: Float, bottom: Float, refH: Float) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = max(1.0f, refH * 0.018f)
    paint.color = Color.argb(160, 255, 35, 88)
    paint.maskFilter = BlurMaskFilter(refH * 0.028f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawLine(x, top, x, bottom, paint)
    paint.maskFilter = null
    paint.strokeWidth = max(0.7f, refH * 0.006f)
    paint.color = Color.argb(100, 255, 105, 142)
    canvas.drawLine(x, top, x, bottom, paint)
  }

  private fun drawMiniFull(canvas: Canvas, paint: Paint, context: Context, snapshot: ZdtdWidgetSnapshot, w: Float, h: Float) {
    val running = snapshot.serviceOn
    val blocked = snapshot.testerActive && !running
    val state = when {
      blocked -> context.getString(R.string.widget_status_tester_active_upper)
      snapshot.unavailable -> context.getString(R.string.widget_status_unknown_upper)
      running -> context.getString(R.string.widget_status_working_upper)
      else -> context.getString(R.string.widget_status_stopped_upper)
    }
    val actionTitle = context.getString(if (running) R.string.widget_action_stop else R.string.widget_action_start)
    val pulsePhase = runningPulsePhase(running)

    val m = min(w, h) * 0.040f
    val panel = RectF(m, m, w - m, h - m)
    drawPanel(canvas, paint, panel, cut = min(w, h) * 0.050f, fillAlpha = 246, stroke = 0xE8FF2358.toInt(), strokeW = h * 0.0049f)
    drawGlassSweep(canvas, paint, panel)
    drawTechTicks(canvas, paint, panel)
    drawDoubleHudFrame(canvas, paint, panel, panel.height() * 0.046f, alpha = 78)

    // Compact HUD header: logo + title + state fact only.
    val header = RectF(panel.left + panel.width() * 0.030f, panel.top + panel.height() * 0.035f, panel.right - panel.width() * 0.030f, panel.top + panel.height() * 0.255f)
    drawHeaderStrip(canvas, paint, header)
    val logoR = header.height() * 0.405f
    val logoCx = header.left + logoR + header.width() * 0.026f
    val logoCy = header.centerY()
    drawReactorLogo(canvas, paint, context, logoCx, logoCy, logoR, strong = true)
    val titleX = logoCx + logoR + header.width() * 0.034f
    drawText(canvas, paint, "ZDT-D", titleX, header.top + header.height() * 0.61f, h * 0.090f, 0xFFF8F4F6.toInt(), bold = true, condensed = true, maxWidth = header.width() * 0.33f)
    val stateW = header.width() * 0.34f
    val stateRight = header.right - header.width() * 0.040f
    drawText(canvas, paint, state, stateRight, header.top + header.height() * 0.60f, h * 0.051f, 0xFFFF365F.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = stateW * 1.08f)
    drawHudLine(canvas, paint, titleX + header.width() * 0.24f, header.top + header.height() * 0.35f, stateRight - stateW - header.width() * 0.035f, header.top + header.height() * 0.35f, alpha = 40)
    drawHudLine(canvas, paint, titleX + header.width() * 0.18f, header.top + header.height() * 0.67f, stateRight - stateW - header.width() * 0.075f, header.top + header.height() * 0.67f, alpha = 28)

    val main = RectF(panel.left + panel.width() * 0.046f, header.bottom + panel.height() * 0.050f, panel.right - panel.width() * 0.046f, panel.bottom - panel.height() * 0.058f)
    val gap = main.width() * 0.028f
    val actionW = main.width() * 0.292f
    val actionArea = RectF(main.left, main.top, main.left + actionW, main.bottom)
    val graphs = RectF(actionArea.right + gap, main.top, main.right, main.bottom)

    drawActionButtonPanel(canvas, paint, actionArea, actionTitle, running, h, pulsePhase)
    drawActivityGraphPanel(canvas, paint, graphs, snapshot, h, pulsePhase)
  }

  private fun drawMiniStrip(canvas: Canvas, paint: Paint, context: Context, snapshot: ZdtdWidgetSnapshot, w: Float, h: Float) {
    val running = snapshot.serviceOn
    val blocked = snapshot.testerActive && !running
    val state = when {
      blocked -> context.getString(R.string.widget_status_tester_active_upper)
      snapshot.unavailable -> context.getString(R.string.widget_status_unknown_upper)
      running -> context.getString(R.string.widget_status_working_upper)
      else -> context.getString(R.string.widget_status_stopped_upper)
    }
    val actionTitle = context.getString(if (running) R.string.widget_action_stop else R.string.widget_action_start)
    val pulsePhase = runningPulsePhase(running)
    val m = min(w, h) * 0.060f
    val panel = RectF(m, m, w - m, h - m)
    drawPanel(canvas, paint, panel, cut = min(w, h) * 0.090f, fillAlpha = 246, stroke = 0xE8FF2358.toInt(), strokeW = h * 0.0058f)
    drawGlassSweep(canvas, paint, panel)
    drawTechTicks(canvas, paint, panel)
    drawDoubleHudFrame(canvas, paint, panel, panel.height() * 0.060f, alpha = 70)

    val header = RectF(panel.left + panel.width() * 0.040f, panel.top + panel.height() * 0.085f, panel.right - panel.width() * 0.040f, panel.top + panel.height() * 0.390f)
    drawHeaderStrip(canvas, paint, header, compact = true)
    val logoR = header.height() * 0.40f
    val logoCx = header.left + logoR + header.width() * 0.020f
    val logoCy = header.centerY()
    drawReactorLogo(canvas, paint, context, logoCx, logoCy, logoR, strong = false)
    val titleX = logoCx + logoR + header.width() * 0.030f
    drawText(canvas, paint, "ZDT-D", titleX, header.top + header.height() * 0.58f, h * 0.100f, 0xFFF8F4F6.toInt(), bold = true, condensed = true, maxWidth = header.width() * 0.24f)
    drawText(canvas, paint, state, header.right - header.width() * 0.030f, header.top + header.height() * 0.58f, h * 0.056f, 0xFFFF365F.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = header.width() * 0.30f)

    val main = RectF(panel.left + panel.width() * 0.045f, panel.top + panel.height() * 0.455f, panel.right - panel.width() * 0.045f, panel.bottom - panel.height() * 0.085f)
    val actionW = main.width() * 0.30f
    val gap = main.width() * 0.025f
    val actionArea = RectF(main.left, main.top, main.left + actionW, main.bottom)
    val graph = RectF(actionArea.right + gap, main.top, main.right, main.bottom)
    drawActionButtonPanel(canvas, paint, actionArea, actionTitle, running, h, pulsePhase, compact = true)
    drawActivityGraphPanel(canvas, paint, graph, snapshot, h, pulsePhase, compact = true)
  }

  private fun drawActionButtonPanel(
    canvas: Canvas,
    paint: Paint,
    r: RectF,
    text: String,
    running: Boolean,
    widgetH: Float,
    phase: Float,
    compact: Boolean = false,
  ) {
    val cut = r.height() * if (compact) 0.16f else 0.17f
    drawInnerLinePanel(canvas, paint, r, cut = cut, alpha = if (running) 22 else 7, strokeAlpha = if (running) 172 else 148)
    drawCornerTicks(canvas, paint, r, alpha = if (running) 132 else 94)
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.shader = LinearGradient(
      r.left,
      r.top,
      r.right,
      r.bottom,
      intArrayOf(Color.argb(if (running) 48 else 20, 255, 35, 88), Color.argb(7, 255, 35, 88), Color.argb(0, 0, 0, 0)),
      floatArrayOf(0f, 0.45f, 1f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawPath(beveledPath(r, cut), paint)
    paint.shader = null

    val iconR = min(r.width(), r.height()) * (if (compact) 0.335f else 0.365f)
    val iconCy = r.top + r.height() * (if (compact) 0.405f else 0.390f)
    drawActionOrb(canvas, paint, r.centerX(), iconCy, iconR, running, phase)

    drawText(
      canvas,
      paint,
      text,
      r.centerX(),
      r.bottom - r.height() * (if (compact) 0.13f else 0.12f),
      widgetH * (if (compact) 0.056f else 0.052f),
      0xF8FFF1F5.toInt(),
      bold = true,
      condensed = true,
      align = Paint.Align.CENTER,
      maxWidth = r.width() * 0.80f,
    )

    drawHudLine(canvas, paint, r.left + r.width() * 0.13f, r.top + r.height() * 0.14f, r.right - r.width() * 0.13f, r.top + r.height() * 0.14f, alpha = 74)
    drawHudLine(canvas, paint, r.left + r.width() * 0.13f, r.bottom - r.height() * 0.23f, r.right - r.width() * 0.13f, r.bottom - r.height() * 0.23f, alpha = 68)
  }

  private fun drawActionOrb(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, running: Boolean, phase: Float) {
    val active = running
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.shader = RadialGradient(
      cx,
      cy,
      radius * 1.18f,
      intArrayOf(
        Color.argb(if (active) 140 else 48, 255, 54, 95),
        Color.argb(if (active) 52 else 22, 255, 35, 88),
        Color.argb(0, 255, 35, 88),
      ),
      floatArrayOf(0.0f, 0.55f, 1.0f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(cx, cy, radius * 1.30f, paint)
    paint.shader = null

    paint.style = Paint.Style.FILL
    paint.color = Color.argb(if (active) 220 else 196, 10, 1, 6)
    canvas.drawCircle(cx, cy, radius * 1.02f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = radius * 0.055f
    paint.color = Color.argb(if (active) 245 else 150, 255, 54, 95)
    canvas.drawCircle(cx, cy, radius * 1.02f, paint)
    paint.strokeWidth = radius * 0.025f
    paint.color = Color.argb(if (active) 120 else 70, 255, 245, 248)
    canvas.drawCircle(cx, cy, radius * 0.68f, paint)

    val arc = RectF(cx - radius * 0.88f, cy - radius * 0.88f, cx + radius * 0.88f, cy + radius * 0.88f)
    canvas.save()
    canvas.rotate(phase, cx, cy)
    paint.strokeWidth = radius * 0.095f
    paint.color = Color.argb(if (active) 230 else 95, 255, 54, 95)
    paint.maskFilter = if (active) BlurMaskFilter(radius * 0.08f, BlurMaskFilter.Blur.NORMAL) else null
    canvas.drawArc(arc, -92f, 42f, false, paint)
    canvas.drawArc(arc, 22f, 64f, false, paint)
    canvas.drawArc(arc, 154f, 58f, false, paint)
    paint.maskFilter = null
    paint.strokeWidth = radius * 0.035f
    paint.color = Color.argb(if (active) 185 else 80, 255, 235, 240)
    canvas.drawArc(RectF(cx - radius * 0.52f, cy - radius * 0.52f, cx + radius * 0.52f, cy + radius * 0.52f), 205f, 80f, false, paint)
    canvas.restore()

    // Small moving hotspots emulate a slowly rotating live icon in RemoteViews bitmap updates.
    if (active) {
      paint.resetForShape()
      paint.style = Paint.Style.FILL
      paint.color = 0xFFFFE8EE.toInt()
      paint.maskFilter = BlurMaskFilter(radius * 0.10f, BlurMaskFilter.Blur.NORMAL)
      for (i in 0 until 3) {
        val a = Math.toRadians((phase + i * 118f).toDouble())
        val px = cx + cos(a).toFloat() * radius * 0.78f
        val py = cy + sin(a).toFloat() * radius * 0.78f
        canvas.drawCircle(px, py, radius * 0.035f, paint)
      }
      paint.maskFilter = null
    }

    drawPowerGlyph(canvas, paint, cx, cy, radius * 0.42f, if (active) 0xFFFFF7FA.toInt() else 0xCCFFF1F5.toInt())
  }

  private fun drawPowerGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, size: Float, color: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = size * 0.16f
    paint.color = color
    val r = RectF(cx - size * 0.70f, cy - size * 0.56f, cx + size * 0.70f, cy + size * 0.84f)
    canvas.drawArc(r, 126f, 288f, false, paint)
    canvas.drawLine(cx, cy - size * 0.88f, cx, cy - size * 0.12f, paint)
  }

  private fun drawActivityGraphPanel(
    canvas: Canvas,
    paint: Paint,
    r: RectF,
    snapshot: ZdtdWidgetSnapshot,
    widgetH: Float,
    phase: Float,
    compact: Boolean = false,
  ) {
    val cut = r.height() * if (compact) 0.15f else 0.16f
    drawInnerLinePanel(canvas, paint, r, cut = cut, alpha = 7, strokeAlpha = 152)
    drawCornerTicks(canvas, paint, r, alpha = 94)

    val padX = r.width() * if (compact) 0.050f else 0.055f
    val headerY = r.top + r.height() * if (compact) 0.235f else 0.215f
    val titleSize = widgetH * if (compact) 0.043f else 0.037f
    drawText(canvas, paint, "АКТИВНОСТЬ", r.left + padX, headerY, titleSize, 0xFFFF4F7E.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.42f)
    drawText(canvas, paint, "PROC: ${snapshot.processes}", r.right - padX, headerY, titleSize * 0.92f, 0xFFF8F1F4.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = r.width() * 0.32f)
    drawHudLine(canvas, paint, r.left + padX, r.top + r.height() * if (compact) 0.305f else 0.292f, r.right - padX, r.top + r.height() * if (compact) 0.305f else 0.292f, alpha = 70)

    val active = snapshot.serviceOn || snapshot.testerActive
    val cpuNorm = if (active) (snapshot.cpuPercent.toFloat() / 50f).coerceIn(0.06f, 0.92f) else 0.055f
    val ramNorm = (snapshot.ramMb.toFloat() / 96f).coerceIn(if (active) 0.12f else 0.10f, if (active) 0.92f else 0.44f)

    val lanesTop = r.top + r.height() * if (compact) 0.375f else 0.365f
    val lanesBottom = r.bottom - r.height() * if (compact) 0.100f else 0.105f
    val laneGap = r.height() * if (compact) 0.095f else 0.105f
    val laneH = (lanesBottom - lanesTop - laneGap) / 2f

    drawMeterLane(
      canvas,
      paint,
      row = RectF(r.left + padX, lanesTop, r.right - padX, lanesTop + laneH),
      label = "CPU",
      value = formatCpu(snapshot.cpuPercent),
      level = cpuNorm,
      phase = phase,
      widgetH = widgetH,
      active = active,
      compact = compact,
    )
    val ramTop = lanesTop + laneH + laneGap
    drawMeterLane(
      canvas,
      paint,
      row = RectF(r.left + padX, ramTop, r.right - padX, ramTop + laneH),
      label = "RAM",
      value = formatRam(snapshot.ramMb),
      level = ramNorm,
      phase = phase + 61f,
      widgetH = widgetH,
      active = active,
      compact = compact,
    )
  }

  private fun drawMeterLane(
    canvas: Canvas,
    paint: Paint,
    row: RectF,
    label: String,
    value: String,
    level: Float,
    phase: Float,
    widgetH: Float,
    active: Boolean,
    compact: Boolean,
  ) {
    val labelSize = widgetH * if (compact) 0.041f else 0.036f
    val valueSize = widgetH * if (compact) 0.042f else 0.037f
    val labelW = row.width() * if (compact) 0.16f else 0.135f
    val valueW = row.width() * if (compact) 0.22f else 0.185f
    val meter = RectF(row.left + labelW, row.top + row.height() * 0.10f, row.right - valueW, row.bottom - row.height() * 0.10f)

    drawText(canvas, paint, label, row.left, row.top + row.height() * 0.63f, labelSize, 0xEFFFF1F5.toInt(), bold = true, condensed = true, maxWidth = labelW * 0.88f)
    drawText(canvas, paint, value, row.right, row.top + row.height() * 0.63f, valueSize, 0xFFFF365F.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = valueW * 0.96f)
    drawHudMeter(canvas, paint, meter, level, phase, active)
  }

  private fun drawHudMeter(canvas: Canvas, paint: Paint, r: RectF, level: Float, phase: Float, active: Boolean) {
    val cut = r.height() * 0.16f
    val bg = beveledPath(r, cut)
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(120, 8, 2, 6)
    canvas.drawPath(bg, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(0.8f, r.height() * 0.040f)
    paint.color = Color.argb(64, 255, 54, 95)
    canvas.drawPath(bg, paint)

    val segments = 14
    val gap = r.width() * 0.012f
    val segW = (r.width() - gap * (segments - 1)) / segments
    val base = r.bottom - r.height() * 0.25f
    val minH = r.height() * 0.16f
    val maxH = r.height() * 0.64f
    for (i in 0 until segments) {
      val x = r.left + i * (segW + gap)
      val wave = (sin(Math.toRadians((phase * 0.42f + i * 37f).toDouble())).toFloat() * 0.10f) +
        (cos(Math.toRadians((phase * 0.25f + i * 53f).toDouble())).toFloat() * 0.06f)
      val v = (level + wave).coerceIn(0.04f, 0.98f)
      val h = minH + maxH * v
      val bar = RectF(x, base - h, x + segW, base)

      paint.resetForShape()
      paint.style = Paint.Style.FILL
      paint.color = Color.argb(34, 255, 54, 95)
      canvas.drawRect(x, base - maxH, x + segW, base, paint)
      paint.color = Color.argb(if (active) (82 + v * 138).roundToInt() else (58 + v * 82).roundToInt(), 255, 54, 95)
      canvas.drawRect(bar, paint)
      if (active && v > 0.35f) {
        paint.maskFilter = BlurMaskFilter(r.height() * 0.045f, BlurMaskFilter.Blur.NORMAL)
        paint.color = Color.argb(36, 255, 54, 95)
        canvas.drawRect(bar, paint)
        paint.maskFilter = null
      }
    }

    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = max(1f, r.height() * 0.030f)
    paint.color = Color.argb(if (active) 138 else 78, 255, 120, 150)
    canvas.drawLine(r.left + r.width() * 0.035f, base + r.height() * 0.10f, r.right - r.width() * 0.035f, base + r.height() * 0.10f, paint)
  }

  private fun drawLiveGraphLane(
    canvas: Canvas,
    paint: Paint,
    label: String,
    value: String,
    row: RectF,
    graph: RectF,
    level: Float,
    phase: Float,
    widgetH: Float,
    active: Boolean,
    compact: Boolean,
  ) {
    drawText(canvas, paint, label, row.left, row.top + row.height() * 0.62f, widgetH * if (compact) 0.041f else 0.035f, 0xEFFFF1F5.toInt(), bold = true, condensed = true, maxWidth = row.width() * 0.14f)
    drawText(canvas, paint, value, row.right, row.top + row.height() * 0.62f, widgetH * if (compact) 0.043f else 0.037f, 0xFFFF365F.toInt(), bold = true, condensed = true, align = Paint.Align.RIGHT, maxWidth = row.width() * 0.20f)
    drawSegmentGraph(canvas, paint, graph, level, phase, active)
  }

  private fun drawSegmentGraph(canvas: Canvas, paint: Paint, r: RectF, level: Float, phase: Float, active: Boolean) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(0.8f, r.height() * 0.030f)
    paint.color = Color.argb(30, 255, 54, 95)
    for (i in 0..3) {
      val y = r.top + r.height() * (0.18f + i * 0.22f)
      canvas.drawLine(r.left, y, r.right, y, paint)
    }

    val segments = 18
    val gap = r.width() * 0.012f
    val segW = (r.width() - gap * (segments - 1)) / segments
    val baseY = r.bottom - r.height() * 0.18f
    for (i in 0 until segments) {
      val x = r.left + i * (segW + gap)
      val v = segmentLevel(level, phase, i)
      val top = r.bottom - r.height() * (0.20f + v * 0.66f)
      val a = if (active) (80 + (v * 140)).roundToInt() else (42 + (v * 70)).roundToInt()
      paint.resetForShape()
      paint.style = Paint.Style.FILL
      paint.color = Color.argb(34, 255, 54, 95)
      canvas.drawRect(x, baseY, x + segW, r.bottom - r.height() * 0.12f, paint)
      paint.color = Color.argb(a, 255, 54, 95)
      val bar = RectF(x, top, x + segW, r.bottom - r.height() * 0.12f)
      canvas.drawRect(bar, paint)
      if (active && v > 0.45f) {
        paint.maskFilter = BlurMaskFilter(r.height() * 0.060f, BlurMaskFilter.Blur.NORMAL)
        paint.color = Color.argb(55, 255, 54, 95)
        canvas.drawRect(bar, paint)
        paint.maskFilter = null
      }
    }

    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = max(1.0f, r.height() * 0.045f)
    paint.color = Color.argb(if (active) 170 else 90, 255, 54, 95)
    var px = r.left + segW * 0.5f
    var py = r.bottom - r.height() * (0.20f + segmentLevel(level, phase, 0) * 0.66f)
    for (i in 1 until segments) {
      val x = r.left + i * (segW + gap) + segW * 0.5f
      val y = r.bottom - r.height() * (0.20f + segmentLevel(level, phase, i) * 0.66f)
      canvas.drawLine(px, py, x, py, paint)
      canvas.drawLine(x, py, x, y, paint)
      px = x
      py = y
    }
  }

  private fun segmentLevel(level: Float, phase: Float, index: Int): Float {
    val wave = sin(Math.toRadians((phase * 0.55f + index * 34f).toDouble())).toFloat() * 0.16f +
      cos(Math.toRadians((phase * 0.33f + index * 71f).toDouble())).toFloat() * 0.10f
    return (level + wave).coerceIn(0.05f, 0.95f)
  }

  private fun drawSteppedTrack(canvas: Canvas, paint: Paint, r: RectF, level: Float, phase: Float, alpha: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1f, r.height() * 0.035f)
    paint.color = Color.argb(34, 255, 54, 95)
    val gridCount = 4
    for (i in 0..gridCount) {
      val y = r.top + r.height() * i / gridCount
      canvas.drawLine(r.left, y, r.right, y, paint)
    }

    paint.strokeWidth = max(1.4f, r.height() * 0.075f)
    paint.strokeJoin = Paint.Join.MITER
    paint.strokeCap = Paint.Cap.SQUARE
    paint.color = Color.argb(alpha, 255, 54, 95)
    val steps = 11
    var px = r.left
    var py = stepY(r, level, phase, 0)
    for (i in 1..steps) {
      val x = r.left + r.width() * i / steps
      val y = stepY(r, level, phase, i)
      canvas.drawLine(px, py, x, py, paint)
      canvas.drawLine(x, py, x, y, paint)
      px = x
      py = y
    }
    paint.maskFilter = BlurMaskFilter(r.height() * 0.08f, BlurMaskFilter.Blur.NORMAL)
    paint.color = Color.argb(alpha / 3, 255, 54, 95)
    // subtle glow pass
    px = r.left
    py = stepY(r, level, phase, 0)
    for (i in 1..steps) {
      val x = r.left + r.width() * i / steps
      val y = stepY(r, level, phase, i)
      canvas.drawLine(px, py, x, py, paint)
      canvas.drawLine(x, py, x, y, paint)
      px = x
      py = y
    }
    paint.maskFilter = null
  }

  private fun stepY(r: RectF, level: Float, phase: Float, index: Int): Float {
    val wave = (sin(Math.toRadians((phase + index * 31f).toDouble())).toFloat() * 0.18f) +
      (cos(Math.toRadians((phase * 0.7f + index * 57f).toDouble())).toFloat() * 0.10f)
    val v = (level + wave).coerceIn(0.08f, 0.92f)
    return r.bottom - r.height() * v
  }

  private fun runningPulsePhase(running: Boolean): Float {
    return if (running) ((System.currentTimeMillis() / 180L) % 360L).toFloat() else 0f
  }

  private enum class IconKind { PROCESSES, CPU, RAM }

  private fun drawMetric(canvas: Canvas, paint: Paint, r: RectF, label: String, value: String, icon: IconKind, widgetH: Float) {
    drawInnerLinePanel(canvas, paint, r, cut = r.height() * 0.18f, alpha = 12, strokeAlpha = 145)
    drawIcon(canvas, paint, icon, r.left + r.width() * 0.18f, r.centerY(), r.height() * 0.22f, 0xFFFF365F.toInt())
    val x = r.left + r.width() * 0.42f
    drawText(canvas, paint, label, x, r.top + r.height() * 0.34f, widgetH * 0.035f, 0xFFFF4F7E.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.50f)
    drawText(canvas, paint, value, x, r.top + r.height() * 0.72f, widgetH * 0.068f, 0xFFFFFFFF.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.52f)
    drawHudLine(canvas, paint, r.left + r.width() * 0.10f, r.bottom - r.height() * 0.18f, r.right - r.width() * 0.10f, r.bottom - r.height() * 0.18f, alpha = 55)
  }

  private fun drawMiniMetric(canvas: Canvas, paint: Paint, r: RectF, label: String, value: String, icon: IconKind, widgetH: Float) {
    drawInnerLinePanel(canvas, paint, r, cut = r.height() * 0.15f, alpha = 10, strokeAlpha = 145)
    drawIcon(canvas, paint, icon, r.left + r.width() * 0.18f, r.centerY(), r.height() * 0.20f, 0xFFFF365F.toInt())
    val x = r.left + r.width() * 0.35f
    drawText(canvas, paint, label, x, r.top + r.height() * 0.38f, widgetH * 0.044f, 0xCFFFF1F5.toInt(), bold = false, condensed = true, maxWidth = r.width() * 0.58f)
    drawText(canvas, paint, value, x, r.top + r.height() * 0.72f, widgetH * 0.064f, 0xFFFF365F.toInt(), bold = true, condensed = true, maxWidth = r.width() * 0.58f)
  }

  private fun drawPanel(canvas: Canvas, paint: Paint, r: RectF, cut: Float, fillAlpha: Int, stroke: Int, strokeW: Float) {
    val path = beveledPath(r, cut)
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(fillAlpha, 12, 3, 8), Color.argb(fillAlpha, 4, 2, 5)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    canvas.drawPath(path, paint)
    paint.shader = null
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = strokeW * 2.8f
    paint.color = Color.argb(42, 255, 35, 88)
    paint.maskFilter = BlurMaskFilter(strokeW * 3.4f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawPath(path, paint)
    paint.maskFilter = null
    paint.strokeWidth = strokeW
    paint.color = stroke
    canvas.drawPath(path, paint)
    paint.strokeWidth = strokeW * 0.45f
    paint.color = Color.argb(112, 255, 80, 120)
    val inner = RectF(r.left + strokeW * 3f, r.top + strokeW * 3f, r.right - strokeW * 3f, r.bottom - strokeW * 3f)
    canvas.drawPath(beveledPath(inner, cut * 0.70f), paint)
  }

  private fun drawHeaderStrip(canvas: Canvas, paint: Paint, r: RectF, compact: Boolean = false) {
    val cut = r.height() * if (compact) 0.20f else 0.24f
    drawInnerLinePanel(canvas, paint, r, cut = cut, alpha = 6, strokeAlpha = if (compact) 120 else 132)
    // Brighter ZDT-D curtain: this is the main visual anchor of Mini Dashboard.
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1.2f, r.height() * if (compact) 0.017f else 0.014f)
    paint.color = Color.argb(if (compact) 196 else 210, 255, 35, 88)
    paint.maskFilter = BlurMaskFilter(r.height() * 0.035f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawPath(beveledPath(r, cut), paint)
    paint.maskFilter = null
    paint.strokeWidth = max(0.8f, r.height() * 0.006f)
    paint.color = Color.argb(126, 255, 105, 142)
    val inner = RectF(r.left + r.width() * 0.025f, r.top + r.height() * 0.13f, r.right - r.width() * 0.025f, r.bottom - r.height() * 0.13f)
    if (inner.width() > 10f && inner.height() > 6f) canvas.drawPath(beveledPath(inner, cut * 0.48f), paint)
    drawCornerTicks(canvas, paint, r, alpha = if (compact) 98 else 106)
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(95, 255, 35, 88)
    val notchW = r.width() * if (compact) 0.075f else 0.060f
    val notchH = r.height() * 0.105f
    val notch = Path().apply {
      moveTo(r.centerX() - notchW, r.top)
      lineTo(r.centerX() - notchW * 0.58f, r.top + notchH)
      lineTo(r.centerX() + notchW * 0.58f, r.top + notchH)
      lineTo(r.centerX() + notchW, r.top)
      close()
    }
    canvas.drawPath(notch, paint)
  }

  private fun drawDoubleHudFrame(canvas: Canvas, paint: Paint, r: RectF, cut: Float, alpha: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(0.9f, r.height() * 0.0065f)
    paint.color = Color.argb(alpha, 255, 54, 95)
    val a = RectF(r.left + r.width() * 0.025f, r.top + r.height() * 0.055f, r.right - r.width() * 0.025f, r.bottom - r.height() * 0.055f)
    canvas.drawPath(beveledPath(a, cut), paint)
    paint.strokeWidth = max(0.65f, r.height() * 0.0045f)
    paint.color = Color.argb((alpha * 0.62f).roundToInt(), 255, 112, 145)
    val b = RectF(r.left + r.width() * 0.055f, r.top + r.height() * 0.105f, r.right - r.width() * 0.055f, r.bottom - r.height() * 0.105f)
    if (b.width() > 12f && b.height() > 10f) canvas.drawPath(beveledPath(b, cut * 0.62f), paint)
  }

  private fun drawCornerTicks(canvas: Canvas, paint: Paint, r: RectF, alpha: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1f, r.height() * 0.007f)
    paint.color = Color.argb(alpha, 255, 54, 95)
    val lx = r.width() * 0.070f
    val ly = r.height() * 0.060f
    canvas.drawLine(r.left + lx, r.top + ly, r.left + lx * 1.85f, r.top + ly, paint)
    canvas.drawLine(r.right - lx, r.top + ly, r.right - lx * 1.85f, r.top + ly, paint)
    canvas.drawLine(r.left + lx, r.bottom - ly, r.left + lx * 1.85f, r.bottom - ly, paint)
    canvas.drawLine(r.right - lx, r.bottom - ly, r.right - lx * 1.85f, r.bottom - ly, paint)
  }

  private fun drawInnerLinePanel(canvas: Canvas, paint: Paint, r: RectF, cut: Float, alpha: Int, strokeAlpha: Int) {
    val path = beveledPath(r, cut)
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(alpha, 255, 35, 88)
    canvas.drawPath(path, paint)
    paint.color = Color.argb(218, 4, 1, 5)
    canvas.drawPath(path, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1.1f, r.height() * 0.012f)
    paint.color = Color.argb(strokeAlpha, 255, 35, 88)
    canvas.drawPath(path, paint)
    paint.strokeWidth = max(0.7f, r.height() * 0.006f)
    paint.color = Color.argb(60, 255, 95, 135)
    canvas.drawLine(r.left + r.width() * 0.10f, r.top + r.height() * 0.18f, r.right - r.width() * 0.10f, r.top + r.height() * 0.18f, paint)
    canvas.drawLine(r.left + r.width() * 0.10f, r.bottom - r.height() * 0.18f, r.right - r.width() * 0.10f, r.bottom - r.height() * 0.18f, paint)
  }

  private fun drawButton(canvas: Canvas, paint: Paint, r: RectF, text: String, active: Boolean, widgetH: Float) {
    drawInnerLinePanel(canvas, paint, r, cut = r.height() * 0.26f, alpha = if (active) 26 else 8, strokeAlpha = 150)
    if (active) {
      paint.resetForShape()
      paint.style = Paint.Style.FILL
      paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0x35FF365F, 0x11000000, Shader.TileMode.CLAMP)
      canvas.drawPath(beveledPath(r, r.height() * 0.30f), paint)
      paint.shader = null
    }
    drawIcon(canvas, paint, IconKind.CPU, r.left + r.width() * 0.18f, r.centerY(), r.height() * 0.20f, 0xFFFFFFFF.toInt(), squareOnly = true)
    drawText(canvas, paint, text, r.centerX() + r.width() * 0.07f, r.centerY() + widgetH * 0.020f, widgetH * 0.072f, 0xFFFFFFFF.toInt(), bold = true, condensed = true, align = Paint.Align.CENTER, maxWidth = r.width() * 0.62f)
  }

  private fun drawPowerButton(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, running: Boolean) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = radius * 0.075f
    paint.color = if (running) 0xFFFF365F.toInt() else 0xE8FF365F.toInt()
    paint.maskFilter = BlurMaskFilter(radius * 0.10f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawCircle(cx, cy, radius, paint)
    paint.maskFilter = null
    paint.color = 0xFFFF365F.toInt()
    canvas.drawCircle(cx, cy, radius * 0.76f, paint)
    paint.style = Paint.Style.FILL
    paint.color = 0xFFFFFFFF.toInt()
    val s = radius * 0.29f
    canvas.drawRect(cx - s * 0.55f, cy - s * 0.55f, cx + s * 0.55f, cy + s * 0.55f, paint)
  }

  private fun drawReactorLogo(canvas: Canvas, paint: Paint, context: Context, cx: Float, cy: Float, radius: Float, strong: Boolean) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = radius * 0.055f
    paint.color = 0x35FF365F
    paint.maskFilter = BlurMaskFilter(radius * 0.20f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawCircle(cx, cy, radius * 1.08f, paint)
    paint.maskFilter = null
    paint.color = 0xFFFF365F.toInt()
    paint.strokeWidth = radius * 0.035f
    canvas.drawCircle(cx, cy, radius * 1.02f, paint)
    paint.color = 0x88FFF1F5.toInt()
    paint.strokeWidth = radius * 0.020f
    canvas.drawCircle(cx, cy, radius * 0.67f, paint)
    paint.color = 0xAAFF365F.toInt()
    paint.strokeWidth = radius * 0.075f
    val arc = RectF(cx - radius * 0.93f, cy - radius * 0.93f, cx + radius * 0.93f, cy + radius * 0.93f)
    canvas.drawArc(arc, -88f, 38f, false, paint)
    canvas.drawArc(arc, 170f, 38f, false, paint)
    canvas.drawArc(arc, 352f, 26f, false, paint)

    val logo = BitmapFactory.decodeResource(context.resources, R.drawable.ic_nfqws_overlay_logo)
    if (logo != null) {
      val side = radius * if (strong) 1.34f else 1.22f
      val dst = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
      paint.resetForShape()
      paint.style = Paint.Style.FILL
      paint.colorFilter = PorterDuffColorFilter(if (strong) 0xFFFFEEF4.toInt() else 0xFFFFD6E3.toInt(), PorterDuff.Mode.SRC_IN)
      paint.maskFilter = BlurMaskFilter(radius * 0.050f, BlurMaskFilter.Blur.NORMAL)
      canvas.drawBitmap(logo, null, dst, paint)
      paint.maskFilter = null
      paint.colorFilter = null
    }
  }

  private fun drawGlassSweep(canvas: Canvas, paint: Paint, r: RectF) {
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(15, 255, 54, 95)
    val p = Path().apply {
      moveTo(r.left + r.width() * 0.10f, r.top)
      lineTo(r.left + r.width() * 0.40f, r.top)
      lineTo(r.left + r.width() * 0.28f, r.bottom)
      lineTo(r.left + r.width() * 0.02f, r.bottom)
      close()
    }
    canvas.drawPath(p, paint)
    paint.color = Color.argb(10, 255, 255, 255)
    val p2 = Path().apply {
      moveTo(r.left + r.width() * 0.48f, r.top)
      lineTo(r.left + r.width() * 0.58f, r.top)
      lineTo(r.left + r.width() * 0.46f, r.bottom)
      lineTo(r.left + r.width() * 0.38f, r.bottom)
      close()
    }
    canvas.drawPath(p2, paint)
  }

  private fun drawTechTicks(canvas: Canvas, paint: Paint, r: RectF) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1f, r.height() * 0.004f)
    paint.color = Color.argb(92, 255, 35, 88)
    val y = r.top + r.height() * 0.035f
    canvas.drawLine(r.left + r.width() * 0.38f, y, r.left + r.width() * 0.48f, y, paint)
    canvas.drawLine(r.left + r.width() * 0.55f, y, r.left + r.width() * 0.64f, y, paint)
    val notch = Path().apply {
      moveTo(r.centerX() - r.width() * 0.04f, r.top)
      lineTo(r.centerX() - r.width() * 0.025f, r.top + r.height() * 0.030f)
      lineTo(r.centerX() + r.width() * 0.025f, r.top + r.height() * 0.030f)
      lineTo(r.centerX() + r.width() * 0.04f, r.top)
    }
    canvas.drawPath(notch, paint)
    paint.strokeWidth = max(1f, r.height() * 0.003f)
    for (i in 0 until 5) {
      val x = r.left + r.width() * (0.11f + i * 0.012f)
      canvas.drawLine(x, r.top + r.height() * 0.052f, x + r.width() * 0.006f, r.top + r.height() * 0.052f, paint)
    }
    for (i in 0 until 4) {
      val x = r.right - r.width() * (0.11f + i * 0.012f)
      canvas.drawLine(x, r.bottom - r.height() * 0.035f, x + r.width() * 0.006f, r.bottom - r.height() * 0.035f, paint)
    }
  }

  private fun drawIcon(canvas: Canvas, paint: Paint, kind: IconKind, cx: Float, cy: Float, size: Float, color: Int, squareOnly: Boolean = false) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1.6f, size * 0.12f)
    paint.color = color
    if (squareOnly) {
      paint.style = Paint.Style.FILL
      canvas.drawRect(cx - size * 0.34f, cy - size * 0.34f, cx + size * 0.34f, cy + size * 0.34f, paint)
      return
    }
    when (kind) {
      IconKind.PROCESSES -> {
        val p1 = Path().apply {
          moveTo(cx, cy - size * 0.55f); lineTo(cx + size * 0.58f, cy - size * 0.25f); lineTo(cx, cy + size * 0.05f); lineTo(cx - size * 0.58f, cy - size * 0.25f); close()
        }
        val p2 = Path().apply {
          moveTo(cx - size * 0.58f, cy + size * 0.05f); lineTo(cx, cy + size * 0.35f); lineTo(cx + size * 0.58f, cy + size * 0.05f)
          moveTo(cx - size * 0.58f, cy + size * 0.35f); lineTo(cx, cy + size * 0.65f); lineTo(cx + size * 0.58f, cy + size * 0.35f)
        }
        canvas.drawPath(p1, paint); canvas.drawPath(p2, paint)
      }
      IconKind.CPU -> {
        val rr = RectF(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f)
        canvas.drawRect(rr, paint)
        for (i in -2..2) {
          val d = i * size * 0.20f
          canvas.drawLine(cx - size * 0.66f, cy + d, cx - size * 0.48f, cy + d, paint)
          canvas.drawLine(cx + size * 0.48f, cy + d, cx + size * 0.66f, cy + d, paint)
          canvas.drawLine(cx + d, cy - size * 0.66f, cx + d, cy - size * 0.48f, paint)
          canvas.drawLine(cx + d, cy + size * 0.48f, cx + d, cy + size * 0.66f, paint)
        }
      }
      IconKind.RAM -> {
        val rr = RectF(cx - size * 0.64f, cy - size * 0.36f, cx + size * 0.64f, cy + size * 0.36f)
        canvas.drawRect(rr, paint)
        for (i in 0..3) {
          val x = rr.left + rr.width() * (0.18f + i * 0.18f)
          canvas.drawRect(x, rr.top + rr.height() * 0.25f, x + rr.width() * 0.08f, rr.top + rr.height() * 0.55f, paint)
        }
        for (i in 0..4) {
          val x = rr.left + rr.width() * (0.12f + i * 0.18f)
          canvas.drawLine(x, rr.bottom, x, rr.bottom + size * 0.18f, paint)
        }
      }
    }
  }

  private fun drawGear(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, color: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = r * 0.20f
    paint.color = color
    canvas.drawCircle(cx, cy, r * 0.52f, paint)
    for (i in 0 until 8) {
      val a = Math.toRadians((i * 45).toDouble())
      val x1 = cx + cos(a).toFloat() * r * 0.72f
      val y1 = cy + sin(a).toFloat() * r * 0.72f
      val x2 = cx + cos(a).toFloat() * r * 1.00f
      val y2 = cy + sin(a).toFloat() * r * 1.00f
      canvas.drawLine(x1, y1, x2, y2, paint)
    }
  }

  private fun drawAccentSlash(canvas: Canvas, paint: Paint, x: Float, y: Float, size: Float) {
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(90, 255, 54, 95)
    val p = Path().apply {
      moveTo(x, y + size)
      lineTo(x + size * 0.42f, y)
      lineTo(x + size * 1.05f, y)
      lineTo(x + size * 0.64f, y + size)
      close()
    }
    canvas.drawPath(p, paint)
  }

  private fun drawHudLine(canvas: Canvas, paint: Paint, x1: Float, y1: Float, x2: Float, y2: Float, alpha: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1f, (y2 - y1).coerceAtLeast(1f) * 0.02f)
    paint.color = Color.argb(alpha, 255, 54, 95)
    canvas.drawLine(x1, y1, x2, y2, paint)
  }

  private fun drawDot(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, color: Int) {
    paint.resetForShape()
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.maskFilter = BlurMaskFilter(r * 1.1f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawCircle(cx, cy, r, paint)
    paint.maskFilter = null
    canvas.drawCircle(cx, cy, r * 0.82f, paint)
  }

  private fun drawCircleStroke(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, color: Int, sw: Float) {
    paint.resetForShape()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = sw
    paint.color = color
    canvas.drawCircle(cx, cy, r, paint)
  }

  private fun beveledPath(r: RectF, cut: Float): Path = Path().apply {
    moveTo(r.left + cut, r.top)
    lineTo(r.right - cut, r.top)
    lineTo(r.right, r.top + cut)
    lineTo(r.right, r.bottom - cut)
    lineTo(r.right - cut, r.bottom)
    lineTo(r.left + cut, r.bottom)
    lineTo(r.left, r.bottom - cut)
    lineTo(r.left, r.top + cut)
    close()
  }

  private fun drawText(
    canvas: Canvas,
    paint: Paint,
    text: String,
    x: Float,
    baselineY: Float,
    size: Float,
    color: Int,
    bold: Boolean,
    condensed: Boolean,
    align: Paint.Align = Paint.Align.LEFT,
    maxWidth: Float = Float.MAX_VALUE,
  ) {
    paint.resetForText(size, color, bold, condensed, align)
    var out = text
    if (maxWidth.isFinite() && paint.measureText(out) > maxWidth) {
      val ellipsis = "…"
      while (out.isNotEmpty() && paint.measureText(out + ellipsis) > maxWidth) out = out.dropLast(1)
      out += ellipsis
    }
    canvas.drawText(out, x, baselineY, paint)
  }

  private fun textWidth(paint: Paint, text: String, size: Float, bold: Boolean, condensed: Boolean): Float {
    paint.resetForText(size, Color.WHITE, bold, condensed, Paint.Align.LEFT)
    return paint.measureText(text)
  }

  private fun Paint.resetForShape() {
    reset()
    isAntiAlias = true
    isDither = true
  }

  private fun Paint.resetForText(size: Float, color: Int, bold: Boolean, condensed: Boolean, align: Paint.Align) {
    reset()
    isAntiAlias = true
    isSubpixelText = true
    textSize = size
    this.color = color
    textAlign = align
    typeface = android.graphics.Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
  }

  private fun formatCpu(value: Double): String = String.format(java.util.Locale.US, "%.1f%%", value)

  private fun formatRam(value: Double): String = String.format(java.util.Locale.US, "%.1f МБ", value).replace('.', ',')
}
