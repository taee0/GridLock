package apps.ijp.coverscreen.launcher;

import android.app.Application;
import android.view.WindowManager;

/**
 * Application class. The native lib hardcodes this exact path, and nV1/nW1 are
 * registered against jobject, so they have to stay instance methods here.
 */
public class CoverScreenAppLauncherApp extends Application {

    public WindowManager mW;

    static {
        try {
            System.loadLibrary("sparx");
        } catch (Throwable ignored) {
        }
    }

    /** builds LayoutParams from a signature string */
    public native WindowManager.LayoutParams nV1(String signature);

    /** binds the window manager to a display id */
    public native boolean nW1(int displayId);

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mW = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    public static CoverScreenAppLauncherApp instance;
}
