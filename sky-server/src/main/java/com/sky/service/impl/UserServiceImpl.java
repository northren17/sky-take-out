package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.dto.UserPasswordLoginDTO;
import com.sky.dto.UserRegisterDTO;
import com.sky.entity.User;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.LoginFailedException;
import com.sky.exception.PasswordErrorException;
import com.sky.exception.RegisterFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.CaptchaService;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import com.sky.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    //微信服务接口地址
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    /** 账号长度限制 */
    private static final int USERNAME_MIN = 4;
    private static final int USERNAME_MAX = 32;
    /** 密码长度限制 */
    private static final int PASSWORD_MIN = 6;
    private static final int PASSWORD_MAX = 32;

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CaptchaService captchaService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * BCrypt 密码编码器：自带随机盐，同一密码每次加密结果都不同，
     * 因此只能用它自己的 matches() 校验，不能直接比较字符串。
     * <p>
     * 说明：员工端使用的是 MD5（{@code org.springframework.util.DigestUtils}），
     * 两边算法不同、密文格式也不同（BCrypt 密文形如 {@code $2a$10$...}），互不影响。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    public User wxLogin(UserLoginDTO userLoginDTO) {
        String openid = getOpenid(userLoginDTO.getCode());

        //判断openid是否为空，如果为空表示登录失败，抛出业务异常
        if(openid == null){
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        //判断当前用户是否为新用户
        User user = userMapper.getById(openid);

        //如果是新用户，自动完成注册
        if(user == null){
            user = User.builder()
                    .openid(openid)
                    .createTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);//后绪步骤实现
        }

        //返回这个用户对象
        return user;
    }

    @Override
    public User register(UserRegisterDTO dto) {
        String username = dto.getUsername() == null ? null : dto.getUsername().trim();
        String password = dto.getPassword();

        // 1、基础校验（不引入 validation 依赖，保持与项目现有风格一致）
        if (!StringUtils.hasText(username)) {
            throw new RegisterFailedException(MessageConstant.USERNAME_IS_NULL);
        }
        if (!StringUtils.hasText(password)) {
            throw new RegisterFailedException(MessageConstant.PASSWORD_IS_NULL);
        }
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            throw new RegisterFailedException(MessageConstant.USERNAME_LENGTH_ERROR);
        }
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new RegisterFailedException(MessageConstant.PASSWORD_LENGTH_ERROR);
        }
        if (!password.equals(dto.getConfirmPassword())) {
            throw new RegisterFailedException(MessageConstant.PASSWORD_NOT_MATCH);
        }

        // 2、校验验证码（一次性，校验后即失效）
        captchaService.verify(dto.getCaptchaKey(), dto.getCode());

        // 3、账号唯一性校验
        if (userMapper.getByUsername(username) != null) {
            throw new RegisterFailedException(MessageConstant.USERNAME_ALREADY_EXISTS);
        }

        // 4、保存用户：账号写入 username，openid 保持为空（该列留给微信登录）
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .name(StringUtils.hasText(dto.getName()) ? dto.getName().trim() : username)
                .phone(dto.getPhone())
                .createTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);

        log.info("用户注册成功：id={}, username={}", user.getId(), username);
        return user;
    }

    @Override
    public User loginByPassword(UserPasswordLoginDTO dto) {
        String username = dto.getUsername() == null ? null : dto.getUsername().trim();
        if (!StringUtils.hasText(username)) {
            throw new LoginFailedException(MessageConstant.USERNAME_IS_NULL);
        }
        if (!StringUtils.hasText(dto.getPassword())) {
            throw new LoginFailedException(MessageConstant.PASSWORD_IS_NULL);
        }

        // 1、校验验证码
        captchaService.verify(dto.getCaptchaKey(), dto.getCode());

        // 2、查用户
        User user = userMapper.getByUsername(username);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        // 微信注册的用户没有密码，无法用账号密码登录
        if (!StringUtils.hasText(user.getPassword())) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        // 3、BCrypt 校验密码
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        log.info("用户登录成功：id={}, username={}", user.getId(), username);
        return user;
    }

    @Override
    public void logout(Long userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.delete(RedisKeyConstant.USER_TOKEN + userId);
        log.info("用户退出登录：id={}", userId);
    }

    @Override
    public UserInfoVO getInfo(Long userId) {
        User user = userMapper.getByUserId(userId);
        if (user == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        return UserInfoVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .phone(user.getPhone())
                .sex(user.getSex())
                .avatar(user.getAvatar())
                .createTime(user.getCreateTime())
                .build();
    }

    /**
     * 调用微信接口服务，获取微信用户的openid
     * @param code
     * @return
     */
    private String getOpenid(String code){
        //调用微信接口服务，获得当前微信用户的openid
        Map<String, String> map = new HashMap<>();
        map.put("appid",weChatProperties.getAppid());
        map.put("secret",weChatProperties.getSecret());
        map.put("js_code",code);
        map.put("grant_type","authorization_code");
        String json = HttpClientUtil.doGet(WX_LOGIN, map);

        JSONObject jsonObject = JSON.parseObject(json);
        String openid = jsonObject.getString("openid");
        return openid;
    }
}
