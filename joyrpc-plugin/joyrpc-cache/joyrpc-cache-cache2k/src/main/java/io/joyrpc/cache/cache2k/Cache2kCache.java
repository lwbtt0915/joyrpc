package io.joyrpc.cache.cache2k;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.cache.AbstractCache;
import io.joyrpc.cache.CacheConfig;
import io.joyrpc.cache.CacheObject;

/**
 * GuavaCache
 *
 * @param <K>
 * @param <V>
 */
public class Cache2kCache<K, V> extends AbstractCache<K, V> {
    /**
     * 缓存
     */
    protected org.cache2k.Cache<K, CacheObject<V>> cache;

    /**
     * 构造函数
     *
     * @param cache
     * @param config
     */
    public Cache2kCache(org.cache2k.Cache<K, CacheObject<V>> cache, CacheConfig<K, V> config) {
        this.cache = cache;
        this.config = config == null ? new CacheConfig<>() : config;
    }

    @Override
    protected void doPut(final K key, final V value) throws Exception {
        cache.put(key, new CacheObject<>(value));
    }

    @Override
    protected CacheObject<V> doGet(final K key) throws Exception {
        return cache.get(key);
    }

    @Override
    protected void doRemove(final K key) throws Exception {
        cache.remove(key);
    }

}