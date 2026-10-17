package me.d3sox.nevumi.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SecretServiceVault(private val applicationId: String = "me.d3sox.nevumi") : CredentialVault {
    override suspend fun save(key: String, secret: String) {
        val (code, _) = execute(listOf("store", "--label=Nevumi", "application", applicationId,
            "profile", key), secret)
        check(code == 0) { "Could not save the password in the desktop credential store" }
    }
    override suspend fun read(key: String): String? {
        val (code, output) = execute(listOf("lookup", "application", applicationId, "profile", key))
        check(code == 0 || code == 1) { "Could not open the desktop credential store" }
        return output.removeSuffix("\n").takeIf { code == 0 }
    }
    override suspend fun delete(key: String) {
        val (code, _) = execute(listOf("clear", "application", applicationId, "profile", key))
        check(code == 0 || code == 1) { "Could not remove the saved password" }
    }
    private suspend fun execute(arguments: List<String>, input: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf("secret-tool") + arguments).start()
        try {
            process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Unlock the desktop credential store and try again" }
            process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
