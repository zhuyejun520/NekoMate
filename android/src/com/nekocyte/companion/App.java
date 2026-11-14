package com.nekocyte.companion;

import android.app.Application;

public class App extends Application {
    public static App I;

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        Session.init(this);
        Notify.init(this);
        DebugLog.beginSession(this);
        DebugLog.i("app", "onCreate manufacturer=" + android.os.Build.MANUFACTURER
                + " model=" + android.os.Build.MODEL
                + " api=" + android.os.Build.VERSION.SDK_INT
                + " version=" + UpdateManager.VERSION_NAME);
    }
}
