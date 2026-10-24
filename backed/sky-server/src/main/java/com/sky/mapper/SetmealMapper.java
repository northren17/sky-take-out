package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishItemVO;
import com.sky.vo.AgentDishRecommendationItemVO;
import com.sky.vo.SetmealVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐的数量
     * @param id
     * @return
     */
    @Select("select count(id) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(Long id);
    /*
    新增套餐
     */
    @AutoFill(OperationType.INSERT)
    void insert(Setmeal setmeal);

    /*
    套餐分页
     */
    Page<SetmealVO> pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);
    /*
    获取套餐信息
     */
    @Select("select * from setmeal where id = #{id}")
    Setmeal getById(Long id);

    @Update("update setmeal set stock = case when stock = -1 then -1 else stock - #{quantity} end " +
            "where id = #{id} and status = 1 and (stock = -1 or stock >= #{quantity})")
    int deductStock(@org.apache.ibatis.annotations.Param("id") Long id,
                    @org.apache.ibatis.annotations.Param("quantity") int quantity);

    @Update("update setmeal set stock = case when stock < 0 then stock else stock + #{quantity} end where id = #{id}")
    int restoreStock(@org.apache.ibatis.annotations.Param("id") Long id,
                     @org.apache.ibatis.annotations.Param("quantity") int quantity);

    /**
     * 根据id删除套餐
     * @param setmealId
     */
    @Delete("delete from setmeal where id = #{id}")
    void deleteById(Long setmealId);
    /*
    修过菜单
     */
    @AutoFill(OperationType.UPDATE)
    void update(Setmeal setmeal);
    /**
     * 动态条件查询套餐
     * @param setmeal
     * @return
     */
    List<Setmeal> list(Setmeal setmeal);

    @Select("select s.name, '套餐' as type, c.name as categoryName, " +
            "coalesce(s.description, '') as description, s.price, " +
            "coalesce(f.flavor_text, '') as flavorText " +
            "from setmeal s join category c on c.id = s.category_id " +
            "left join (select sd.setmeal_id, " +
            "group_concat(concat(df.name, ':', df.value) separator ' ') as flavor_text " +
            "from setmeal_dish sd join dish_flavor df on df.dish_id = sd.dish_id " +
            "group by sd.setmeal_id) f on f.setmeal_id = s.id " +
            "where s.status = 1 and c.status = 1 and c.type = 2 " +
            "and (s.stock = -1 or s.stock > 0) " +
            "order by c.sort, s.update_time desc limit 100")
    List<AgentDishRecommendationItemVO> listAvailableRecommendations();

    /**
     * 根据套餐id查询菜品选项
     * @param setmealId
     * @return
     */
    @Select("select sd.dish_id as dishId, sd.name, sd.copies, d.image, d.description " +
            "from setmeal_dish sd left join dish d on sd.dish_id = d.id " +
            "where sd.setmeal_id = #{setmealId}")
    List<DishItemVO> getDishItemBySetmealId(Long setmealId);
    /**
     * 根据条件统计套餐数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);
}
