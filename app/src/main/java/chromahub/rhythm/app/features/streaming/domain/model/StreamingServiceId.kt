/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

object StreamingServiceId {
    const val CUSTOM_API = "CUSTOM_API"
    val all = listOf(
        CUSTOM_API
    )
}

object StreamingServiceRules {
    fun requiresServerUrl(serviceId: String): Boolean {
        return false
    }
}
