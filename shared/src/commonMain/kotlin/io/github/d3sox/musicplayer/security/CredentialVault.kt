package io.github.d3sox.musicplayer.security

interface CredentialVault {
    suspend fun save(key: String, secret: String)
    suspend fun read(key: String): String?
    suspend fun delete(key: String)
}
