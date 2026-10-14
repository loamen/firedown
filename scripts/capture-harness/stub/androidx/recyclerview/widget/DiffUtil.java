package androidx.recyclerview.widget; public class DiffUtil {
  public abstract static class ItemCallback<T> { public abstract boolean areItemsTheSame(T a, T b); public abstract boolean areContentsTheSame(T a, T b);
    public Object getChangePayload(T a, T b) { return null; } } }
