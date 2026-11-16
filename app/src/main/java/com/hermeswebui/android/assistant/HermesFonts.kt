package com.hermeswebui.android.assistant

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/** A font the user can pick: a Galaxy font installed on the phone, by its package. */
data class FontChoice(val id: String, val label: String)

/**
 * The one font of every native Hermes screen. The picked font's file is copied into the app, and
 * [preload] reads it before the first frame is composed, so a screen is never drawn in another font
 * first. No pick means the phone's own font.
 */
object HermesFonts {
    private const val PREFS = "hermes_font"
    private const val KEY_ID = "id"
    private const val KEY_LABEL = "label"
    private const val FILE = "picked-font"
    /** Galaxy fonts (Settings, Font style) are installed as packages under this name. */
    private const val GALAXY_PREFIX = "com.monotype.android.font."

    /** Null is the phone's own font. Compose state, so a new pick redraws every screen showing text. */
    var family: FontFamily? by mutableStateOf(null)
        private set
    var pickedId: String? by mutableStateOf(null)
        private set
    private var loaded = false

    /** Reads the picked font from the app's own copy. Synchronous and cheap: call before `setContent`. */
    fun preload(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: return
        val typeface = runCatching { Typeface.createFromFile(File(context.filesDir, FILE)) }.getOrNull()
        if (typeface == null || typeface == Typeface.DEFAULT) return
        pickedId = id
        family = FontFamily(typeface)
    }

    fun pickedLabel(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LABEL, null)?.takeIf { pickedId != null }

    /** The Galaxy fonts installed on this phone, by name. Reads the package list: call off the main thread. */
    fun installed(context: Context): List<FontChoice> {
        val manager = context.packageManager
        return runCatching { manager.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { it.packageName.startsWith(GALAXY_PREFIX) }
            .map { FontChoice(it.packageName, manager.getApplicationLabel(it).toString().ifBlank { it.packageName.removePrefix(GALAXY_PREFIX) }) }
            .sortedBy { it.label.lowercase() }
    }

    /** The font inside a Galaxy font package, for a preview. Call off the main thread. */
    fun typeface(context: Context, id: String): Typeface? = runCatching {
        val assets = context.createPackageContext(id, 0).assets
        val name = assets.list("fonts").orEmpty().firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } ?: return null
        Typeface.createFromAsset(assets, "fonts/$name")
    }.getOrNull()

    /**
     * Makes [choice] the app's font (null: the phone's own). Copies the font file into the app, so the
     * next start needs neither the package list nor the font's package. Call off the main thread;
     * returns false when the font could not be read, leaving the current one.
     */
    fun pick(context: Context, choice: FontChoice?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, FILE)
        if (choice == null) {
            prefs.edit().remove(KEY_ID).remove(KEY_LABEL).apply()
            file.delete()
            pickedId = null
            family = null
            return true
        }
        val copied = runCatching {
            val assets = context.createPackageContext(choice.id, 0).assets
            val name = assets.list("fonts").orEmpty().firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } ?: return false
            val staged = File(context.filesDir, "$FILE.new")
            assets.open("fonts/$name").use { input -> staged.outputStream().use { input.copyTo(it) } }
            val typeface = Typeface.createFromFile(staged)
            if (!staged.renameTo(file)) return false
            typeface
        }.getOrNull() ?: return false
        prefs.edit().putString(KEY_ID, choice.id).putString(KEY_LABEL, choice.label).apply()
        pickedId = choice.id
        family = FontFamily(copied)
        return true
    }
}
