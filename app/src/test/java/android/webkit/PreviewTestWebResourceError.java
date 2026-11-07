package android.webkit;

/** Test-only fixture: the framework constructor is package-private. */
public final class PreviewTestWebResourceError extends WebResourceError {
    @Override
    public int getErrorCode() {
        return -2;
    }

    @Override
    public CharSequence getDescription() {
        return "fixture failure";
    }
}
