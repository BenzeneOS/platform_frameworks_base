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

package com.android.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Slog

import com.android.internal.util.HideAppListUtils

/**
 * Service that cleans up the hide app list when apps are uninstalled.
 * Removes uninstalled package names from Settings.Secure.HIDE_APPLIST.
 * @hide
 */
class HideAppListService(context: Context) : SystemService(context) {
    override fun onStart() {
        // Nothing to publish
    }

    override fun onBootPhase(phase: Int) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            HideAppListUtils.init(context)
            registerPackageRemovedReceiver()
        }
    }

    private fun registerPackageRemovedReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }

        context.registerReceiverAsUser(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) {
                        return
                    }
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName.isNullOrEmpty()) {
                        return
                    }
                    removePackageFromHideList(packageName)
                }
            },
            UserHandle.ALL,
            filter,
            null,
            null,
        )
    }

    private fun removePackageFromHideList(packageName: String) {
        val userManager = context.getSystemService(UserManager::class.java) ?: return

        userManager.getUserHandles(true).forEach { userHandle ->
            removePackageFromHideListForUser(packageName, userHandle.identifier)
        }
    }

    private fun removePackageFromHideListForUser(packageName: String, userId: Int) {
        try {
            val hiddenApps = Settings.Secure.getStringForUser(
                context.contentResolver,
                Settings.Secure.HIDE_APPLIST,
                userId
            )

            if (hiddenApps.isNullOrEmpty()) {
                return
            }

            val newValue = hiddenApps
                .split(",")
                .filter { it.isNotEmpty() && it != packageName }
                .joinToString(",")

            // Only update if the list actually changed
            if (hiddenApps != newValue) {
                Settings.Secure.putStringForUser(
                    context.contentResolver,
                    Settings.Secure.HIDE_APPLIST,
                    newValue,
                    userId,
                )
                Slog.d(TAG, "Removed $packageName from hide list for user $userId")
            }
        } catch (e: Exception) {
            Slog.e(TAG, "Error removing package from hide list", e)
        }
    }

    companion object {
        private const val TAG = "HideAppListService"
    }
}
