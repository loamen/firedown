package android.text;
/** Harness stand-in: the one method the code under test calls. */
public class TextUtils {
    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }
}
