package android.ext.settings.app;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

/** @hide */
public class AswBenzenedRoot extends AppSwitch {
    public static final AswBenzenedRoot I = new AswBenzenedRoot();

    private AswBenzenedRoot() {
        gosPsFlag = GosPackageStateFlag.BENZENED_ROOT;
        gosPsFlagNonDefault = GosPackageStateFlag.BENZENED_ROOT_NON_DEFAULT;
    }

    public static boolean isEnabledFor(Context ctx, @UserIdInt int userId, ApplicationInfo appInfo,
                                       GosPackageState gosPackageState) {
        return AswBenzenedRoot.I.get(ctx, userId, appInfo, gosPackageState);
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return false;
    }
}
