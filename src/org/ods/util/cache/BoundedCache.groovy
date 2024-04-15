package org.ods.util.cache

import com.cloudbees.groovy.cps.NonCPS

import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference

/**
 * A {@code Cache} that allows to set a maximum size.
 * Existing entries may be evicted to leave space for new ones, following the given {@code CachePolicy}.
 *
 * This implementation does not allow null keys or values.
 *
 * This implementation supports all the replacement policies in {@code CachePolicy}.
 *
 * The cache is implemented using synchronization with entry-level granularity.
 * If high concurrency levels are expected, choose a cache implementation that implements the
 * {@code ConcurrentReads} or {@code ConcurrentWrites} as needed.
 * Note that synchronized implementations may still work better than non-blocking ones
 * for extremely high levels of concurrency, where concurrent implementations are more likely to suffer starvation.
 *
 * This cache is memory sensitive by default and the garbage collector can free up individual entries
 * or even the cache as a whole. This behaviour can be disabled. Note that entries that can be freed by the
 * GC also occupy more space than regular entries.
 *
 * The garbage collector can automatically reclaim the cached values, but not the entries holding them.
 * The entries will be reclaimed by the threads calling any of the cache methods. In order to avoid
 * impacting the computational cost of the methods, a constant maximum number of entries will be reclaimed
 * in each method call.
 *
 * @param <K> the type of the keys.
 * @param <V> the type of the cached values.
 */
class BoundedCache<K, V> implements Cache<K, V>, Bounded {

    static final Object FULL = new Object()
    static final float DEFAULT_LOAD_FACTOR = 0.75f

    private final long maxSize
    private final Closure<? extends V> valueProducer
    private transient volatile Object cache = null
    private final Object mutex = new Object()
    private final Object baseMutex = new Object()
    private final CachePolicy policy
    private final int initialCapacity
    private final boolean noGC

    /**
     * Constructs a new {@code BoundedCache} instance using a closure as the value producer and fulfilling
     * the restrictions and capabilities provided by the other, optional, parameters.
     *
     * If the cache has a bounded size and no replacement policy is specified, the default replacement
     * policy is {@code CachePolicy.LRU}.
     *
     * Note that it makes no sense to set an initial capacity greater than the maximum cache size.
     *
     * @param valueProducer the closure that will generate missing values.
     * @param maxSize the optional maximum size of the cache. Unbounded if zero or omitted.
     * @param policy the replacement policy of the bounded cache, or no replacement, if omitted.
     * @param initialCapacity a optional hint to the underlying implementation about the initial number of entries
     * that the cache should be able to accommodate without resizing.
     * @param noGC if {@code true}, do not let the garbage collector free up cache entries.
     * @NullPointerException if {@code valueProducer} is null.
     * @IllegalArgumentException if either {@code maxSize} or {@code initialCapacity} is negative.
     */
    BoundedCache(Closure<? extends V> valueProducer,
                 long maxSize = 0,
                 CachePolicy policy = null,
                 int initialCapacity = 0,
                 boolean noGC = false) {
        if (valueProducer.is(null)) {
            throw new NullPointerException('valueProducer')
        }
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize == ${maxSize}")
        }
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity == ${initialCapacity}")
        }
        this.valueProducer = valueProducer
        this.maxSize = maxSize
        if (maxSize && policy.is(null)) {
            policy = CachePolicy.LRU
        }
        this.policy = policy
        if (initialCapacity) {
            initialCapacity = (int) (Math.ceil(initialCapacity / (double) DEFAULT_LOAD_FACTOR))
        }
        this.initialCapacity = initialCapacity
        this.noGC = noGC
    }

    /**
     * Gets the value corresponding to that key, generating it, if necessary.
     * This method runs in expected constant time for a cache hit.
     * The cost in case of a cache miss is the cost of generating a value using the value producer.
     * In any case, the method may block in the presence of concurrency.
     *
     * @param key the key to get the value for.
     * @return the value corresponding to the key or {@code null}, if it was a cache miss and the value producer
     * returned null.
     */
    @Override
    @NonCPS
    V get(K key) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        def lock = new Lock()
        def mutex = mutex
        def cache = getCache()
        def oldValue
        while (true) {
            oldValue = tryLock(cache, key, lock)
            // The operation takes effect here, iif oldValue is null or FULL or an instance of V.
            if (oldValue.is(null)) {
                break
            }
            if (oldValue instanceof V) {
                return oldValue
            }
            if (oldValue.is(FULL)) {
                return valueProducer(key)
            }
            assert oldValue instanceof Lock : oldValue.class.name
            while (true) {
                synchronized (oldValue) {
                    synchronized (mutex) {
                        def check = read(cache, key) // Check that the lock has not been released, yet.
                        // The operation takes effect here, iif check is an instance of V.
                        if (!oldValue.is(check)) {
                            if (check instanceof V) {
                                return check
                            }
                            oldValue = check
                            continue // Synchronize on the new lock.
                        }
                    }
                    oldValue.wait()
                    break // Resume the outer loop.
                }
            }
        }
        V value = null
        try {
            try {
                value = valueProducer(key)
            } catch (e) {
                synchronized (mutex) {
                    cache.remove(key, lock)
                }
                throw e
            }
            synchronized (mutex) {
                if (value.is(null)) {
                    cache.remove(key, lock)
                } else {
                    cache.replace(key, lock, value)
                }
            }
        } finally {
            synchronized (lock) {
                lock.notifyAll()
            }
        }
        return value
    }

    /**
     * Clears the whole cache.
     *
     * This method runs in constant time and does not block.
     */
    @Override
    @NonCPS
    void clear() {
        cache = null // The operation takes effect here.
    }

    /**
     * Clears the entry for the given key, if currently cached.
     *
     * This method runs in expected constant time, but it may block in the presence of concurrency.
     *
     * @param key the key of the entry to clear.
     * @return the value that was previously cached, or {@code null}, if there was no cached entry for that key.
     */
    @Override
    @NonCPS
    V clear(K key) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        def oldValue
        def cache = getCache()
        synchronized (mutex) {
            if (!noGC) {
                ((BaseCache<K>) cache).removeStaleEntries()
            }
            oldValue = cache.get(key)
            if (oldValue instanceof V) {
                cache.remove(key)
                return oldValue
            }
        } // The operation takes effect here.
        assert oldValue.is(null) || oldValue instanceof Lock: oldValue.class.name
        return null
    }

    /**
     * Clears the entry for the given key, if currently mapped to the given value.
     *
     * This method runs in expected constant time, but it may block in the presence of concurrency.
     *
     * @param key the key of the entry to clear.
     * @return the value that was previously cached, or {@code null}, if there was no cached entry for that key.
     */
    @Override
    @NonCPS
    boolean clear(K key, V value) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        if (value.is(null)) {
            return false
        }
        def oldValue
        def mutex = mutex
        def cache = getCache()
        while (true) {
            synchronized (mutex) {
                oldValue = read(cache, key)
                // The operation takes effect here, iff oldValue is null or an instance of V.
                def result = doClear(cache, key, value, oldValue)
                if (!result.is(null)) {
                    return result
                }
            }
            synchronized (oldValue) {
                synchronized (mutex) {
                    def check = read(cache, key)
                    // The operation takes effect here, iff check is not oldValue.
                    if (!oldValue.is(check)) {
                        def result = doClear(cache, key, value, oldValue)
                        return result.is(null) ? false : result
                        // If we found a different lock, the entry had been cleared by another thread.
                    }
                }
                oldValue.wait()
            }
        }
    }

    /**
     * Returns the number of entries currently in the cache.
     *
     * If there are still entries whose value was cleared by the garbage collector,
     * the size may also count them.
     *
     * The underlying implementation returns an {@code int} size. The maximum possible value is
     * {@code Integer.MAX_VALUE}. The maximum size of the underlying implementation is unspecified, but
     * currently smaller than {@code Integer.MAX_VALUE}, so the size will never overflow. This may change
     * in future versions of the underlying cache. Clients of this method should not rely on unspecified
     * behaviour.
     *
     * @return the number of entries currently cached.
     */
    @Override
    @NonCPS
    long size() {
        long size
        def cache = getCache()
        synchronized (mutex) {
            if (!noGC) {
                ((BaseCache<K>) cache).removeStaleEntries()
            }
            size = cache.size()
        } // The operation takes effect here.
        return size & 0xffffffffL // In case cache.size() overflows.
    }

    /**
     * Returns the maximum size of the cache.
     *
     * This is a constant value.
     *
     * @return the maximum size of the cache or zero, if unbounded.
     */
    @Override
    @NonCPS
    long maxSize() {
        if (!noGC) {
            def cache = getCache()
            synchronized (mutex) {
                ((BaseCache<K>) cache).removeStaleEntries()
            }
        }
        return maxSize
    }

    /**
     * Returns a {@code String} representation of the cached entries.
     *
     * If any entry is locked while generating its value, its value will be shown as "<locked>".
     *
     * @return a {@code String} representation of the cached entries.
     */
    @Override
    @NonCPS
    String toString() {
        def cache = getCache()
        synchronized (mutex) {
            if (!noGC) {
                ((BaseCache<K>) cache).removeStaleEntries()
            }
            return cache.toString()
        } // The operation takes effect here.
    }

    @NonCPS
    private Boolean doClear(cache, key, value, oldValue) {
        if (oldValue.is(null)) {
            return Boolean.FALSE
        }
        if (oldValue.is(value)) {
            def check = cache.remove(key)
            assert check.is(oldValue) : "${check.class.name}@${Integer.toHexString(check.hashCode())}: ${check}"
            return Boolean.TRUE
        }
        if (oldValue instanceof V) {
            return Boolean.FALSE
        }
        assert oldValue instanceof Lock : oldValue.class.name
        return null
    }

    @NonCPS
    private tryLock(BaseCache<K> cache, K key, Lock lock) {
        synchronized (mutex) {
            if (!noGC) {
                cache.removeStaleEntries()
            }
            return cache.putIfAbsent(key, lock)
        }
    }

    @NonCPS
    private read(BaseCache<K> cache, K key) {
        synchronized (mutex) {
            if (!noGC) {
                cache.removeStaleEntries()
            }
            return cache.get(key)
        }
    }

    @NonCPS
    private BaseCache<K> getCache() {
        return noGC ? getStrongCache() : getSoftCache()
    }

    @NonCPS
    private BaseCache<K> getStrongCache() {
        def cache = doGetStrongCache()
        if (!cache.is(null)) {
            return cache
        }
        synchronized (baseMutex) {
            cache = doGetStrongCache()
            if (!cache.is(null)) {
                return cache
            }
            cache = createCache()
            this.cache = cache
            return cache
        }
    }

    @NonCPS
    private BaseCache<K> doGetStrongCache() {
        def cache = cache
        assert cache.is(null) || cache instanceof BaseCache<K>: cache.class.name
        return cache
    }

    @NonCPS
    private BaseCache<K> getSoftCache() {
        def cache = doGetSoftCache()
        if(!cache.is(null)) {
            return cache
        }
        synchronized (baseMutex) {
            cache = doGetSoftCache()
            if(!cache.is(null)) {
                return cache
            }
            cache = createCache()
            this.cache = new SoftReference<BaseCache<K>>(cache)
            return cache
        }
    }

    @NonCPS
    private BaseCache<K> doGetSoftCache() {
        def cache = cache
        if (cache.is(null)) {
            return null
        }
        assert cache instanceof SoftReference<BaseCache<K>>: cache.class.name
        cache = cache.get()
        if (cache.is(null)) {
            return null
        }
        assert cache instanceof BaseCache<K>: cache.class.name
        return cache
    }

    @NonCPS
    private BaseCache<K> createCache() {
        def maxSize = maxSize
        return maxSize ? createUnboundedCache() : createBoundedCache(maxSize)
    }

    @NonCPS
    private BaseCache<K>  createUnboundedCache() {
        def initialCapacity = initialCapacity
        return new BaseCache<>(initialCapacity, noGC)
    }

    @NonCPS
    private BaseCache<K>  createBoundedCache(long maxSize) {
        def initialCapacity = initialCapacity
        BaseCache<K> cache
        switch (policy) {
            case CachePolicy.NONE:
                cache = new NoReplacementCache<K>(maxSize, initialCapacity, noGC)
                break
            case CachePolicy.FIFO:
                cache = new LRCache<K>(maxSize, initialCapacity, noGC)
                break
            case CachePolicy.LIFO:
                cache = new MRCache<K>(maxSize, initialCapacity, noGC)
                break
            case CachePolicy.LRU:
                cache = new LRCache<K>(maxSize, initialCapacity, noGC, true)
                break
            case CachePolicy.MRU:
                cache = new MRCache<K>(maxSize, initialCapacity, noGC, true)
                break
        }
        return cache
    }

    private static class BaseCache<K>  extends LinkedHashMap<K, Object> {

        private static final long serialVersionUID = 1L
        private static final int DEFAULT_INITIAL_CAPACITY = 16
        private static final int MAX_ENTRIES_TO_RECLAIM = 8;
        private final ReferenceQueue<V> referenceQueue

        BaseCache(int initialCapacity, boolean noGC, boolean accessOrder) {
            super(initialCapacity ?: DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, accessOrder)
            referenceQueue = noGC ? null : new ReferenceQueue<V>()
        }

        @Override
        @NonCPS
        Object putIfAbsent(K key, Object value) {
            def oldValue = get(key)
            if (!oldValue.is(null)) {
                return oldValue
            }
            return doPut(key, value)
        }

        @Override
        @NonCPS
        Object get(Object key) {
            removeStaleEntries()
            def value = super.get(key)
            return unwrap(value)
        }

        @Override
        @NonCPS
        Object put(K key, Object value) {
            removeStaleEntries()
            return doPut(key, value)
        }

        @NonCPS
        protected Object doPut(K key, Object value) {
            try {
                def wrapped = wrap(key, value)
                return super.put(key, wrapped)
            } finally {
                Reference.reachabilityFence(value)
            }
        }

        @Override
        @NonCPS
        Object remove(Object key) {
            removeStaleEntries()
            def oldValue = super.remove(key)
            return unwrap(oldValue)
        }

        @Override
        @NonCPS
        boolean remove(Object key, Object value) {
            removeStaleEntries()
            def oldValue = get(key)
            oldValue = unwrap(oldValue)
            if (value != oldValue) {
                return false
            }
            return super.remove(key)
        }

        @NonCPS
        void removeStaleEntries() {
            def referenceQueue = referenceQueue
            if (referenceQueue.is(null)) {
                return
            }
            SoftEntry<K, V> reference = null
            for (int i = 0; i < MAX_ENTRIES_TO_RECLAIM &&
                (reference = (SoftEntry<K, V>) referenceQueue.poll()) != null; i++) {
                super.remove(reference.key)
            }
        }

        @NonCPS
        private Object unwrap(Object value) {
            if (value instanceof SoftEntry<K, V>) {
                value = value.get()
                if (value.is(null)) {
                    removeStaleEntries()
                }
            }
            return value
        }

        @NonCPS
        private Object wrap(K key, V value) {
            def referenceQueue = referenceQueue
            if (referenceQueue.is(null) || !(value instanceof V)) {
                return value
            }
            return new SoftEntry<K, V>(key, value, referenceQueue)
        }

    }

    private static class SoftEntry<K, V> extends SoftReference<V> {

        private final K key

        SoftEntry(K key, V value, ReferenceQueue<V> referenceQueue) {
            super(value, referenceQueue)
            this.key = key
        }

        @NonCPS
        K getKey() {
            return key
        }

    }

    private static abstract class MaxSizeCache<K> extends BaseCache<K> {

        private static final long serialVersionUID = 1L
        private final long maxSize

        MaxSizeCache(long maxSize, int initialCapacity, boolean noGC, boolean accessOrder = false) {
            super(initialCapacity ?: DEFAULT_INITIAL_CAPACITY, noGC, accessOrder)
            this.maxSize = maxSize
            referenceQueue = noGC ? null : new ReferenceQueue<V>()
        }

        @Override
        @NonCPS
        Object putIfAbsent(K key, Object value) {
            def oldValue = get(key)
            if (!oldValue.is(null)) {
                return oldValue
            }
            if (size() >= maxSize) {
                return evict()
            }
            return doPut(key, value)
        }

        @NonCPS
        protected abstract Object evict()

    }

    private static class NoReplacementCache<K> extends MaxSizeCache<K> {

        private static final long serialVersionUID = 1L

        NoReplacementCache(long maxSize, int initialCapacity = 0, boolean noGC = false) {
            super(maxSize, initialCapacity, noGC)
        }

        @Override
        @NonCPS
        protected Object evict() {
            return FULL
        }
    }

    private static class LRCache<K> extends MaxSizeCache<K> {

        private static final long serialVersionUID = 1L

        LRCache(long maxSize, int initialCapacity, boolean noGC, boolean lru = false) {
            super(maxSize, initialCapacity ?: DEFAULT_INITIAL_CAPACITY, noGC, lru)
        }

        @Override
        @NonCPS
        protected Object evict() {
            def it = entrySet().iterator()
            it.next()
            it.remove()
            return null
        }

    }

    private static class MRCache<K> extends MaxSizeCache<K> {

        private static final long serialVersionUID = 1L

        MRCache(long maxSize, int initialCapacity, boolean noGC, boolean mru = false) {
            super(maxSize, initialCapacity ?: DEFAULT_INITIAL_CAPACITY, noGC, mru)
        }

        @Override
        @NonCPS
        protected Object evict() {
            def it = entrySet().iterator()
            it.next()
            while (it.hasNext()) {
                it.next() // Linear time. To do it in constant time, we need Java 21.
            }
            it.remove()
            return null
        }

    }

    private static class Lock {
        @Override
        @NonCPS
        String toString() {
            return '<locked>'
        }
    }

}
