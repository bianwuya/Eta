package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderCredentialRepository

internal data class ImageGenAccounts(val accounts: List<CodexAccountView> = emptyList(), val error: String? = null)

internal fun loadImageGenAccounts(
    config: ImageGenConfig,
    readAccounts: (String, String) -> List<CodexAccountView> = ProviderCredentialRepository::accounts,
): ImageGenAccounts {
    if (config.source != ImageGenSource.CODEX_SUBSCRIPTION || config.credentialPoolId.isBlank()) return ImageGenAccounts()
    return try {
        ImageGenAccounts(readAccounts(config.credentialPoolId, ProviderAuthModes.OPENAI_CHATGPT))
    } catch (_: ProviderAuthException) {
        ImageGenAccounts(error = "订阅账号凭据不可用，请在此独立连接中重新登录")
    }
}
