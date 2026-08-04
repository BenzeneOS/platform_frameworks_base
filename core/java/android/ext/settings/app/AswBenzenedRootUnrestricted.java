package android.ext.settings.app;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/** @hide */
public class AswBenzenedRootUnrestricted extends AppSwitch {
    public static final AswBenzenedRootUnrestricted I = new AswBenzenedRootUnrestricted();

    private AswBenzenedRootUnrestricted() {
        gosPsFlag = GosPackageStateFlag.BENZENED_ROOT_UNRESTRICTED;
        gosPsFlagNonDefault = GosPackageStateFlag.BENZENED_ROOT_UNRESTRICTED_NON_DEFAULT;
    }

    public static boolean isEnabledFor(Context ctx, @UserIdInt int userId, ApplicationInfo appInfo,
                                       GosPackageState gosPackageState) {
        return AswBenzenedRootUnrestricted.I.get(ctx, userId, appInfo, gosPackageState);
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return false;
    }
}
