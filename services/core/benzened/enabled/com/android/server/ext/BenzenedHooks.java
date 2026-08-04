package com.android.server.ext;

import android.content.Context;
import android.os.ServiceManager;

/** @hide */
public final class BenzenedHooks {
    private BenzenedHooks() {}

    public static void register(Context systemContext) {
        ServiceManager.addService(BenzenedGrantsService.SERVICE_NAME,
                new BenzenedGrantsService(systemContext));
    }
}
