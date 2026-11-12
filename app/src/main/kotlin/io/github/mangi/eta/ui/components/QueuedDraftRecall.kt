package io.github.mangi.eta.ui.components

/** Legacy request mode is deliberately ignored after custom plan mode retirement. */
internal fun recallQueuedDraft(
    id: String,
    text: String,
    mode: String,
    legacy: (String, String) -> Unit,
    withMode: ((String, String, String) -> Unit)?,
) {
    if (withMode != null) withMode(id, text, "normal") else legacy(id, text)
}
