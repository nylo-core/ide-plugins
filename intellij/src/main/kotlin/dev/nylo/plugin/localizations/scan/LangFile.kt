package dev.nylo.plugin.localizations.scan

import java.io.File

/** A discovered locale file: its [code] (e.g. `en`) and the backing [file] (`lang/en.json`). */
data class LangFile(val code: String, val file: File)
