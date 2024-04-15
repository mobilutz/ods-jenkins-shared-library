package org.ods.util.cache

import com.cloudbees.groovy.cps.NonCPS

import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 *
 * @param <K> the type of the keys.
 * @param <V> the type of the cached values.
 */
class ConcurrentCache<K, V> implements Cache<K, V>, ConcurrentReads {

    private final Closure<? extends V> valueProducer
    private transient volatile Object cache
    private final Object mutex = new Object()
    private final int initialCapacity
    private final boolean noGC

    ConcurrentCache(Closure<? extends V> valueProducer, int initialCapacity = 0, boolean noGC = false) {
        if (valueProducer.is(null)) {
            throw new NullPointerException('valueProducer')
        }
        if (initialCapacity < 0) {
            throw new IllegalArgumentException('initialCapacity < 0')
        }
        this.valueProducer = valueProducer
        this.initialCapacity = initialCapacity
        this.noGC = noGC
    }

    @Override
    @NonCPS
    V get(K key) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        def cache = getCache()
        def lock = new Lock()
        while (true) {
            def valueOrLock = cache.putIfAbsent(key, lock)
            if (valueOrLock.is(null)) {
                break
            }
            if (valueOrLock instanceof V) {
                return valueOrLock
            }
            assert valueOrLock instanceof Lock : valueOrLock.class.name
            // Another thread is holding the lock.
            valueOrLock.wait()
        }
        def check = null
        V value = null
        try {
            value = valueProducer(key)
            check = value.is(null) ? cache.remove(key) : cache.put(key, value)
        } catch (e) {
            try {
                check = cache.remove(key)
            } catch (suppressed) {
                e.addSuppressed(suppressed)
            }
            throw e
        } finally {
            lock.notifyAll()
        }
        assert check.is(lock): check.class.name
        return value
    }

    @Override
    @NonCPS
    void clear() {
        cache = null
    }

    @Override
    @NonCPS
    V clear(K key) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        def cache = getCache()
        def oldValue = cache.get(key)
        assert oldValue.is(null) || oldValue instanceof V || oldValue instanceof Lock : oldValue.class.name
        if (oldValue instanceof V &&
            cache.remove(key, oldValue)) {
            return oldValue
        }
        return null
    }

    @Override
    @NonCPS
    boolean clear(K key, V value) {
        if (key.is(null)) {
            throw new NullPointerException('key')
        }
        if (value.is(null)) {
            throw new NullPointerException('value')
        }
        return getCache().remove(key, value)
    }

    @Override
    @NonCPS
    long size() {
        getCache().size()
    }

    @Override
    @NonCPS
    String toString() {
        getCache().toString()
    }

    @NonCPS
    private ConcurrentMap<K, Object> getCache() {
        return noGC ? getStrongCache() : getSoftCache()
    }

    @NonCPS
    private ConcurrentMap<K, Object> getStrongCache() {
        def cache = (ConcurrentMap<K, Object>) cache
        if (!cache.is(null)) {
            return cache
        }
        synchronized (mutex) {
            cache = (ConcurrentMap<K, Object>) this.cache
            if (!cache.is(null)) {
                return cache
            }
            cache = createCache()
            this.cache = cache
        }
        return cache
    }

    @NonCPS
    private ConcurrentMap<K, Object> getSoftCache() {
        def softCache = (SoftReference<ConcurrentMap<K, Object>>) cache
        def cache
        if (softCache.is(null) || (cache = softCache.get()).is(null)) {
            synchronized (mutex) {
                def newSoftCache = (SoftReference<ConcurrentMap<K, Object>>) this.cache
                if (newSoftCache.is(null) || newSoftCache.is(softCache) || (cache = newSoftCache.get()).is(null)) {
                    cache = createCache()
                    this.cache = new SoftReference<ConcurrentMap<K, Object>>(cache)
                }
            }
        }
        return cache
    }

    @NonCPS
    private ConcurrentMap<K, Object> createCache() {
        def initialCapacity = initialCapacity
        if (initialCapacity) {
            return new ConcurrentHashMap<>(initialCapacity)
        }
        return new ConcurrentHashMap<>()
    }

    private static class Lock {
        @Override
        @NonCPS
        String toString() {
            return '<locked>'
        }
    }

}
