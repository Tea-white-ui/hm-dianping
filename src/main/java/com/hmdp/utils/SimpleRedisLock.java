package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{
    private final String name;
    private static final String KEY_PREFIX = "lock:";

    private final StringRedisTemplate stringRedisTemplate;

    private static final String ID_PREFIX = UUID.randomUUID() + "-";// 全局唯一前缀，跨JVM也不会重复
    private final String id = ID_PREFIX + Thread.currentThread().getId();// 锁的唯一标识：UUID + 线程ID

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id, timeoutSec, TimeUnit.SECONDS);

        return success != null && success;
    }
    @Override
    public void unlock() {
        // 防止误删锁
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(lockId == null) return;
        if(id.equals(lockId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
