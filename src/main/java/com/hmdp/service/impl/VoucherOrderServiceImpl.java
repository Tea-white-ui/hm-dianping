package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public Result seckillVoucher(Long voucherId) {

        // 1. 查询优惠卷（注意：库存、开始/结束时间都在 tb_seckill_voucher 表，需用 SeckillVoucher 查询）
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 2. 判断秒杀是否开始或结束
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断优惠卷是否过期
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠卷已过期");
        }
        // 4. 判断库存是否充足
        if(voucher.getStock() <= 0) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 5. 一人一单，套上悲观锁，锁需要在被事务管理的方法外，防止事务还没提交锁就已经释放
        synchronized (userId.toString().intern()) { // .intern是为了字符串对象归一化，让锁住的是同一个对象
            // 为了防止事务失效，需要用Spring注入本service再调用方法
            return voucherOrderService.createVoucherOrder(voucherId);
        }

    }
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. 判断用户是否已经下单
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("一名用户只能购买一张优惠券");
        }

        // 2. 扣减库存（乐观锁：stock > 0 作为条件，防止超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 3. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("voucher:order"));// 订单id
        voucherOrder.setUserId(userId);// 用户id
        voucherOrder.setVoucherId(voucherId);// 代金卷id
        voucherOrder.setPayType(1);

        save(voucherOrder);
        return Result.ok(voucherOrder.getId());

    }
}
