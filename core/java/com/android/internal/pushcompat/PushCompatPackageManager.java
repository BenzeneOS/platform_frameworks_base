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

package com.android.internal.pushcompat;

import android.annotation.Nullable;
import android.app.ApplicationPackageManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Base64;

import java.util.List;

/**
 * Serves a synthetic GmsCore entry to apps whose push is relayed by PushCompat, so that
 * GoogleApiAvailability.isGooglePlayServicesAvailable() stops reporting SERVICE_MISSING and
 * nagging the user to install Google Play services. The check tests for the presence of GmsCore
 * rather than for working push, which PushCompat has already replaced.
 */
public class PushCompatPackageManager extends ApplicationPackageManager {
    private static final String GMS_CORE_PKG = "com.google.android.gms";
    private static final byte[] GMS_CORE_CERTIFICATE = Base64.decode(
            "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNV"
                    + "BAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBW"
                    + "aWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4G"
                    + "A1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQx"
                    + "CzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3Vu"
                    + "dGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9p"
                    + "ZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgC"
                    + "ggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JO"
                    + "Rland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/"
                    + "8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfY"
                    + "wXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LW"
                    + "uT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0z"
                    + "OHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Yl"
                    + "mn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14al"
                    + "oXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UE"
                    + "BxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsT"
                    + "B0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTAD"
                    + "AQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4"
                    + "rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCE"
                    + "yj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh"
                    + "5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTb"
                    + "Qe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZM"
                    + "cUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3WfMBEmh/9iFBDAaTCK",
            Base64.NO_WRAP);

    public PushCompatPackageManager(Context context, IPackageManager pm) {
        super(context, pm);
    }

    @Override
    public PackageInfo getPackageInfoAsUser(String packageName, PackageInfoFlags flags, int userId)
            throws NameNotFoundException {
        try {
            return super.getPackageInfoAsUser(packageName, flags, userId);
        } catch (NameNotFoundException e) {
            PackageInfo pi = maybeMakeGmsCorePackageInfo(packageName, flags, userId);
            if (pi == null) {
                throw e;
            }
            return pi;
        }
    }

    @Override
    public ApplicationInfo getApplicationInfoAsUser(String packageName, ApplicationInfoFlags flags,
                                                    int userId) throws NameNotFoundException {
        try {
            return super.getApplicationInfoAsUser(packageName, flags, userId);
        } catch (NameNotFoundException e) {
            ApplicationInfo ai = maybeMakeGmsCoreApplicationInfo(packageName, flags, userId);
            if (ai == null) {
                throw e;
            }
            return ai;
        }
    }

    @Nullable
    private PackageInfo maybeMakeGmsCorePackageInfo(String pkgName, PackageInfoFlags flags,
                                                    int userId) {
        if (!GMS_CORE_PKG.equals(pkgName)) {
            return null;
        }
        PackageInfo pi = new PackageInfo();
        pi.packageName = GMS_CORE_PKG;
        pi.setLongVersionCode(Integer.MAX_VALUE);
        pi.applicationInfo = makeGmsCoreApplicationInfo();

        Signature signature = new Signature(GMS_CORE_CERTIFICATE);
        if ((flags.getValue() & PackageManager.GET_SIGNATURES) != 0) {
            pi.signatures = new Signature[]{signature};
        }
        if ((flags.getValue() & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            pi.signingInfo = new SigningInfo(
                    SigningInfo.VERSION_JAR, List.of(signature), null, null);
        }
        return pi;
    }

    @Nullable
    private ApplicationInfo maybeMakeGmsCoreApplicationInfo(String pkgName,
                                                            ApplicationInfoFlags flags, int userId) {
        if (!GMS_CORE_PKG.equals(pkgName)) {
            return null;
        }
        return makeGmsCoreApplicationInfo();
    }

    private static ApplicationInfo makeGmsCoreApplicationInfo() {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = GMS_CORE_PKG;
        ai.enabled = true;
        ai.longVersionCode = Integer.MAX_VALUE;
        ai.versionCode = Integer.MAX_VALUE;
        return ai;
    }
}
