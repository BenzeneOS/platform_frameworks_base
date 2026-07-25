/*
 * Copyright (C) 2026 Amaan Qureshi <contact@amaanq.com>
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

package com.android.server.ext;

import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.pushcompat.FirebaseReceiverResolver;
import com.android.internal.pushcompat.PushCompatApp;
import com.android.server.pm.UserManagerInternal;

import java.util.List;
import java.util.Set;

public final class PushCompatBroker {
    private static final String TAG = "PushCompatBroker";
    private static final String ACTION_C2DM_RECEIVE =
            "com.google.android.c2dm.intent.RECEIVE";
    private static final String ACTION_NEW_TOKEN = "com.google.firebase.messaging.NEW_TOKEN";
    private static final Set<String> UNIFIED_PUSH_CONNECTOR_ACTIONS = Set.of(
            "org.unifiedpush.android.connector.NEW_ENDPOINT",
            "org.unifiedpush.android.connector.REGISTRATION_FAILED",
            "org.unifiedpush.android.connector.UNREGISTERED",
            "org.unifiedpush.android.connector.MESSAGE");
    private static final long TEMPORARY_ALLOWLIST_DURATION_MILLIS = 10_000L;

    private final Context context;
    private final PackageManagerInternal packageManager;
    private final UserManagerInternal userManager;

    PushCompatBroker(Context context, PackageManagerInternal packageManager,
            UserManagerInternal userManager) {
        this.context = context;
        this.packageManager = packageManager;
        this.userManager = userManager;
    }

    public boolean deliverToken(int userId, String packageName, String token) {
        final int callingUid = Binder.getCallingUid();
        final int pushCompatUid = packageManager.getPackageUid(PushCompatApp.PKG_NAME,
                PackageManager.MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM);
        if (callingUid != pushCompatUid) {
            throw new SecurityException("Unauthorized PushCompat broker caller " + callingUid);
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(token)) {
                Slog.e(TAG, "Invalid token delivery request");
                return false;
            }
            if (!userManager.isProfileAccessible(UserHandle.getUserId(callingUid), userId,
                    TAG, false)) {
                Slog.e(TAG, "Target user is not accessible " + userId);
                return false;
            }
            if (!packageManager.getGosPackageState(packageName, userId)
                    .hasFlag(GosPackageStateFlag.PUSH_COMPAT_RELAY)) {
                Slog.e(TAG, "Target package is not enabled " + packageName + " u" + userId);
                return false;
            }

            final Intent receiverIntent = new Intent(ACTION_C2DM_RECEIVE).setPackage(packageName);
            final List<ResolveInfo> receivers = packageManager.queryIntentReceivers(
                    receiverIntent,
                    null,
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                    Process.SYSTEM_UID,
                    Process.myPid(),
                    userId,
                    true,
                    new String[] { packageName });
            final ResolveInfo receiver = FirebaseReceiverResolver.selectReceiver(receivers);
            if (receiver == null) {
                Slog.e(TAG, "No C2DM receiver for " + packageName + " u" + userId);
                return false;
            }

            final Intent intent =
                    new Intent(ACTION_NEW_TOKEN)
                            .setComponent(new ComponentName(
                                    receiver.activityInfo.packageName,
                                    receiver.activityInfo.name))
                            .putExtra("token", token);
            try {
                context.sendBroadcastAsUser(
                        intent,
                        UserHandle.of(userId),
                        null,
                        pushBroadcastOptions());
                return true;
            } catch (RuntimeException e) {
                Slog.e(TAG, "Token broadcast failed for " + packageName + " u" + userId, e);
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean deliverUnifiedPushConnectorIntent(int userId, String packageName,
            String action, Bundle extras) {
        final int callingUid = Binder.getCallingUid();
        final int pushCompatUid = packageManager.getPackageUid(PushCompatApp.PKG_NAME,
                PackageManager.MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM);
        if (callingUid != pushCompatUid) {
            throw new SecurityException("Unauthorized PushCompat broker caller " + callingUid);
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            if (!userManager.isProfileAccessible(UserHandle.getUserId(callingUid), userId,
                    TAG, false)) {
                Slog.e(TAG, "Target user is not accessible " + userId);
                return false;
            }
            if (TextUtils.isEmpty(packageName) || extras == null) {
                Slog.e(TAG, "Invalid UnifiedPush connector delivery request");
                return false;
            }
            if (TextUtils.isEmpty(action) || !UNIFIED_PUSH_CONNECTOR_ACTIONS.contains(action)) {
                Slog.e(TAG, "Rejected UnifiedPush connector action " + action);
                return false;
            }

            final Intent intent = new Intent(action).setPackage(packageName).putExtras(extras);
            try {
                context.sendBroadcastAsUser(
                        intent,
                        UserHandle.of(userId),
                        null,
                        pushBroadcastOptions());
                return true;
            } catch (RuntimeException e) {
                Slog.e(TAG, "UnifiedPush connector broadcast failed for "
                        + packageName + " u" + userId, e);
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static Bundle pushBroadcastOptions() {
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppAllowlist(
                TEMPORARY_ALLOWLIST_DURATION_MILLIS,
                PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_PUSH_MESSAGING,
                "PushCompat delivery");
        return options.toBundle();
    }
}
