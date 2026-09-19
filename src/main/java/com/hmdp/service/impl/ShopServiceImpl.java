package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        // 缓存穿透
        //Shop shop = queryWithPassThrough(id);
        // 互斥解决缓存穿透
        Shop shop = queryWithMutex(id);
        // 返回结果
        return Result.ok(shop);
    }

    public Shop queryWithPassThrough(Long id) {
        // 从缓存获得合法店铺信息
        Shop shop = getWithCache(id);
        if(shop != null) return shop;

        // 3. 不合法，查询数据库
        shop = getById(id);
        if( shop==null ){
            // 4. 店铺信息不存在，将空值写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 存入缓存,别忘记设置过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6. 返回结果
        return shop;
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        // 1. 从缓存中获取合法店铺信息
        Shop shop = getWithCache(id);
        if(shop != null) return shop;

        // 3. 不合法存在
        // 3.1 获取互斥锁
        boolean isLocked = tryLock(LOCK_SHOP_KEY + id);

        // 3.2 判断是否获取互斥锁
        if(!isLocked){
            // 获取失败，休眠重试
            Thread.sleep(50);
            queryWithMutex(id);
        }

        // 3.3 获取锁成功，查询缓存
        shop = getWithCache(id);
        if(shop != null) return shop;

        // 缓存不存在，查询数据库
        shop = getById(id);
        if( shop==null ){
            // 4. 店铺信息不存在，将空值写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 释放锁
            unlock(LOCK_SHOP_KEY + id);
            return null;
        }

        // 5. 存入缓存,别忘记设置过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 释放锁
        unlock(LOCK_SHOP_KEY + id);

        // 6. 返回结果
        return shop;
    }


    @Override
    public Result queryByType(Integer type_id,Integer current) {
        if(type_id==null || current==null) return Result.fail("type or current is null");
        // 1. 从缓存中读取分页店铺信息
        String shopsPageJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY + type_id + CURRENT_KEY + current);
        if(StrUtil.isNotBlank(shopsPageJSON)){
            // 2. 存在，直接返回
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY + type_id + CURRENT_KEY + current, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(JSONUtil.toList(shopsPageJSON, Shop.class));
        }

        // 3. 缓存不存在，读取数据库
        Page<Shop> shopsPage = query()
                .eq("type_id", type_id)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

        // 4. 存入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY + type_id + CURRENT_KEY + current, JSONUtil.toJsonStr(shopsPage), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 5. 返回结果
        return Result.ok(shopsPage);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId() == null) return Result.fail("店铺id不能为空");
        // 1. 更新数据库
        save(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private Shop getWithCache(Long id) {
        // 1. 从redis中获取店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id );

        if(StrUtil.isNotBlank(shopJson)){
            // 2. 存在
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return JSONUtil.toBean(shopJson, Shop.class);
        }else if(shopJson != null){
            // 3. 不存在，但是是空字符
            return null;
        }
        // 不存在
        return null;
    }

    /**
     * 异步刷新店铺缓存
     * @param id 店铺id
     */
    private void refreshShopCache(Long id){
        // todo完成这部分
    }
}
