package org.apache.commons.collections4;
import java.util.*;
public class CollectionUtils {
  public static boolean isEqualCollection(Collection<?> a, Collection<?> b) {
    if (a == null || b == null) throw new NullPointerException();
    if (a.size() != b.size()) return false;
    Map<Object,Integer> m = new HashMap<>();
    for (Object o : a) m.merge(o, 1, Integer::sum);
    for (Object o : b) { Integer c = m.get(o); if (c == null || c == 0) return false; m.put(o, c - 1); }
    return true;
  }
}
