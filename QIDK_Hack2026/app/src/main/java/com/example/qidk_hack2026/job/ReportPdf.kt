package com.example.qidk_hack2026.job

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One-page-per-A4 summary of a filled form: what went in, what is still empty, what was said. */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val MARGIN = 48f

    fun file(context: Context, record: Record): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        return File(dir, "form-${record.id}.pdf")
    }

    fun create(context: Context, record: Record): File {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var canvas = page.canvas
        var y = MARGIN

        fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun text(s: String, p: TextPaint, left: Float = MARGIN, width: Float = W - 2 * MARGIN, gap: Float = 6f): Float {
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).build()
            if (y + layout.height > H - MARGIN) newPage()
            canvas.save(); canvas.translate(left, y); layout.draw(canvas); canvas.restore()
            y += layout.height + gap
            return layout.height.toFloat()
        }

        val ink = Color.parseColor("#12161D")
        val muted = Color.parseColor("#667084")
        val rule = Paint().apply { color = Color.parseColor("#DCE1EA"); strokeWidth = 1f }
        val accent = Color.parseColor("#3D7BFF")

        canvas.drawRect(0f, 0f, W.toFloat(), 8f, Paint().apply { color = accent })
        y += 8f
        text("FORM REPORT", paint(10f, accent, true), gap = 10f)
        text(record.title.ifBlank { "Untitled form" }, paint(24f, ink, true), gap = 6f)
        text(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(record.time)), paint(11f, muted), gap = 18f)
        canvas.drawLine(MARGIN, y, W - MARGIN, y, rule); y += 18f

        text("Filled (${record.entries.size})", paint(13f, ink, true), gap = 10f)
        if (record.entries.isEmpty()) text("Nothing was filled in.", paint(12f, muted), gap = 12f)
        val labelP = paint(11f, muted)
        val valueP = paint(12.5f, ink)
        for (e in record.entries) {
            val startY = y
            val h1 = text(e.label, labelP, width = 150f, gap = 0f)
            val afterLabel = y
            y = startY
            val h2 = text(e.value, valueP, left = MARGIN + 170f, width = W - 2 * MARGIN - 170f, gap = 0f)
            y = maxOf(afterLabel, y) + 9f
            if (h1 + h2 < 0) break
        }

        if (record.missing.isNotEmpty()) {
            y += 8f
            canvas.drawLine(MARGIN, y, W - MARGIN, y, rule); y += 18f
            text("Still empty (${record.missing.size})", paint(13f, Color.parseColor("#C26A00"), true), gap = 10f)
            record.missing.forEach { text("- $it", paint(12f, ink), gap = 4f) }
        }

        if (record.transcript.isNotBlank()) {
            y += 12f
            canvas.drawLine(MARGIN, y, W - MARGIN, y, rule); y += 18f
            text("What was said", paint(13f, ink, true), gap = 8f)
            text("“${record.transcript}”", paint(11.5f, muted), gap = 4f)
        }

        doc.finishPage(page)
        val out = file(context, record)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** First page as a bitmap, for the in-app preview. */
    fun render(file: File, widthPx: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val h = (widthPx.toFloat() * p.height / p.width).toInt()
                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
