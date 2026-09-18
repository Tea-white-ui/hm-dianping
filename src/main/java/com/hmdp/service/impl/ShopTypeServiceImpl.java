package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 从缓存中读取数据
        String typeListJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST_KEY);

        if (StrUtil.isNotBlank(typeListJSON)) {
            // 2. 存在，直接返回
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_LIST_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
            return Result.ok(JSONUtil.toList(typeListJSON,ShopType.class));
        }

        // 3. 不存在，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        // 4. 存入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopTypes), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        // 5. 返回结果
        return Result.ok(shopTypes);
    }
}
