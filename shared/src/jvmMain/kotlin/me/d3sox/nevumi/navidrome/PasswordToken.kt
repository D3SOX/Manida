package me.d3sox.nevumi.navidrome

import java.security.MessageDigest

internal actual fun passwordToken(password: String, salt: String): String =
    MessageDigest.getInstance("MD5").digest((password + salt).encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
