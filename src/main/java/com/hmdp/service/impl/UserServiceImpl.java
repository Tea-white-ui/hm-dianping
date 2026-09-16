package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Random;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) throw new IllegalArgumentException("手机号格式不正确");

        // 保存验证码
        String code = String.valueOf(RandomUtil.randomNumbers(6));
        session.setAttribute("code", code);

        // 发送验证码（省略，这里只是模拟）
        log.info("发送验证码：{}给{}", code, phone);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())) return Result.fail("手机号格式不正确");
        // 校验验证码
        String code = (String) session.getAttribute("code");
        if(code == null || !code.equals(loginForm.getCode()))
            return Result.fail("验证码错误");

        // 判断用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();

        if(user == null) {
            // 用户不存在，创建新账户
            user = createUserWithPhone(loginForm.getPhone());
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        session.setAttribute("user", userDTO);

        return Result.ok();
    }

    private User createUserWithPhone(String phone){
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(5));
        // 保存用户
        save(user);
        log.info("创建新用户：{}", user.getId());
        return user;
    }
}
