package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
        //查redis
        String shopType = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_KEY);
        //有返回
        if (StrUtil.isNotBlank(shopType)){
            List<ShopType> list = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(list);
        }
        //无查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //无 报错
        if (typeList.isEmpty()) {
            return Result.fail("无店铺类型");
        }
        //有 给redis
        String typeJson = JSONUtil.toJsonPrettyStr(typeList);
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_KEY,typeJson);
        //返回
        return Result.ok(typeList);
    }
}
