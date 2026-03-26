package com.sky.service;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.SetmealVO;

import java.util.List;

public interface SetmealService {
    /*
    增加套餐
     */
    void saveWithDish(SetmealDTO setmealDTO);
/*
套餐分页
 */
    PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);
/*
删除套餐
 */
    void deleteBatch(List<Long> ids);

       /*  根据id查询套餐和关联的菜品数据
      @param id
      @return*/
    SetmealVO getByIdWithDish(Long id);
    /**
     * 修改套餐
     * @param setmealDTO
     */
    void update(SetmealDTO setmealDTO);
/*
套餐启用禁用
 */
    void startOrStop(Integer status, Long id);
}
