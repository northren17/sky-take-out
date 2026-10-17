package com.sky.controller.user;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.context.BaseContext;
import com.sky.dto.UserLoginDTO;
import com.sky.dto.UserPasswordLoginDTO;
import com.sky.dto.UserRegisterDTO;
import com.sky.entity.User;
import com.sky.properties.JwtProperties;
import com.sky.result.Result;
import com.sky.service.CaptchaService;
import com.sky.service.UserService;
import com.sky.utils.JwtUtil;
import com.sky.vo.CaptchaVO;
import com.sky.vo.UserInfoVO;
import com.sky.vo.UserLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user/user")
@Tag(name = "C端用户相关接口")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private CaptchaService captchaService;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 图形验证码
     *
     * @return 验证码标识 + Base64 图片
     */
    @GetMapping("/captcha")
    @Operation(summary = "获取图形验证码")
    public Result<CaptchaVO> captcha() {
        return Result.success(captchaService.generate());
    }

    /**
     * 用户注册
     *
     * @param userRegisterDTO 账号、密码、确认密码、验证码
     * @return 注册成功后的登录状态（含 jwt 令牌）
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<UserLoginVO> register(@RequestBody UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：{}", userRegisterDTO.getUsername());
        User user = userService.register(userRegisterDTO);
        // 注册成功后直接签发令牌，前端无需再跳一次登录
        return Result.success(buildLoginVO(user));
    }

    /**
     * 账号密码登录（网页版）
     *
     * @param dto 账号、密码、验证码
     * @return 登录信息（含 jwt 令牌）
     */
    @PostMapping("/loginByPassword")
    @Operation(summary = "用户账号密码登录")
    public Result<UserLoginVO> loginByPassword(@RequestBody UserPasswordLoginDTO dto) {
        log.info("用户账号密码登录：{}", dto.getUsername());
        User user = userService.loginByPassword(dto);
        return Result.success(buildLoginVO(user));
    }

    /**
     * 退出登录：让服务端保存的令牌失效
     *
     * @return 空结果
     */
    @PostMapping("/logout")
    @Operation(summary = "用户退出登录")
    public Result<String> logout() {
        userService.logout(BaseContext.getCurrentId());
        return Result.success();
    }

    /**
     * 查询当前登录用户信息
     *
     * @return 用户信息（不含密码）
     */
    @GetMapping("/info")
    @Operation(summary = "查询当前登录用户信息")
    public Result<UserInfoVO> info() {
        return Result.success(userService.getInfo(BaseContext.getCurrentId()));
    }

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    @PostMapping("/login")
    @Operation(summary = "微信登录")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO){
        log.info("微信用户登录：{}",userLoginDTO.getCode());

        //微信登录
        User user = userService.wxLogin(userLoginDTO);//后绪步骤实现

        //为微信用户生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID,user.getId());
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        //令牌同时写入 Redis，便于服务端注销
        saveToken(user.getId(), token);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .username(user.getUsername())
                .name(user.getName())
                .token(token)
                .build();
        return Result.success(userLoginVO);
    }

    /**
     * 签发 jwt 令牌并写入 Redis
     *
     * @param user 登录用户
     * @return 登录返回体
     */
    private UserLoginVO buildLoginVO(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        claims.put(JwtClaimsConstant.USERNAME, user.getUsername());
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        saveToken(user.getId(), token);

        return UserLoginVO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .username(user.getUsername())
                .name(user.getName())
                .token(token)
                .build();
    }

    /**
     * 把令牌写入 Redis，TTL 与 jwt 过期时间保持一致
     * <p>
     * 这样 JWT 虽然是自包含的，但服务端仍然可以“注销 / 强制下线”（删除 key 即失效）。
     *
     * @param userId 用户 id
     * @param token  jwt 令牌
     */
    private void saveToken(Long userId, String token) {
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.USER_TOKEN + userId,
                token,
                jwtProperties.getUserTtl(),
                TimeUnit.MILLISECONDS);
    }
}
