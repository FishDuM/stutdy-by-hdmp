package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.SECONDS);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //不存在返回
            return null;
        }
        // 命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期直接返回店铺信息
            return shop;
        }
        // 已过期需要缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        //成功开启独立线程实现缓存重建
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        //失败返回旧数据
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void saveShop2Redis(Long id , Long expireSeconds){
        // 1、查询店铺数据
        Shop shop = getById(id);
        // 2、封装逻辑过期时间（请先在Test中调用此方法预热数据）
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        // 此处改为了秒钟方便查看效果
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }


    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中是否为空值
        if (shopJson!=null){
            //返回错误信息
            return null;
        }
        // --实现缓存重建
        // --获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        try{
            boolean isLock = tryLock(lockKey);
            // --判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
//            //获取锁之后再次从redis查
//            shopJson = stringRedisTemplate.opsForValue().get(key);
//            //判断是否存在
//            if (StrUtil.isNotBlank(shopJson)) {
//                //存在返回
//                shop = JSONUtil.toBean(shopJson, Shop.class);
//                return shop;
//            }
            //不存在根据id查数据库
            shop = getById(id);
            if (shop == null) {
                //空值插入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //不存在返回错误
                return null;
            }
            //存在写入redis再返回
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // --释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }


    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中是否为空值
        if (shopJson!=null){
            //返回错误信息
            return null;
        }
        //不存在根据id查数据库
        Shop shop = getById(id);
        if (shop == null){
            //空值插入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //不存在返回错误
            return null;
        }
        //存在写入redis再返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
