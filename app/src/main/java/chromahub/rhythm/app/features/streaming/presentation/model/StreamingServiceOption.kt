/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.model

import androidx.annotation.StringRes
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId

data class StreamingServiceOption(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int
)

object StreamingServiceOptions {
    const val CUSTOM_API = StreamingServiceId.CUSTOM_API
    val defaults: List<StreamingServiceOption> = listOf(
        StreamingServiceOption(
            id = CUSTOM_API,
            nameRes = R.string.streaming_service_custom_api,
            descriptionRes = R.string.streaming_service_custom_api_desc
        ),
    )
}
