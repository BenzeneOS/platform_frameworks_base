package com.android.server.ext;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.ext.settings.app.AswBenzenedRoot;
import android.ext.settings.app.AswBenzenedRootUnrestricted;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import app.benzeneos.benzened.IBenzenedGrants;

public final class BenzenedGrantsService extends IBenzenedGrants.Stub {
    private static final String TAG = "BenzenedGrants";

    public static final String SERVICE_NAME = "app.benzeneos.benzened.IBenzenedGrants/default";

    private final Context context;

    BenzenedGrantsService(Context context) {
        this.context = context;
    }

    @Override
    public int getRootTier(int uid) {
        if (Binder.getCallingUid() != Process.ROOT_UID) {
            return TIER_NONE;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return rootTierForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int rootTierForUid(int uid) {
        int userId = UserHandle.getUserId(uid);
        PackageManager pm = context.getPackageManager();

        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length != 1) {
            return TIER_NONE;
        }

        String packageName = packages[0];
        ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfoAsUser(packageName, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "unknown package for uid " + uid);
            return TIER_NONE;
        }

        GosPackageState ps = GosPackageState.get(packageName, userId);
        if (!AswBenzenedRoot.isEnabledFor(context, userId, appInfo, ps)) {
            return TIER_NONE;
        }
        if (AswBenzenedRootUnrestricted.isEnabledFor(context, userId, appInfo, ps)) {
            return TIER_UNRESTRICTED;
        }
        return TIER_STANDARD;
    }
}
