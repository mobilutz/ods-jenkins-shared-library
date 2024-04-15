package org.ods.util.cache

import com.cloudbees.groovy.cps.NonCPS

/**
 * A thread-safe, generic cache indexed by a key.
 *
 * This interface provides no methods for explicitly inserting elements in the cache.
 * Implementations take a {@code Closure<? extends V>} or a {@code Function<? super K, ? extends V>}
 * that will produce the values when there is a cache miss.
 *
 * In case of a miss, the cache invokes the producer, passing it a {@code K key}, and caches the returned value,
 * if not null.
 *
 * Values are considered read from the cache at the moment they are read, no matter it results in a hit or a miss.
 * In case of a miss, the read takes effect the moment the miss is detected, not when the value has been produced.
 * Therefore, if an entry is cleared while its value is being produced, any subsequent {@code get}
 * of that same key will produce a new, updated value. In other words, the cache always returns a value
 * which is at least as recent as the last time that same entry (or the whole cache) was cleared.
 * Two reads of the same key, with no clear of that key (or the whole cache) between them, that result in a cache
 * miss never produce the value for that key concurrently. The first cache miss will be the one producing the value,
 * and any other misses in the meantime will wait until the value is available (again, in the absence of clears).
 * However, a new value for the same key could be concurrently produced, if it is necessary to meet the recency
 * requirement described above because a clear happened between both reads.
 *
 * For example:
 *
 * 1. Thread A calls {@code get(key)}. It is a cache miss and the value starts being produced.
 *
 * 2. Thread B calls {@code clear(key)}. This call may succeed before 1. completes and the value has been produced.
 *
 * 3. Thread B calls {@code get(key)}. If no other thread has requested this same key between 2 and 3,
 * this call may start producing a new, updated, value, concurrently to 1.
 *
 * It is warranted in any case that the returned value is at least as recent as the clearance in 2.
 *
 *
 * {@code Cache} implementations may assume that there are less writes than reads
 * and support a lower concurrency level for writes than for reads,
 * as a cache is generally expected to have a high percentage of hits.
 *
 * Implementations are encouraged to be memory sensitive and allow the GC to reclaim space from them.
 * This may be coarse-grained (cleaning the whole cache)
 * or fine-grained (cleaning individual entries or groups of them).
 * Any memory-sensitive implementation should allow the user to disable this behaviour.
 *
 * Implementations typically do not support null keys or values.
 * They may also impose certain restrictions on the types, such as the key being {@code Comparable}.
 *
 * @param <K> the type of the keys.
 * @param <V> the type of the cached values.
 */
interface Cache<K, V> {
    /**
     * Gets the value mapped to the given {@code key}.
     *
     * If the value does not exist, it is automatically produced and cached,
     * possibly evicting another cached value (implementation dependent).
     *
     * If the produced value is {@code null}, no entry will be cached for that key.
     *
     * If an exception is thrown while producing a new value, it will be propagated by this method.
     * Exceptions may also be thrown by the implementation, for example, if the key is {@code null} or does not
     * meet the restrictions imposed by the implementation, or if the calling thread is interrupted.
     *
     * This method behaves as if it were atomic and took effect the moment the read happens (whether hit or miss).
     * If it results in a cache hit, or no value is cached for a miss, it has the memory semantics of a volatile read.
     * If it results in a miss and a value is generated and cached, it has the memory semantics of a volatile write.
     *
     *
     * Example:
     *
     *
     * Thread A --- 1 ----------- 3 ---
     *
     * Thread B -------- 2 ------- 4 ---
     *
     *
     * 1. Thread A invokes {@code get(k)}. It results in a miss and starts producing the value, but it takes effect now.
     *
     * 2. Thread B invokes {@code get(k)}. It happens after 1, so it blocks until the value has been produced in 3.
     *
     * 3. Thread A completes the {@code get(k)} and receives the produced value, which is cached.

     * 4. Thread B completes the {@code get(k)} and gets the value cached in 3. It takes effect now, not in 2.
     *
     *
     * @param key the key of the value to retrieve.
     * @return the cached value or {@code null}, if the produced value is null.
     * @throws NullPointerException if the implementation does not support null keys and {@code key} is null.
     * @throws IllegalArgumentException if the {@code key} does not meet the restrictions imposed by the implementation.
     */
    @NonCPS
    V get(K key)

    /**
     * Clears the whole cache.
     *
     * This method behaves as if it were atomic and has the memory semantics of a volatile write.
     *
     * Note that, this method may complete while other thread is executing a {@code get} and
     * producing a value. In this case, the {@code clear} happens after the {@code get}.
     *
     *
     * Example:
     *
     *
     * Thread A --- 1 --------------------------------- 5 ---
     *
     * Thread B ------- 2 ---
     *
     * Thread C ------------- 3 --------------- 4 ---
     *
     *
     * 1. Thread A invokes {@code get(k)}. It results in a miss and starts producing the value, but it takes effect now.
     *
     * 2. Thread B invokes {@code clear()}. It completes and takes effect now.
     * It happens after 1, even though that {@code get} hasn't completed, yet.
     *
     * 3. Thread C invokes {@code get(k)}. Happens after 2, so it results in a miss
     * and a new, fresh value starts being produced, concurrently to Thread A.

     * 4. Thread C completes the {@code get(k)} and gets a new, fresh value, as it happened after 2.
     * This value is cached.
     *
     * 5. Thread A completes the {@code get(k)} an receives the generated value (happened before 2).
     * However, the {@code clear()} has already taken place, so the value is not cached.
     *
     * At this point the cached value for key {@code k} is the value generated in 4.
     * Any other {@code get(k)} after 3 will get this same cached value until it's cleared.
     *
     *
     * Implementations are encouraged to remove as many objects as possible, in order to reduce unnecessary footprint,
     * and recreate them when a new value is to be cached.
     */
    @NonCPS
    void clear()

    /**
     * Clears the entry for the given key, if it exists.
     *
     * This method behaves as if it were atomic and has the memory semantics of a volatile write.
     *
     *
     * Example:
     *
     *
     * Thread A --- 1 ------------------- 4 ---
     *
     * Thread B ------- 2 ---
     *
     * Thread C ------------- 3 --------------- 5 ---
     *
     *
     * 1. Thread A invokes {@code get(k)}. It results in a miss and starts producing the value, but it takes effect now.
     *
     * 2. Thread B invokes {@code clear(k)}. It completes and takes effect now.
     * It happens after 1, even though that {@code get} hasn't completed, yet.
     *
     * 3. Thread C invokes {@code get(k)}. Happens after 2, so it results in a miss
     * and a new, fresh value starts being produced, concurrently to Thread A.

     * 4. Thread A completes the {@code get(k)} an receives the generated value (happened before 2).
     * However, the {@code clear(k)} has already taken place, so the value is not cached.
     *
     * 5. Thread C completes the {@code get(k)} and gets a new, fresh value, as it happened after 2.
     * This value is cached.
     *
     * At this point the cached value for key {@code k} is the value generated in 5.
     * Any other {@code get(k)} after 3 will get this same cached value until it's cleared.
     *
     *
     * @param key the key of the entry to be cleared.
     * @return the previous value for the given key, or {@code null}, if there was no entry to clear.
     * @throws NullPointerException if the implementation does not support null keys and {@code key} is null.
     * @throws IllegalArgumentException if the {@code key} does not meet the restrictions imposed by the implementation.
     */
    @NonCPS
    V clear(K key)

    /**
     * Clears the entry for the given key, if it is currently mapped to the given value, as per reference equality.
     *
     * This method behaves as if it were atomic and has the memory semantics of a volatile write.
     *
     * @param key the key of the entry to be cleared.
     * @param value the value to compare with the mapped value.
     * @return {@code true}, if the entry was cleared.
     * @throws NullPointerException if the implementation does not support null keys and {@code key} is null.
     * @throws IllegalArgumentException if the {@code key} or the {@code value} do not meet the restrictions
     * imposed by the implementation.
     */
    @NonCPS
    boolean clear(K key, V value)

    /**
     * Returns the number of mappings currently in the cache.
     *
     * Depending on the implementation, it may be approximate.
     *
     * This method returns a {@code long} value in order to properly support implementations
     * capable of holding more than {@code Integer.MAX_VALUE} entries.
     *
     * Note that it is currently impossible for a cache to hold more than {@code Long.MAX_VALUE} entries.
     * Actually, the practical maximum is much lower. Therefore, the size will never overflow.
     *
     * If the underlying implementation only provides an {@code int} size, this method will produce maximum
     * sizes at least as big as {@code Integer.MAX_VALUE}. Implementations are encouraged to support
     * the widest possible range of sizes and to signal an overflow by returning the maximum value plus 1,
     * if possible. In any case, the implementations must specify the range and the overflow behaviour.
     *
     * This method behaves as if it were atomic and has the memory semantics of a volatile read.
     *
     * @return the current size of the cache or the best possible approximation.
     */
    @NonCPS
    long size()
}
