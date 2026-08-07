package apps.ijp.coverscreen.launcher.ui;

import android.view.WindowManager;

/**
 * Static native bindings for the launcher libspark.
 * JNI_OnLoad registers all of these from the table at .data.rel.ro 0x53278,
 * so every name and descriptor here has to match the binary exactly.
 */
public final class NativeLayoutParamsFactory {

    public static boolean loaded = false;
    public static String loadError = null;

    static {
        try {
            System.loadLibrary("sparx");
            loaded = true;
        } catch (Throwable t) {
            loadError = String.valueOf(t);
        }
    }

    private NativeLayoutParamsFactory() {}

    // flash LED init, portrait
    public static native WindowManager.LayoutParams nA1();
    public static native WindowManager.LayoutParams nA2();
    public static native WindowManager.LayoutParams nA3();

    // flash LED init, reverse portrait
    public static native WindowManager.LayoutParams nB1();
    public static native WindowManager.LayoutParams nB2();
    public static native WindowManager.LayoutParams nB3();

    // flash LED init, landscape
    public static native WindowManager.LayoutParams nC1();
    public static native WindowManager.LayoutParams nC2();
    public static native WindowManager.LayoutParams nC3();

    // flash LED init, reverse landscape
    public static native WindowManager.LayoutParams nD1();
    public static native WindowManager.LayoutParams nD2();
    public static native WindowManager.LayoutParams nD3();

    // full screen portrait, flip
    public static native WindowManager.LayoutParams nE1();
    public static native WindowManager.LayoutParams nE2();

    // full screen reverse portrait, flip
    public static native WindowManager.LayoutParams nF1();
    public static native WindowManager.LayoutParams nF2();

    // full screen landscape, flip
    public static native WindowManager.LayoutParams nG1();
    public static native WindowManager.LayoutParams nG2();

    // full screen reverse landscape, flip
    public static native WindowManager.LayoutParams nH1();
    public static native WindowManager.LayoutParams nH2();

    // full screen portrait, razr
    public static native WindowManager.LayoutParams nI1();
    public static native WindowManager.LayoutParams nI2();

    // full screen reverse portrait, razr
    public static native WindowManager.LayoutParams nJ1();
    public static native WindowManager.LayoutParams nJ2();

    // full screen landscape, razr
    public static native WindowManager.LayoutParams nK1();
    public static native WindowManager.LayoutParams nK2();

    // full screen reverse landscape, razr
    public static native WindowManager.LayoutParams nL1();
    public static native WindowManager.LayoutParams nL2();

    // corner mode and background
    public static native WindowManager.LayoutParams nM1(int mode);
    public static native WindowManager.LayoutParams nM2(int mode);

    // fallbacks
    public static native WindowManager.LayoutParams nN1();
    public static native WindowManager.LayoutParams nN2();
    public static native WindowManager.LayoutParams nN3();
    public static native WindowManager.LayoutParams nN4();
    public static native WindowManager.LayoutParams nN5();

    // config loader
    public static native boolean nS1(
            String[] a,
            String[] b,
            String[] c,
            String[] d,
            String[] e,
            String[] f,
            String[] g,
            int[] h);
}
