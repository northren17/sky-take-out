package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.vo.DishVO;

import java.util.List;

public interface DishService {
    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    public void saveWithFlavor(DishDTO dishDTO);

    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

  public Result deleteBatch(List<Long> ids);

    /**
     * 查询用户
     * @param id
     * @return
     */
    DishVO getByIdWithFlavor(Long id);
/*
修改菜品
 */
    void updateWithFlavor(DishDTO dishDTO);
/*
套餐查询
 */
    List<Dish> list(Long categoryId);
/*
启用菜品
 */
    void startOrStop(Integer status, Long id);
    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    List<DishVO> listWithFlavor(Dish dish);

    /**
     * 查询「有在售菜品」的菜品分类（C端首页左侧分类栏）
     *
     * @return 分类列表
     */
    List<Category> listAvailableCategory();
}


