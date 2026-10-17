package me.d3sox.nevumi

/** Requires a seed process followed by a fresh app process with retained data. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ColdStartupCheck
