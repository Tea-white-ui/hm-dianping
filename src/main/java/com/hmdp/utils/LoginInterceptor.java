package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {
    // 这里不能使用Resource和Autowired，必须使用构造器注入

    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) return false;// token为空，拦截

        //2. 获得redis中的用户信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object,Object> user = stringRedisTemplate.opsForHash().entries(key);
        if(user.isEmpty()){
            // 用户不存在，拦截
            response.setStatus(401);
            return false;
        }
        //3. 将获得的Mpa类型userDTO转成UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);

        //4.用户存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //5. 别忘了要刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }

}
