package com.roberto.eliasaitutor.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.roberto.eliasaitutor.model.UserProfile
import java.io.ByteArrayOutputStream
import java.time.LocalDate

object PdfGenerator {
    fun generatePdfReport(context: Context, profile: UserProfile, narrative: String): ByteArray {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size in points
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Background
        val bgPaint = Paint().apply { color = Color.parseColor("#0d0f14") }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        // Title text
        val titlePaint = Paint().apply {
            color = Color.parseColor("#4f8ef7")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Elias AI Tutor — Soft Skills Report", 40f, 60f, titlePaint)
        
        // Line separator
        val linePaint = Paint().apply {
            color = Color.parseColor("#252a35")
            strokeWidth = 1f
        }
        canvas.drawLine(40f, 75f, 555f, 75f, linePaint)

        var y = 110f

        // Sections helper
        fun drawSection(title: String) {
            val sectionPaint = Paint().apply {
                color = Color.parseColor("#4f8ef7")
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(title, 40f, y, sectionPaint)
            y += 10f
            canvas.drawLine(40f, y, 555f, y, linePaint)
            y += 20f
        }

        fun drawKv(key: String, value: String) {
            val keyPaint = Paint().apply {
                color = Color.parseColor("#7a8099")
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val valPaint = Paint().apply {
                color = Color.parseColor("#e8eaf0")
                textSize = 11f
            }
            canvas.drawText(key.uppercase(), 40f, y, keyPaint)
            canvas.drawText(value, 200f, y, valPaint)
            y += 20f
        }

        fun drawBodyText(text: String, startX: Float = 40f) {
            val textPaint = Paint().apply {
                color = Color.parseColor("#b4b9c8")
                textSize = 11f
            }
            
            // simple text wrapping
            val lines = text.split("\n")
            for (line in lines) {
                var currentLine = line
                while (currentLine.isNotEmpty()) {
                    val count = textPaint.breakText(currentLine, true, 515f, null)
                    val toDraw = currentLine.substring(0, count)
                    canvas.drawText(toDraw, startX, y, textPaint)
                    y += 16f
                    currentLine = currentLine.substring(count)
                }
            }
            y += 5f
        }

        // 1. Student Overview
        drawSection("Student Overview")
        drawKv("Name / ID", profile.userId.ifEmpty { "Student" })
        drawKv("Current Level", "Level ${profile.level}")
        drawKv("Total XP", "${profile.xp} XP")
        drawKv("Elias Coins", "${profile.coins} coins")
        drawKv("Messages Sent", "${profile.messagesCount}")
        drawKv("Report Date", LocalDate.now().toString())
        y += 10f

        // 2. Soft Skills Assessment
        drawSection("Soft Skills Assessment (DeepSeek AI)")
        drawKv("Confidence", "${profile.confidence} / 100")
        drawKv("Clarity", "${profile.clarity} / 100")
        drawKv("Posture", "${profile.posture} / 100")
        if (profile.softSkillsSummary.isNotEmpty()) {
            drawBodyText("AI Summary: ${profile.softSkillsSummary}")
        }
        y += 10f

        // 3. Narrative
        drawSection("Personalized Coaching Narrative (DeepSeek AI)")
        drawBodyText(narrative)
        y += 10f

        // 4. Grammar
        drawSection("Grammar Mistake Log (Last 10)")
        val errors = profile.errorLog.takeLast(10)
        if (errors.isEmpty()) {
            drawBodyText("No mistakes logged yet — great work!")
        } else {
            errors.forEach { e ->
                drawBodyText("[${e.timestamp.take(10)}] ${e.error}")
            }
        }
        y += 10f

        // 5. Recommendations
        drawSection("Recommendations from Elias")
        val recs = listOf(
            "Focus on using phrasal verbs naturally in everyday conversation.",
            "Practice shadowing 5 minutes daily for measurable pronunciation gains.",
            "Try the Job Interview scenario once you reach Level 5.",
            "Aim for 3 new vocabulary words per day using spaced repetition.",
            "Record yourself and compare your audio to the shadowing reference."
        )
        recs.forEach { r ->
            drawBodyText("→ $r", 50f)
        }

        document.finishPage(page)

        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }
}
