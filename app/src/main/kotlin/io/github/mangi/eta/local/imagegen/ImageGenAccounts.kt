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
    if (config.source != ImageGenSource.CODEX_SUBSCRIPTION || config.providerId.isBlank()) return ImageGenAccounts()
    return try {
        ImageGenAccounts(readAccounts(config.providerId, ProviderAuthModes.OPENAI_CHATGPT))
    } catch (_: ProviderAuthException) {
        ImageGenAccounts(error = "订阅账号凭据不可用，请到服务商设置重新登录；也可切换到中转 API")
    }
}
