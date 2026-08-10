package dev.inkflow.boox;

import android.app.Application;
import android.os.Build;

import com.onyx.android.sdk.rx.RxBaseAction;
import com.onyx.android.sdk.utils.ResManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Initializes the process exactly as required by the current Onyx pen demo. */
public final class InkflowApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ResManager.init(this);
        RxBaseAction.init(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("");
        }
    }
}
