package me.d3sox.nevumi.security

interface CredentialVault {
    suspend fun save(key: String, secret: String)
    suspend fun read(key: String): String?
    suspend fun delete(key: String)
}
