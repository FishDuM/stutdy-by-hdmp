package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService poxy;

    private static final DefaultRedisScript<Long> SECKILL_STOCK_KEY;

    static {
        SECKILL_STOCK_KEY = new DefaultRedisScript<>();
        SECKILL_STOCK_KEY.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_STOCK_KEY.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXEUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXEUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run(){
            while (true){
                try {
                    // 1、获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2、创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            // 获取锁失败
            log.error("不允许重复下单");
        }
        try {
            poxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1、执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_STOCK_KEY,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2、判断结果是否为0
        int r = result.intValue();
        if (r!= 0){
            // 2.1 不为0 没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        // 2.2 为0 有购买资格 把下单信息保存到阻塞队列中
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 获取代理对象
        poxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
    
    
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2、判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3、判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        // 4、判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
//        // 创建锁对象
/// /        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            // 获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService poxy = (IVoucherOrderService) AopContext.currentProxy();
//            return poxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            // 用户购买过了
            log.error("用户购买过一次了");
        }
        // 5、扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
        }
        // 6、创建订单
        save(voucherOrder);
    }
}
