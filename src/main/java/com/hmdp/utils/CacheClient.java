package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_KEY_PREFIX;

/**
 * <p>
 * 通用缓存工具类，基于 {@link StringRedisTemplate} 封装常用的缓存读写逻辑。
 * </p>
 * <p>
 * 主要提供两类缓存方案：
 * <ul>
 *     <li><b>缓存穿透防护</b>：通过缓存空字符串（短 TTL）避免无效请求反复访问数据库；</li>
 *     <li><b>缓存击穿防护</b>：通过逻辑过期 + 互斥锁 + 异步重建，保证热点数据的高并发读取性能。</li>
 * </ul>
 *
 * @author 虎哥
 */
@Getter
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造缓存客户端。
     *
     * @param stringRedisTemplate Spring 提供的 String 类型 Redis 操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * 将对象序列化为 JSON 字符串并写入 Redis，同时设置过期时间。
     *
     * @param key   缓存键
     * @param value 缓存值（会被序列化为 JSON）
     * @param ttl   过期时间数值
     * @param unit  过期时间单位
     */
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl, unit);
    }

    /**
     * 以“逻辑过期”方式写入 Redis：将数据与逻辑过期时间封装为 {@link RedisData} 后写入，且不设置 Redis 物理过期时间。
     * <p>
     * 逻辑过期用于解决缓存击穿问题：缓存不会真正失效，而是由业务代码判断是否过期，从而在过期时异步重建缓存。
     * </p>
     *
     * @param key   缓存键
     * @param value 缓存值（会被序列化为 JSON）
     * @param ttl   逻辑过期时间数值（相对于当前时间的偏移量）
     * @param unit  逻辑过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        if(value  == null){
            // 数据库不存在数据，缓存写入空字符
            setWithLogicalExpire(key, "", 2L, TimeUnit.MINUTES);
        }
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询缓存，未命中时回源数据库，并利用“缓存空值”解决缓存穿透问题。
     * <p>
     * 执行流程：
     * <ol>
     *     <li>先从 Redis 查询缓存，命中非空值直接返回；</li>
     *     <li>命中空字符串（穿透防护标记）则直接返回 {@code null}；</li>
     *     <li>未命中则调用 {@code dbFallback} 查询数据库；</li>
     *     <li>数据库无数据时写入空字符串（短 TTL）后返回 {@code null}，有数据则写入缓存并返回。</li>
     * </ol>
     *
     * @param key        缓存键前缀，实际键为 {@code key + id}
     * @param id         查询主键，会被拼接在 {@code key} 之后
     * @param type       返回结果的类型
     * @param dbFallback 缓存未命中时的数据库回源函数，入参为 id
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回结果类型
     * @param <ID>       查询主键类型
     * @return 查询到的对象；若缓存或数据库中均不存在则返回 {@code null}
     */
    public <R,ID> R queryWithPassThrough(String key, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        // 1. 从Redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key+id);
        // 2. 判断缓存是否命中
        if(StringUtils.isNotBlank(json)){
            // 缓存命中，返回数据
            return BeanUtil.toBean(json, type);
        }

        if(json!=null){
            // 缓存为空字符串
            return null;
        }
        // 3. 缓存未命中，查询数据库
        R result = dbFallback.apply(id);

        // 4. 数据库数据写入缓存
        if(result==null){
            // 数据不存在，写入空字符到缓存，并返回null
            set(key+id, "", 2L, TimeUnit.MINUTES);
            return null;
        }
        // 数据库数据存在
        set(key+id, result, time, unit);
        // 5. 返回结果
        return result;
    }

    /**
     * 查询缓存，命中后基于“逻辑过期”判断是否过期，过期时异步重建缓存（解决缓存击穿问题）。
     * <p>
     * 执行流程：
     * <ol>
     *     <li>从缓存获取数据，不存在则返回 {@code null}；</li>
     *     <li>反序列化后判断逻辑过期时间，未过期直接返回；</li>
     *     <li>已过期则尝试获取互斥锁，获取失败时直接返回旧数据（保证可用性）；</li>
     *     <li>获取成功后再做一次双重检查，仍然过期则提交异步任务重建缓存，并立即返回旧数据。</li>
     * </ol>
     * <p>
     * 注意：此方法要求缓存已通过 {@link #setWithLogicalExpire} 预热写入。
     * </p>
     *
     * @param key        缓存键前缀，实际键为 {@code key + id}
     * @param id         查询主键，会被拼接在 {@code key} 之后
     * @param type       返回结果的类型
     * @param dbFallback 缓存重建时的数据库回源函数，入参为 id
     * @param time       逻辑过期时间数值
     * @param unit       逻辑过期时间单位
     * @param <R>        返回结果类型
     * @param <ID>       查询主键类型
     * @return 缓存中的数据；缓存不存在时返回 {@code null}，过期但尚未重建完成时返回旧数据
     */
    public <R, ID> R queryWithLogicalExpire(String key, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1. 从缓存中获取信息
        String redisDataJOSN = stringRedisTemplate.opsForValue().get(key + id);

        if (!StrUtil.isNotBlank(redisDataJOSN)) {
            // 缓存未命中
            if (redisDataJOSN == null) {
                // 缓存不存在（未预热），采用互斥锁回源数据库重建缓存，并返回重建结果
                return rebuildWithMutex(key, id, type, dbFallback, time, unit);
            }
            // 命中空字符串（防穿透标记），返回空
            return null;
        }


        // 2. 缓存命中，判断是否过期
        RedisData redisData = JSONUtil.toBean(redisDataJOSN, RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            // 未过期，返回缓存信息
            return r;
        }

        // 3. 缓存已过期，获取互斥锁
        boolean isLocked = tryLock(LOCK_KEY_PREFIX + key + id);
        if(!isLocked){
            // 获取失败，返回旧数据
            return r;
        }
        // 4. 获取成功，再次检查缓存是否过期
        redisDataJOSN = stringRedisTemplate.opsForValue().get(key + id);
        if(!StrUtil.isNotBlank(redisDataJOSN)){
            // 缓存信息不存在，返回空
            return null;
        }

        // 5. 缓存命中，判断是否过期
        redisData = JSONUtil.toBean(redisDataJOSN, RedisData.class);
        r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            // 未过期，返回店铺信息
            return r;
        }

        // 6. 缓存仍然过期，异步刷新缓存
        refreshCacheAsync(key, id, dbFallback, time, unit);

        // 7. 返回旧数据
        return r;

    }


    /**
     * 查询缓存，方案"互斥锁”保证只有一个线程回源数据库重建缓存（解决缓存击穿问题）。
     * <p>
     * 执行流程：
     * <ol>
     *     <li>先从 Redis 查询缓存，命中非空值直接返回；</li>
     *     <li>命中空字符串（穿透防护标记）直接返回 {@code null}；</li>
     *     <li>未命中则尝试获取互斥锁，获取失败时休眠 50ms 后重试；</li>
     *     <li>获取成功后再做一次缓存检查，仍未命中才查询数据库并重建缓存；</li>
     *     <li>无论重建成功与否，最终都会释放锁。</li>
     * </ol>
     * <p>
     * 与 {@link #queryWithPassThrough} 的区别：高并发下仅有一个线程访问数据库，其余线程等待后复用重建结果。
     * </p>
     *
     * @param key        缓存键前缀，实际键为 {@code key + id}
     * @param id         查询主键，会被拼接在 {@code key} 之后
     * @param type       返回结果的类型
     * @param dbFallback 缓存未命中时的数据库回源函数，入参为 id
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回结果类型
     * @param <ID>       查询主键类型
     * @return 查询到的对象；若缓存或数据库中均不存在则返回 {@code null}
     */
    public <R, ID> R queryWithMutex(String key, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String cacheKey = key + id;
        String lockKey = LOCK_KEY_PREFIX + cacheKey;
        // 循环重试，避免递归调用在高竞争下导致栈溢出
        while (true) {
            // 1. 从 Redis 查询缓存
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            // 2. 命中非空值，直接返回
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            // 3. 命中空字符串，说明数据库中也不存在，直接返回 null（防穿透）
            if (json != null) {
                return null;
            }

            // 4. 尝试获取互斥锁
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取失败，说明已有线程正在重建缓存，短暂休眠后重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待缓存重建被中断", e);
                }
                continue;
            }

            // 5. 获取锁成功，进入缓存重建流程
            try {
                // 5.1 二次检查：可能已被其他线程重建完成，命中则直接返回
                json = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }

                // 5.2 缓存仍未命中，查询数据库
                R result = dbFallback.apply(id);
                if (result == null) {
                    // 数据库不存在，写入空值防穿透，并返回 null
                    set(cacheKey, "", 2L, TimeUnit.MINUTES);
                    return null;
                }

                // 5.3 数据库存在，重建缓存并返回
                set(cacheKey, result, time, unit);
                return result;
            } finally {
                // 6. 释放锁（只有成功获取锁的线程才会进入此处，避免误删他人持有的锁）
                unlock(lockKey);
            }
        }
    }

    /**
     * 缓存未预热（键不存在）时的兜底重建：用互斥锁保证并发下只有一个线程回源数据库，
     * 并按“逻辑过期”格式写入缓存，避免与 {@link #queryWithLogicalExpire} 的读取格式冲突。
     *
     * @param key        缓存键前缀，实际键为 {@code key + id}
     * @param id         查询主键
     * @param type       返回结果的类型
     * @param dbFallback 数据库回源函数
     * @param time       逻辑过期时间数值
     * @param unit       逻辑过期时间单位
     * @param <R>        返回结果类型
     * @param <ID>       查询主键类型
     * @return 重建后的数据；数据库中不存在时返回 {@code null}
     */
    private <R, ID> R rebuildWithMutex(String key, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String cacheKey = key + id;
        String lockKey = LOCK_KEY_PREFIX + cacheKey;
        // 循环重试，未抢到锁时短暂休眠，等待持锁线程重建完成后复用其结果
        while (true) {
            // 尝试获取互斥锁，保证并发下只有一个线程回源数据库
            if (!tryLock(lockKey)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待缓存重建被中断", e);
                }
                continue;
            }
            try {
                // 二次检查：可能已被其他线程重建完成
                String json = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(json)) {
                    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                    return JSONUtil.toBean((JSONObject) redisData.getData(), type);
                }
                if (json != null) {
                    return null;
                }
                // 回源数据库
                R result = dbFallback.apply(id);
                if (result == null) {
                    // 数据库不存在，写入空字符串防穿透（短 TTL）
                    set(cacheKey, "", 2L, TimeUnit.MINUTES);
                    return null;
                }
                // 按“逻辑过期”格式写入缓存并返回重建结果
                setWithLogicalExpire(cacheKey, result, time, unit);
                return result;
            } finally {
                unlock(lockKey);
            }
        }
    }

    /** 用于异步重建缓存的线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newCachedThreadPool();

    private <R, ID> void refreshCacheAsync(String key, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 6. 缓存仍然过期，异步刷新缓存
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 重建缓存
                setWithLogicalExpire(key + id, dbFallback.apply(id), time, unit);
            } catch (Exception e) {
                log.error("缓存重建出错", e);
            } finally {
                // 释放锁
                unlock(LOCK_KEY_PREFIX + key + id);
            }
        });
    }

    /**
     * 尝试获取互斥锁，用于缓存重建时的并发控制。
     *
     * @param key 锁键
     * @return 获取成功返回 {@code true}，失败返回 {@code false}
     */
    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    /**
     * 释放互斥锁。
     *
     * @param key 锁键
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
