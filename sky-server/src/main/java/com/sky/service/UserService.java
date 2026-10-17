package com.sky.service;

import com.sky.dto.UserLoginDTO;
import com.sky.dto.UserPasswordLoginDTO;
import com.sky.dto.UserRegisterDTO;
import com.sky.entity.User;
import com.sky.vo.UserInfoVO;

public interface UserService {

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    User wxLogin(UserLoginDTO userLoginDTO);

    /* ==================== 网页版注册 / 账号密码登录 ==================== */

    /**
     * 用户注册
     *
     * @param userRegisterDTO 注册参数（含验证码）
     * @return 注册成功的用户
     */
    User register(UserRegisterDTO userRegisterDTO);

    /**
     * 账号密码登录（校验验证码）
     *
     * @param dto 登录参数（含验证码）
     * @return 登录成功的用户
     */
    User loginByPassword(UserPasswordLoginDTO dto);

    /**
     * 退出登录：清除服务端保存的令牌
     *
     * @param userId 当前登录用户 id
     */
    void logout(Long userId);

    /**
     * 查询当前登录用户信息（不含密码）
     *
     * @param userId 当前登录用户 id
     * @return 用户信息
     */
    UserInfoVO getInfo(Long userId);
}
