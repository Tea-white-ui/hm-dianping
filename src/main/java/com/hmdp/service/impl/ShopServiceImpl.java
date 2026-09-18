package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.debug("店铺信息缓存读取:{}",shop.getId());
            return Result.ok(shop);
        }

        // 3. 不存在，查询数据库
        Shop shop = getById(id);
        if(shop==null) return Result.fail("店铺不存在!");

        // 4. 存如缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));

        // 5. 返回结果
        return Result.ok(shop);
    }
}
