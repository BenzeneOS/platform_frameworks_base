/*
 * Copyright (C) 2025 Amaan Qureshi <contact@amaanq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Repository for custom network type text setting.
 * When the user sets custom text (e.g., "4G"), it replaces all network type
 * indicators (LTE, 5G, 3G, etc.) in the status bar with that text.
 */
interface CustomNetworkTypeRepository {
    /** The custom network type text, or empty string if not set */
    val customNetworkTypeText: StateFlow<String>
}

@SysUISingleton
class CustomNetworkTypeRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    @Main private val mainHandler: Handler,
    @Background private val scope: CoroutineScope,
    private val userTracker: UserTracker,
) : CustomNetworkTypeRepository {
    override val customNetworkTypeText: StateFlow<String> = callbackFlow {
        fun getValue(): String =
            Settings.System.getStringForUser(
                contentResolver,
                Settings.System.CUSTOM_NETWORK_TYPE_TEXT,
                userTracker.userId,
            ) ?: ""

        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                trySend(getValue())
            }
        }

        val userCallback = object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: android.content.Context) {
                trySend(getValue())
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_NETWORK_TYPE_TEXT),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, mainHandler::post)

        trySend(getValue())

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
            userTracker.removeCallback(userCallback)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), "")
}
