package apps.ijp.coverrecents

/**
 * The binding class for libspark.so.
 *
 * This package name is not a choice. JNI_OnLoad inside libspark.so does a
 * FindClass on "apps/ijp/coverrecents/NativeRecentsFactory" and then
 * RegisterNatives against it. Move this file into com.tv.coverscreen and the
 * registration fails and every getter below throws. The rest of the app keeps
 * its own package; this one file is the shim the library expects.
 *
 * Every method here is registered by name and signature against the
 * JNINativeMethod table in the library. The descriptors below must match that
 * table exactly, because RegisterNatives matches on the descriptor too, and one
 * wrong descriptor aborts the whole registration, not just that method.
 *
 * Registered descriptors:
 *   gD1 gD2 gD3          ()I
 *   gL1 .. gL7           ()I
 *   gT1 .. gT8           ()F
 *   gP1 .. gP5           ()Ljava/lang/String;
 *   gR1 .. gR6           ()Ljava/lang/String;
 *   gI1                  ()I
 *   gDM1                 (Ljava/lang/Object;I)Ljava/lang/Object;
 *   nS1                  ([Ljava/lang/String;[I[Ljava/lang/String;[F[Ljava/lang/String;[Ljava/lang/String;)Z
 */
object NativeRecentsFactory {

    /** True when libspark.so loaded and its natives registered. */
    @JvmStatic
    @Volatile
    var loaded: Boolean = false
        private set

    /** Whatever went wrong, kept for the config screen. */
    @JvmStatic
    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("spark")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            loadError = e.message ?: "UnsatisfiedLinkError"
        } catch (e: Throwable) {
            loaded = false
            loadError = e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------- displays

    /** Cover display id. Lives in .bss, so it reads 0 until nS1 fills it. */
    @JvmStatic external fun gD1(): Int

    /** Main display id. 1 in the shipped binary. */
    @JvmStatic external fun gD2(): Int

    /** Cover panel height in px. 1048 in the shipped binary. */
    @JvmStatic external fun gD3(): Int

    /** DisplayManager.getDisplay(id), reflected. Pass the DisplayManager. */
    @JvmStatic external fun gDM1(displayManager: Any, displayId: Int): Any?

    // -------------------------------------------------------- window params

    /** Window type. 2032 = TYPE_ACCESSIBILITY_OVERLAY. */
    @JvmStatic external fun gL1(): Int

    /** Window flags. 0x40728. */
    @JvmStatic external fun gL2(): Int

    /** Pixel format. -3 = TRANSLUCENT. */
    @JvmStatic external fun gL3(): Int

    /** Gravity. 51 = TOP or LEFT. */
    @JvmStatic external fun gL4(): Int

    /** 80. */
    @JvmStatic external fun gL5(): Int

    /** Cover panel window height, 748. Picked over gD3 when on the cover. */
    @JvmStatic external fun gL6(): Int

    /** y nudge added to height/2, -100. */
    @JvmStatic external fun gL7(): Int

    // -------------------------------------------------------------- timings

    @JvmStatic external fun gT1(): Float
    @JvmStatic external fun gT2(): Float
    @JvmStatic external fun gT3(): Float
    @JvmStatic external fun gT4(): Float
    @JvmStatic external fun gT5(): Float
    @JvmStatic external fun gT6(): Float
    @JvmStatic external fun gT7(): Float
    @JvmStatic external fun gT8(): Float

    // ------------------------------------------------------------- packages

    @JvmStatic external fun gP1(): String
    @JvmStatic external fun gP2(): String
    @JvmStatic external fun gP3(): String
    @JvmStatic external fun gP4(): String
    @JvmStatic external fun gP5(): String

    // ----------------------------------------------------------- resource ids

    @JvmStatic external fun gR1(): String
    @JvmStatic external fun gR2(): String
    @JvmStatic external fun gR3(): String
    @JvmStatic external fun gR4(): String
    @JvmStatic external fun gR5(): String
    @JvmStatic external fun gR6(): String

    // ---------------------------------------------------------------- misc

    /** 0x7cea9000 in the shipped binary. */
    @JvmStatic external fun gI1(): Int

    /**
     * Push remote config in. Expects three key arrays and three matching value
     * arrays, normally parsed out of fetched JSON. Until this runs, gD1 reads
     * whatever .bss was zeroed to.
     */
    @JvmStatic external fun nS1(
        intKeys: Array<String>,
        intValues: IntArray,
        floatKeys: Array<String>,
        floatValues: FloatArray,
        stringKeys: Array<String>,
        stringValues: Array<String>,
    ): Boolean
}
