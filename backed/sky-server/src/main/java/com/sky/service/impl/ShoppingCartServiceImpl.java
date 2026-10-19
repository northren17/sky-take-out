package com.sky.service.impl;


import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 添加购物车
     *
     * @param shoppingCartDTO
     */
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        Long userId = BaseContext.getCurrentId();
        String lockKey = "lock:shoppingcart:" + userId + ":" + (shoppingCartDTO.getDishId() != null ? "dish:" + shoppingCartDTO.getDishId() : "setmeal:" + shoppingCartDTO.getSetmealId());
        
        // 简单分布式锁，防止并发添加导致重复数据
        Boolean lock = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
        if (lock == null || !lock) {
            log.warn("用户{}添加购物车操作过于频繁", userId);
            return;
        }

        try {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
            //只能查询自己的购物车数据
            shoppingCart.setUserId(userId);

            //判断当前商品是否在购物车中
            List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

            if (shoppingCartList != null && shoppingCartList.size() == 1) {
                //如果已经存在，就更新数量，数量加1
                shoppingCart = shoppingCartList.get(0);
                shoppingCart.setNumber(shoppingCart.getNumber() + 1);
                shoppingCartMapper.updateNumberById(shoppingCart);
            } else {
                //如果不存在，插入数据，数量就是1

                //判断当前添加到购物车的是菜品还是套餐
                Long dishId = shoppingCartDTO.getDishId();
                if (dishId != null) {
                    //添加到购物车的是菜品
                    Dish dish = dishMapper.getByid(dishId);
                    if (dish == null) {
                        throw new ShoppingCartBusinessException(MessageConstant.DISH_NOT_FOUND);
                    }
                    shoppingCart.setName(dish.getName());
                    shoppingCart.setImage(dish.getImage());
                    shoppingCart.setAmount(dish.getPrice());
                } else {
                    //添加到购物车的是套餐
                    Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                    if (setmeal == null) {
                        throw new ShoppingCartBusinessException(MessageConstant.SETMEAL_NOT_FOUND);
                    }
                    shoppingCart.setName(setmeal.getName());
                    shoppingCart.setImage(setmeal.getImage());
                    shoppingCart.setAmount(setmeal.getPrice());
                }
                shoppingCart.setNumber(1);
                shoppingCart.setCreateTime(LocalDateTime.now());
                shoppingCartMapper.insert(shoppingCart);
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(
                ShoppingCart.
                builder().
                userId(BaseContext.getCurrentId()).
                build()
        );
    }
    /**
     * 清空购物车商品
     */
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
    }

    /**
     * 减少购物车中商品数量
     * @param shoppingCartDTO
     */
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        shoppingCart.setUserId(userId);

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        if (shoppingCartList != null && shoppingCartList.size() == 1) {
            shoppingCart = shoppingCartList.get(0);
            if (shoppingCart.getNumber() == 1) {
                shoppingCartMapper.deleteById(shoppingCart.getId());
            } else {
                shoppingCart.setNumber(shoppingCart.getNumber() - 1);
                shoppingCartMapper.updateNumberById(shoppingCart);
            }
        }
    }
}