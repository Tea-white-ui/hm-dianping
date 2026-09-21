package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
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

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        // 缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 逻辑过期解决缓存穿透
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回结果
        if(shop == null) return Result.fail("店铺不存在");
        return Result.ok(shop);
    }




    @Override
    public Result queryByType(Integer type_id,Integer current) {
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
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


}
