package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM test implementation of Android's SparseArray.
 */
public class SparseArray<E> implements Cloneable {
    private final Map<Integer, E> map = new HashMap<>();
    private final List<Integer> keys = new ArrayList<>();

    public SparseArray() {}

    public SparseArray(int initialCapacity) {}

    public int size() {
        return keys.size();
    }

    public E get(int key) {
        return get(key, null);
    }

    public E get(int key, E valueIfKeyNotFound) {
        if (!map.containsKey(key)) {
            return valueIfKeyNotFound;
        }
        return map.get(key);
    }

    public void delete(int key) {
        remove(key);
    }

    public void remove(int key) {
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

    public void put(int key, E value) {
        if (!map.containsKey(key)) {
            int idx = Collections.binarySearch(keys, key);
            if (idx < 0) {
                idx = ~idx;
            }
            keys.add(idx, key);
        }
        map.put(key, value);
    }

    public void append(int key, E value) {
        put(key, value);
    }

    public int keyAt(int index) {
        return keys.get(index);
    }

    public E valueAt(int index) {
        return map.get(keys.get(index));
    }

    public void setValueAt(int index, E value) {
        map.put(keys.get(index), value);
    }

    public int indexOfKey(int key) {
        return Collections.binarySearch(keys, key);
    }

    public int indexOfValue(E value) {
        for (int i = 0; i < keys.size(); i++) {
            E v = map.get(keys.get(i));
            if (v == value || (value != null && value.equals(v))) {
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
    @SuppressWarnings("unchecked")
    public SparseArray<E> clone() {
        SparseArray<E> clone = new SparseArray<>();
        for (int i = 0; i < size(); i++) {
            clone.put(keyAt(i), valueAt(i));
        }
        return clone;
    }
}
