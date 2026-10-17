package me.d3sox.nevumi.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val scrollDate = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
internal actual fun formatScrollDate(epochMillis: Long): String = scrollDate.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
