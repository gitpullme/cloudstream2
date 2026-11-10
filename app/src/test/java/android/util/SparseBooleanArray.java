package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM test implementation of Android's SparseBooleanArray.
 */
public class SparseBooleanArray implements Cloneable {
    private final Map<Integer, Boolean> map = new HashMap<>();
    private final List<Integer> keys = new ArrayList<>();

    public SparseBooleanArray() {}

    public SparseBooleanArray(int initialCapacity) {}

    public int size() {
        return keys.size();
    }

    public boolean get(int key) {
        return get(key, false);
    }

    public boolean get(int key, boolean valueIfKeyNotFound) {
        Boolean val = map.get(key);
        return val != null ? val : valueIfKeyNotFound;
    }

    public void delete(int key) {
        if (map.containsKey(key)) {
            map.remove(key);
            keys.remove(Integer.valueOf(key));
        }
    }

    public void removeAt(int index) {
        if (index >= 0 && index < keys.size()) {
            int key = keys.remove(index);
            map.remove(key);
        }
    }

    public void put(int key, boolean value) {
        if (!map.containsKey(key)) {
            int idx = Collections.binarySearch(keys, key);
            if (idx < 0) {
                idx = ~idx;
            }
            keys.add(idx, key);
        }
        map.put(key, value);
    }

    public void append(int key, boolean value) {
        put(key, value);
    }

    public int keyAt(int index) {
        return keys.get(index);
    }

    public boolean valueAt(int index) {
        Boolean v = map.get(keys.get(index));
        return v != null && v;
    }

    public void setValueAt(int index, boolean value) {
        map.put(keys.get(index), value);
    }

    public int indexOfKey(int key) {
        return Collections.binarySearch(keys, key);
    }

    public int indexOfValue(boolean value) {
        for (int i = 0; i < keys.size(); i++) {
            if (map.get(keys.get(i)) == value) {
                return i;
            }
        }
        return -1;
    }

    public void clear() {
        map.clear();
        keys.clear();
    }

    @Override
    public SparseBooleanArray clone() {
        SparseBooleanArray clone = new SparseBooleanArray();
        for (int i = 0; i < size(); i++) {
            clone.put(keyAt(i), valueAt(i));
        }
        return clone;
    }
}
