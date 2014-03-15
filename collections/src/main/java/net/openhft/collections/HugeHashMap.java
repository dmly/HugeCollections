/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;

import net.openhft.lang.LongHashable;
import net.openhft.lang.Maths;
import net.openhft.lang.io.DirectBytes;
import net.openhft.lang.io.DirectStore;
import net.openhft.lang.io.MultiStoreBytes;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.io.serialization.BytesMarshallable;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * User: plawrey Date: 07/12/13 Time: 10:38
 */
@SuppressWarnings("ALL")
public class HugeHashMap<K, V> extends AbstractMap<K, V> implements HugeMap<K, V> {
    private final Segment<K, V>[] segments;
    private final Hasher hasher;
    //    private final Class<K> kClass;
//    private final Class<V> vClass;

    transient Set<Map.Entry<K, V>> entrySet;
    transient Set<K> keySet;
    transient Collection<V> values;



    public HugeHashMap() {
        this(HugeConfig.DEFAULT, (Class<K>) Object.class, (Class<V>) Object.class);
    }

    public HugeHashMap(HugeConfig config, Class<K> kClass, Class<V> vClass) {
//        this.kClass = kClass;
//        this.vClass = vClass;
        final int segmentCount = config.getSegments();
        hasher = new Hasher(kClass, segmentCount);
        boolean bytesMarshallable = BytesMarshallable.class.isAssignableFrom(vClass);
        //noinspection unchecked
        segments = (Segment<K, V>[]) new Segment[segmentCount];
        for (int i = 0; i < segmentCount; i++)
            segments[i] = new Segment<K, V>(config, hasher, CharSequence.class.isAssignableFrom(kClass), bytesMarshallable, vClass);
    }

    @Override
    public V put(K key, V value) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        segments[segment].put(segmentHash, key, value, true, true);
        return null;
    }

    @Override
    public V get(Object key) {
        return get((K) key, null);
    }

    @Override
    public V get(K key, V value) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segment].get(segmentHash, key, value);
    }

    @Override
    public V remove(Object key) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        segments[segment].remove(segmentHash, (K) key);
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segment].containsKey(segmentHash, (K) key);
    }

    @NotNull
    @Override
    public Set<Entry<K, V>> entrySet() {
        return (entrySet != null) ? entrySet : (entrySet = new EntrySet());
    }

    @NotNull
    @Override
    public Set<K> keySet() {
        return (keySet != null) ? keySet : (keySet = new KeySet());
    }

    @NotNull
    @Override
    public Collection<V> values() {
        return (values != null) ? values : (values = new Values());
    }

    @Override
    public V putIfAbsent(@NotNull K key, V value) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        segments[segment].put(segmentHash, key, value, false, true);
        return null;
    }

    @Override
    public boolean remove(@NotNull Object key, Object value) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        Segment<K, V> segment2 = segments[segment];
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (segment2) {
            V value2 = get(key);
            if (value2 != null && value.equals(value2)) {
                segment2.remove(segmentHash, (K) key);
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean replace(@NotNull K key, @NotNull V oldValue, @NotNull V newValue) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        Segment<K, V> segment2 = segments[segment];
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (segment2) {
            V value2 = get(key);
            if (value2 != null && oldValue.equals(value2)) {
                segment2.put(segmentHash, key, newValue, true, true);
                return true;
            }
            return false;
        }
    }

    @Override
    public V replace(@NotNull K key, @NotNull V value) {
        long hash = hasher.hash(key);
        int segment = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        segments[segment].put(segmentHash, key, value, true, false);
        return null;
    }

    @Override
    public boolean isEmpty() {
        for (Segment<K, V> segment : segments) {
            if (segment.size() > 0)
                return false;
        }
        return true;
    }

    @Override
    public int size() {
        long total = 0;
        for (Segment<K, V> segment : segments) {
            total += segment.size();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    @Override
    public long offHeapUsed() {
        long total = 0;
        for (Segment<K, V> segment : segments) {
            total += segment.offHeapUsed();
        }
        return total;
    }

    @Override
    public void clear() {
        for (Segment<K, V> segment : segments) {
            segment.clear();
        }
    }

    static final class Hasher<K> {

        private final boolean isCharSequence;

        private final boolean isLongHashable;

        private final int segmentMask, segmentShift;

        Hasher(Class keyClass, int segmentCount) {
            this.isCharSequence = CharSequence.class.isAssignableFrom(keyClass);
            this.isLongHashable = LongHashable.class.isAssignableFrom(keyClass);
            this.segmentMask = segmentCount - 1;
            this.segmentShift = Maths.intLog2(segmentCount);
        }

        final long hash(K key) {
            long hash;
            if (isCharSequence) {
                hash = Maths.hash((CharSequence) key);
            } else if (isLongHashable) {
                hash = ((LongHashable) key).longHashCode();
            } else {
                hash = (long) key.hashCode() << 31;
            }
            hash += (hash >>> 42) - (hash >>> 21);
            hash += (hash >>> 14) - (hash >>> 7);
            return hash;
        }

        final int segmentHash(long hash) {
            return (int) (hash >>> segmentShift);
        }

        public final int getSegment(long hash) {
            return (int) (hash & segmentMask);
        }
    }

    static class Segment<K, V> {
        final VanillaBytesMarshallerFactory bmf = new VanillaBytesMarshallerFactory();
        final IntIntMultiMap smallMap;
        final Map<K, DirectStore> map = new HashMap<K, DirectStore>();
        final DirectBytes tmpBytes;
        final MultiStoreBytes bytes = new MultiStoreBytes();
        final DirectStore store;
        final BitSet usedSet;
        final int smallEntrySize;
        final int entriesPerSegment;
        final boolean csKey;
        final Hasher hasher;
        final StringBuilder sbKey;
        final boolean bytesMarshallable;
        final Class<V> vClass;
        long offHeapUsed = 0;
        long size = 0;

        Segment(HugeConfig config, Hasher hasher, boolean csKey, boolean bytesMarshallable, Class<V> vClass) {
            this.csKey = csKey;
            this.hasher = hasher;
            this.bytesMarshallable = bytesMarshallable;
            this.vClass = vClass;
            smallEntrySize = (config.getSmallEntrySize() + 7) & ~7; // round to next multiple of 8.
            entriesPerSegment = config.getEntriesPerSegment();
            store = new DirectStore(bmf, smallEntrySize * entriesPerSegment, false);
            usedSet = new BitSet(config.getEntriesPerSegment());
            smallMap = new VanillaIntIntMultiMap(entriesPerSegment * 2);
            tmpBytes = new DirectStore(bmf, 64 * smallEntrySize, false).createSlice();
            offHeapUsed = tmpBytes.capacity() + store.size();
            sbKey = csKey ? new StringBuilder() : null;
        }

        synchronized void put(int hash, K key, V value, boolean ifPresent, boolean ifAbsent) {
            // search for the previous entry
            int h = smallMap.startSearch(hash);
            boolean foundSmall = false, foundLarge = false;
            while (true) {
                int pos = smallMap.nextPos();
                if (pos < 0) {
                    K key2 = key instanceof CharSequence ? (K) key.toString() : key;
                    final DirectStore store = map.get(key2);
                    if (store == null) {
                        if (ifPresent && !ifAbsent)
                            return;
                        break;
                    }
                    if (ifAbsent)
                        return;
                    bytes.storePositionAndSize(store, 0, store.size());
                    foundLarge = true;
                    break;
                } else {
                    bytes.storePositionAndSize(store, pos * smallEntrySize, smallEntrySize);
                    K key2 = getKey();
                    if (equals(key, key2)) {
                        if (ifAbsent && !ifPresent)
                            return;
                        foundSmall = true;
                        break;
                    }
                }
            }

            tmpBytes.clear();
            if (csKey)
                //noinspection ConstantConditions
                tmpBytes.writeUTFΔ((CharSequence) key);
            else
                tmpBytes.writeObject(key);
            long startOfValuePos = tmpBytes.position();
            if (bytesMarshallable)
                ((BytesMarshallable) value).writeMarshallable(tmpBytes);
            else
                tmpBytes.writeObject(value);
            long size = tmpBytes.position();
            if (size <= smallEntrySize) {
                if (foundSmall) {
                    bytes.position(0);
                    bytes.write(tmpBytes, 0, size);
                    return;
                } else if (foundLarge) {
                    remove(hash, key);
                }
                // look for a free spot.
                int position = h & (entriesPerSegment - 1);
                int free = usedSet.nextClearBit(position);
                if (free >= entriesPerSegment)
                    free = usedSet.nextClearBit(0);
                if (free < entriesPerSegment) {
                    bytes.storePositionAndSize(store, free * smallEntrySize, smallEntrySize);
                    bytes.write(tmpBytes, 0, size);
                    smallMap.put(h, free);
                    usedSet.set(free);
                    this.size++;
                    return;
                }
            }
            if (foundSmall) {
                remove(hash, key);
            } else if (foundLarge) {
                // can it be reused.
                if (bytes.capacity() <= size || bytes.capacity() - size < (size >> 3)) {
                    bytes.write(tmpBytes, startOfValuePos, size);
                    return;
                }
                remove(hash, key);
            }
            size = size - startOfValuePos;
            DirectStore store = new DirectStore(bmf, size);
            bytes.storePositionAndSize(store, 0, size);
            bytes.write(tmpBytes, startOfValuePos, size);
            K key2 = key instanceof CharSequence ? (K) key.toString() : key;
            map.put(key2, store);
            offHeapUsed += size;
            this.size++;
        }

        synchronized V get(int hash, K key, V value) {
            smallMap.startSearch(hash);
            while (true) {
                int pos = smallMap.nextPos();
                if (pos < 0) {
                    K key2 = key instanceof CharSequence ? (K) key.toString() : key;
                    final DirectStore store = map.get(key2);
                    if (store == null)
                        return null;
                    bytes.storePositionAndSize(store, 0, store.size());
                    break;
                } else {
                    bytes.storePositionAndSize(store, pos * smallEntrySize, smallEntrySize);
                    K key2 = getKey();
                    if (equals(key, key2))
                        break;
                }
            }
            if (bytesMarshallable) {
                try {
                    V v = value == null ? (V) NativeBytes.UNSAFE.allocateInstance(vClass) : value;
                    ((BytesMarshallable) v).readMarshallable(bytes);
                    return v;
                } catch (InstantiationException e) {
                    throw new AssertionError(e);
                }
            }
            return (V) bytes.readObject();
        }

        synchronized Entry<K, V> getNextEntry(K prevKey) {
            try {
                int pos;
                if (prevKey == null) {
                    pos = smallMap.firstPos();
                } else {
                    int hash = hasher.segmentHash(hasher.hash(prevKey));
                    pos = smallMap.nextKeyAfter(hash);
                }
                while (true) {
                    if (pos < 0) {
                        return null;
                    } else {
                        bytes.storePositionAndSize(store, pos * smallEntrySize, smallEntrySize);
                        K key = getKey();
                        if (prevKey == null || !equals(key, prevKey)) {
                            if (bytesMarshallable) {
                                V value = (V) NativeBytes.UNSAFE.allocateInstance(vClass);
                                ((BytesMarshallable) value).readMarshallable(bytes);
                                return new SimpleEntry<K, V>(key, value);
                            } else {
                                V value = (V) bytes.readObject();
                                return new SimpleEntry<K, V>(key, value);
                            }
                        }
                    }
                    pos = smallMap.nextPos();
                }
            } catch (InstantiationException e) {
                throw new AssertionError(e);
            }
        }

        boolean equals(K key, K key2) {
            return csKey ? equalsCS((CharSequence) key, (CharSequence) key2) : key.equals(key2);
        }

        static boolean equalsCS(CharSequence key, CharSequence key2) {
            if (key.length() != key2.length())
                return false;
            for (int i = 0; i < key.length(); i++)
                if (key.charAt(i) != key2.charAt(i))
                    return false;
            return true;
        }

        K getKey() {
            if (csKey) {
                sbKey.setLength(0);
                bytes.readUTFΔ(sbKey);
                return (K) sbKey;
            }
            return (K) bytes.readObject();
        }

        synchronized boolean containsKey(int hash, K key) {
            smallMap.startSearch(hash);
            while (true) {
                int pos = smallMap.nextPos();
                if (pos < 0) {
                    K key2 = key instanceof CharSequence ? (K) key.toString() : key;
                    return map.containsKey(key2);
                }
                bytes.storePositionAndSize(store, pos * smallEntrySize, smallEntrySize);
                K key2 = getKey();
                if (equals(key, key2)) {
                    return true;
                }
            }
        }

        synchronized boolean remove(int hash, K key) {
            int h = smallMap.startSearch(hash);
            boolean found = false;
            while (true) {
                int pos = smallMap.nextPos();
                if (pos < 0) {
                    break;
                }
                bytes.storePositionAndSize(store, pos * smallEntrySize, smallEntrySize);
                K key2 = getKey();
                if (equals(key, key2)) {
                    usedSet.clear(pos);
                    smallMap.remove(h, pos);
                    found = true;
                    this.size--;
                    break;
                }
            }
            K key2 = key instanceof CharSequence ? (K) key.toString() : key;
            DirectStore remove = map.remove(key2);
            if (remove == null)
                return found;
            offHeapUsed -= remove.size();
            remove.free();
            this.size--;
            return true;
        }

        synchronized long offHeapUsed() {
            return offHeapUsed;
        }

        synchronized long size() {
            return this.size;
        }

        synchronized void clear() {
            usedSet.clear();
            smallMap.clear();
            for (DirectStore directStore : map.values()) {
                directStore.free();
            }
            map.clear();
            size = 0;
        }
    }

    class AbstractIterator {

        int segmentIndex = segments.length - 1;

        Entry<K, V> nextEntry, lastReturned;

        K lastSegmentKey;

        AbstractIterator() {
            nextEntry = nextSegmentEntry();
        }

        public boolean hasNext() {
            return nextEntry != null;
        }

        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            HugeHashMap.this.remove(lastReturned.getKey());
            lastReturned = null;
        }

        Map.Entry<K, V> nextEntry() {
            Entry<K, V> e = nextEntry;
            if (e == null)
                throw new NoSuchElementException();
            lastReturned = e; // cannot assign until after null check
            nextEntry = nextSegmentEntry();
            return e;
        }

        Entry<K, V> nextSegmentEntry() {
            while (segmentIndex >= 0) {
                Segment<K, V> segment = segments[segmentIndex];
                Entry<K, V> entry = segment.getNextEntry(lastSegmentKey);
                if (entry == null) {
                    segmentIndex--;
                    lastSegmentKey = null;
                } else {
                    lastSegmentKey = entry.getKey();
                    return entry;
                }
            }
            return null;
        }

    }

    final class KeyIterator extends AbstractIterator implements Iterator<K> {

        public K next() {
            return nextEntry().getKey();
        }

    }

    final class ValueIterator extends AbstractIterator implements Iterator<V> {

        public V next() {
            return nextEntry().getValue();
        }

    }

    final class EntryIterator extends AbstractIterator implements Iterator<Entry<K, V>> {

        public Map.Entry<K, V> next() {
            return nextEntry();
        }

    }

    final class KeySet extends AbstractSet<K> {
        public Iterator<K> iterator() {
            return new KeyIterator();
        }
        public int size() {
            return HugeHashMap.this.size();
        }
        public boolean isEmpty() {
            return HugeHashMap.this.isEmpty();
        }
        public boolean contains(Object o) {
            return HugeHashMap.this.containsKey(o);
        }
        public boolean remove(Object o) {
            return HugeHashMap.this.remove(o) != null;
        }
        public void clear() {
            HugeHashMap.this.clear();
        }
    }

    final class Values extends AbstractCollection<V> {
        public Iterator<V> iterator() {
            return new ValueIterator();
        }
        public int size() {
            return HugeHashMap.this.size();
        }
        public boolean isEmpty() {
            return HugeHashMap.this.isEmpty();
        }
        public boolean contains(Object o) {
            return HugeHashMap.this.containsValue(o);
        }
        public void clear() {
            HugeHashMap.this.clear();
        }
    }

    final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            V v = HugeHashMap.this.get(e.getKey());
            return v != null && v.equals(e.getValue());
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return HugeHashMap.this.remove(e.getKey(), e.getValue());
        }

        public int size() {
            return HugeHashMap.this.size();
        }

        public boolean isEmpty() {
            return HugeHashMap.this.isEmpty();
        }

        public void clear() {
            HugeHashMap.this.clear();
        }
    }
}
