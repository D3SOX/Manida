package me.d3sox.nevumi.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal actual fun formatListenTime(epochMillis: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withLocale(Locale.ENGLISH).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
