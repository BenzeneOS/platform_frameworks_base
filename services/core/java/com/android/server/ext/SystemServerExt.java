package com.android.server.ext;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SELinuxFlags;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;

import dalvik.system.VMRuntime;

public final class SystemServerExt {

    private static volatile SystemServerExt instance;

    public final Context context;
    public final Handler bgHandler;
    public final PackageManagerService packageManager;
    private final PushCompatBroker pushCompatBroker;

    private SystemServerExt(Context systemContext, PackageManagerService pm) {
        context = systemContext;
        bgHandler = BackgroundThread.getHandler();
        packageManager = pm;
        pushCompatBroker = new PushCompatBroker(
                systemContext,
                LocalServices.getService(PackageManagerInternal.class),
                LocalServices.getService(UserManagerInternal.class));
    }

    /*
     Called after system server has completed its initialization,
     but before any of the apps are started.

     Call from com.android.server.SystemServer#startOtherServices(), at the end of lambda
     that is passed into mActivityManagerService.systemReady()
     */
    public static void init(Context systemContext, PackageManagerService pm) {
        SystemServerExt sse = new SystemServerExt(systemContext, pm);
        instance = sse;
        sse.bgHandler.post(sse::initBgThread);

        BenzenedHooks.register(systemContext);

        AppCompatConf.init(systemContext);
    }

    public static boolean deliverPushCompatToken(int userId, String packageName, String token) {
        return instance.pushCompatBroker.deliverToken(userId, packageName, token);
    }

    public static boolean deliverUnifiedPushConnectorIntent(int userId, String packageName,
            String action, Bundle extras) {
        return instance.pushCompatBroker.deliverUnifiedPushConnectorIntent(
                userId, packageName, action, extras);
    }

    void initBgThread() {
        WifiAutoOff.maybeInit(this);
        BluetoothAutoOff.maybeInit(this);

        if (android.os.Flags.isDevBuild()) {
            if (!SELinuxFlags.kernelSupportsSELinuxFlags()) {
                String title = "Kernel doesn't support SELinux flags";
                String msg = "App hardening features that use SELinux flags, such as DCL and ptrace restrictions, do not work.";
                new SystemErrorNotification("missing hardening", title, msg).show(context);
            }

            String[] abis = Build.SUPPORTED_64_BIT_ABIS;
            if (abis.length > 0 && "arm64".equals(VMRuntime.getInstructionSet(abis[0]))) {
                try {
                    long size = 1L << 40;
                    long addr = Os.mmap(0, size, OsConstants.PROT_NONE,
                            OsConstants.MAP_PRIVATE | OsConstants.MAP_ANONYMOUS, null, 0);
                    Os.munmap(addr, size);
                } catch (ErrnoException e) {
                    Slog.e("ARM_VA_CHECK", "", e);
                    String title = "scudo is used instead of hardened_malloc: no kernel support for 48-bit VA";
                    String msg = Log.getStackTraceString(e);
                    new SystemErrorNotification("missing hardening", title, msg).show(context);
                }
            }
        }
    }
}
