package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
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
    public Result queryById(Long id) {
        if(id==null) return Result.fail("id is null");
        // 1. 从redis中获取店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id );

        if(StrUtil.isNotBlank(shopJson)){
            // 2. 存在，直接返回
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.debug("店铺信息缓存读取:{}",shop.getId());
            return Result.ok(shop);
        }

        // 3. 不存在，查询数据库
        Shop shop = getById(id);
        if(shop==null) return Result.fail("店铺不存在!");

        // 4. 存入缓存,别忘记设置过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 5. 返回结果
        return Result.ok(shop);
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
    public Result updateShop(Shop shop) {
        // 启动事务
        // todo 做完这里
        return null;
    }
}
