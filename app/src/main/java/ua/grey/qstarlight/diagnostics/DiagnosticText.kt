package ua.grey.qstarlight.diagnostics

object DiagnosticText {
    private val secrets = Regex("(\"(?:pin|password|remotePin|hubPin|pwd_[^\"]*)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"", RegexOption.IGNORE_CASE)

    fun sanitize(text: String): String = secrets.replace(text) { "${it.groupValues[1]}\"***\"" }
        .replace('\r', ' ').replace('\n', ' ').take(4000)
}
