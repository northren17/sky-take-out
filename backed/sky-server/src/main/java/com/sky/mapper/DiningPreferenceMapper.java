package com.sky.mapper;

import com.sky.entity.DiningPreference;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface DiningPreferenceMapper {
    @Select("select user_id as userId, taste_preferences as tastePreferences, " +
            "daily_budget as dailyBudget " +
            "from user_dining_preference where user_id = #{userId}")
    DiningPreference getByUserId(@Param("userId") Long userId);

    @Insert("insert into user_dining_preference (user_id, taste_preferences, daily_budget) " +
            "values (#{userId}, #{tastePreferences}, #{dailyBudget}) " +
            "on duplicate key update taste_preferences = values(taste_preferences), " +
            "daily_budget = values(daily_budget), update_time = current_timestamp")
    int save(@Param("userId") Long userId,
             @Param("tastePreferences") String tastePreferences,
             @Param("dailyBudget") BigDecimal dailyBudget);
}
