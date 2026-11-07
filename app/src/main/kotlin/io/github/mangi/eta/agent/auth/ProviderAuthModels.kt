package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.data.model.ProviderAuthModes
import kotlinx.serialization.Serializable

internal data class ProviderAccountSummary(
    val email: String?,
    val displayName: String?,
    val needsReauthentication: Boolean,
    val planEnabled: Boolean,
)

/** 请求开始时的凭据绑定；[credentialSlotId] 固定到具体账号槽，重试与 401 刷新都沿用它。 */
internal data class ProviderCredentialBinding(
    val providerId: String,
    val authMode: String,
    val generation: Long,
    val credentialSlotId: String = providerId,
)

/** OAuth 返回的可验证稳定身份；识别不到时不能用于去重或重登录校验。 */
internal data class ProviderCredentialIdentity(
    val subject: String,
    val accountId: String?,
) {
    /** accountId 可能因退出登录被清空，只比较双方都存在的部分。 */
    fun matches(other: ProviderCredentialIdentity): Boolean =
        subject == other.subject && (accountId == null || other.accountId == null || accountId == other.accountId)
}

internal fun ProviderCredential.identity(): ProviderCredentialIdentity? =
    subject?.takeIf(String::isNotBlank)?.let {
        ProviderCredentialIdentity(it, accountId?.takeIf(String::isNotBlank))
    }

internal class ProviderLoginPrompt(
    val authorizationUrl: String,
    val userCode: String? = null,
) {
    override fun toString(): String = "ProviderLoginPrompt(redacted)"
}

internal class ProviderAuthException(val code: String, message: String) : RuntimeException(message)

@Serializable
internal data class ProviderCredential(
    val authMode: String,
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val accountId: String? = null,
    val expiresAt: Long = 0,
    val scopes: Set<String> = emptySet(),
) {
    val planEnabled: Boolean
        get() = when (authMode) {
            ProviderAuthModes.OPENAI_CHATGPT -> clientId == CodexOAuth.CLIENT_ID && !accountId.isNullOrBlank()
            ProviderAuthModes.XAI_GROK -> "grok-cli:access" in scopes
            else -> false
        }

    fun summary() = ProviderAccountSummary(email, displayName, subject == null || accessToken.isBlank() ||
        (authMode == ProviderAuthModes.OPENAI_CHATGPT && !planEnabled), planEnabled)

    fun withoutTokens() = copy(accessToken = "", refreshToken = "", idToken = "", accountId = null, expiresAt = 0)

    override fun toString(): String = "ProviderCredential(redacted)"
}

internal enum class OAuthProvider(
    val authMode: String,
    val issuer: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val signingAlgorithm: String,
) {
    OPENAI(
        ProviderAuthModes.OPENAI_CHATGPT,
        "https://auth.openai.com",
        "https://auth.openai.com/oauth/token",
        null,
        "RS256",
    ),
    XAI(
        ProviderAuthModes.XAI_GROK,
        "https://auth.x.ai",
        "https://auth.x.ai/oauth2/token",
        "https://auth.x.ai/oauth2/revoke",
        "ES256",
    );

    val jwksUrl: String get() = "$issuer/.well-known/jwks.json"

    companion object {
        fun fromAuthMode(mode: String): OAuthProvider = entries.firstOrNull { it.authMode == mode }
            ?: throw ProviderAuthException("unsupported_auth_mode", "当前连接不支持账号登录")
    }
}
