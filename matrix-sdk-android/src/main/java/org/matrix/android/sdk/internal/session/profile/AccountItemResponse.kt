/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.profile

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.profile.model.AccountItem

@JsonClass(generateAdapter = true)
data class AccountItemResponse(
        @Json(name = "user_id")
        val userId: String,
        @Json(name = "display_name")
        val displayName: String,
        @Json(name = "avatar_url")
        val avatarUrl: String? = null,
        @Json(name = "unread_count")
        val unreadCount: Int
)

fun AccountItemResponse.toAccountItem() = AccountItem(
        userId = userId,
        displayName = displayName,
        avatarUrl = avatarUrl,
        unreadCount = unreadCount
)
