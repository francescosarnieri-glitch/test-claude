package com.cybersensei.academy.ui.diploma

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.cybersensei.academy.core.ui.Professor
import java.io.File
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Draws the diploma as an image and hands it to whatever the student wants to do with it.
 *
 * Drawn on a canvas rather than captured from the screen: a certificate has to come out the
 * same on every phone, and a screenshot of a scrolling layout would come out cropped, in the
 * device's theme, at the device's density.
 *
 * Nothing here touches the network — the file is written to the app's own cache and shared
 * through a content URI, which is the whole reason the app can offer this while asking for
 * no permissions at all.
 */
object DiplomaImage {

    fun share(context: Context, diploma: Diploma) {
        val file = write(context, diploma)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diplomi", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Diploma — Cyber Sensei")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Diploma di ${diploma.studentName}"))
    }

    private fun write(context: Context, diploma: Diploma): File {
        val directory = File(context.cacheDir, "diplomi").apply { mkdirs() }
        val file = File(directory, "diploma-${diploma.studentName.lowercase()}.png")
        FileOutputStream(file).use { output ->
            render(diploma).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file
    }

    fun render(diploma: Diploma): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val border = Paint().apply {
            color = ACCENT
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        canvas.drawRect(MARGIN, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN, border)

        var y = MARGIN + 140f
        y = canvas.centred("CYBER SENSEI", y, size = 44f, colour = ACCENT, letterSpacing = 0.25f)
        y = canvas.centred("Scuola di difesa informatica", y + 56f, size = 32f, colour = MUTED)

        y = canvas.centred("DIPLOMA", y + 150f, size = 96f, colour = TEXT, bold = true)
        y = canvas.centred("conferito a", y + 90f, size = 34f, colour = MUTED)
        y = canvas.centred(diploma.studentName, y + 110f, size = 76f, colour = ACCENT, bold = true)

        y = canvas.centred(
            "per aver superato gli esami dei tre livelli",
            y + 110f,
            size = 34f,
            colour = TEXT,
        )
        y = canvas.centred("e attraversato la notte dell'Incidente", y + 50f, size = 34f, colour = TEXT)

        // The numbers, because a certificate with no evidence on it is a decoration.
        y += 110f
        val facts = buildList {
            diploma.examScores.toSortedMap().forEach { (level, score) -> add("$level: $score%") }
            add("Padronanza media: ${diploma.masteryPercent}%")
            add("Lezioni: ${diploma.lessonsCompleted} su ${diploma.lessonsTotal}")
            add("Tempo di studio: ${diploma.studyMinutes} minuti")
            add("Iscritto da ${diploma.daysEnrolled} giorni")
        }
        facts.forEach { fact ->
            y = canvas.centred(fact, y + 46f, size = 30f, colour = MUTED)
        }

        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
        canvas.centred(
            "Rilasciato il ${diploma.awardedOn.format(formatter)}",
            HEIGHT - MARGIN - 190f,
            size = 30f,
            colour = MUTED,
        )
        canvas.centred(
            Professor.NAME,
            HEIGHT - MARGIN - 110f,
            size = 42f,
            colour = TEXT,
            italic = true,
        )
        canvas.centred(
            "Questo documento non ha valore legale. Ha valore per te.",
            HEIGHT - MARGIN - 50f,
            size = 24f,
            colour = MUTED,
        )

        return bitmap
    }

    private fun Canvas.centred(
        text: String,
        y: Float,
        size: Float,
        colour: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        letterSpacing: Float = 0f,
    ): Float {
        val paint = Paint().apply {
            this.color = colour
            textSize = size
            isAntiAlias = true
            this.letterSpacing = letterSpacing
            typeface = Typeface.create(
                Typeface.SERIF,
                when {
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }
        drawText(text, (WIDTH - paint.measureText(text)) / 2f, y, paint)
        return y
    }

    private const val WIDTH = 1400
    private const val HEIGHT = 1980
    private const val MARGIN = 60f
    private val BACKGROUND = Color.parseColor("#0F1620")
    private val TEXT = Color.parseColor("#E6EDF3")
    private val MUTED = Color.parseColor("#98A6B5")
    private val ACCENT = Color.parseColor("#3DDC97")
}
