package com.perezfranco.balrog.security

import android.content.Context
import com.perezfranco.balrog.R

/** The bundled EFF large wordlist (7,776 words, ~12.9 bits per word). */
object WordList {

    @Volatile
    private var cached: List<String>? = null

    fun load(context: Context): List<String> =
        cached ?: synchronized(this) {
            cached ?: context.applicationContext.resources
                .openRawResource(R.raw.eff_large_wordlist)
                .bufferedReader()
                .useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
                .also { cached = it }
        }
}
