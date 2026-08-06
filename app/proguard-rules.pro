# Release logging must not carry buffer or chat content (SPEC 9.2).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
