package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

@Mapper
public interface UserMapper {

    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(Long openid);



    /**
     * 插入数据
     * @param user
     */
    void insert(User user);

    @Select("select * from user where openid = #{openid}")
    User getById(String openid);
    /**
     * 根据动态条件统计用户数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    /* ==================== C端用户注册 / 登录 ==================== */

    /**
     * 根据用户名（登录账号）查询用户
     * <p>
     * 网页版注册时把账号写入 username 列，这里按 username 查询。
     * 注意：user 是 SQL 保留字，必须用反引号包裹（MySQL 与 H2 均支持）。
     *
     * @param username 登录账号
     * @return 用户；不存在返回 null
     */
    @Select("select * from `user` where username = #{username}")
    User getByUsername(@Param("username") String username);

    /**
     * 根据主键查询用户
     *
     * @param id 用户 id
     * @return 用户；不存在返回 null
     */
    @Select("select * from `user` where id = #{id}")
    User getByUserId(@Param("id") Long id);

    /**
     * 更新密码（BCrypt 密文）
     *
     * @param id       用户 id
     * @param password 加密后的密码
     * @return 影响行数
     */
    @Update("update `user` set password = #{password} where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);
}
