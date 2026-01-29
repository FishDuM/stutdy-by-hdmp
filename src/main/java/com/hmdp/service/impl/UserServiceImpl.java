package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone) {
        // 1、校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 成立不符合
            // 2、如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3、符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4、保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5、发送验证码
        log.debug("发送手机验证码成功");
        System.out.println(code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1、校验手机号和验证码
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        // 2、不一致，报错
        String getCode = loginForm.getCode();
        if (code == null || !code.equals(getCode)){
            return Result.fail("验证码错误");
        }
        // 3、一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 4、判断用户是否存在
        if(user == null) {
        // 5、不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 6、存在，创建用户并保存到redis
        // 6.1生成token当登陆令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2将User转Hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        // 6.3存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        // 6.4设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 7返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1、创建用户
        User user = new User();
        // 2、保存到数据库
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
