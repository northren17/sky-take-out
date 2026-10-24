package com.sky.service;

import com.sky.dto.DiningPreferenceDTO;
import com.sky.entity.DiningPreference;
import com.sky.mapper.DiningPreferenceMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.vo.AgentDishRecommendationItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiningPreferenceServiceTest {
    private DiningPreferenceMapper preferenceMapper;
    private OrderMapper orderMapper;
    private DishMapper dishMapper;
    private SetmealMapper setmealMapper;
    private DiningPreferenceService service;

    @BeforeEach
    void setUp() {
        preferenceMapper = mock(DiningPreferenceMapper.class);
        orderMapper = mock(OrderMapper.class);
        dishMapper = mock(DishMapper.class);
        setmealMapper = mock(SetmealMapper.class);
        service = new DiningPreferenceService(
                preferenceMapper, orderMapper, dishMapper, setmealMapper);
    }

    @Test
    void savesUserScopedTastesAndReturnsRemainingDailyBudget() {
        DiningPreference preference = new DiningPreference();
        preference.setUserId(12L);
        preference.setTastePreferences("微辣,少油");
        preference.setDailyBudget(new BigDecimal("50.00"));
        when(preferenceMapper.getByUserId(12L)).thenReturn(preference);
        when(orderMapper.sumPaidAmountForUserOnDate(
                eq(12L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("12.50"));

        DiningPreferenceDTO request = new DiningPreferenceDTO();
        request.setTastes(List.of("微辣", "少油"));
        request.setDailyBudget(new BigDecimal("50.00"));
        var response = service.save(12L, request);

        assertThat(response.getTastes()).containsExactly("微辣", "少油");
        assertThat(response.getSpentToday()).isEqualByComparingTo("12.50");
        assertThat(response.getRemainingBudget()).isEqualByComparingTo("37.50");
        verify(preferenceMapper).save(12L, "微辣,少油", new BigDecimal("50.00"));
    }

    @Test
    void rejectsUnsupportedTastesAndInvalidBudgets() {
        DiningPreferenceDTO request = new DiningPreferenceDTO();
        request.setTastes(List.of("过敏信息"));
        request.setDailyBudget(new BigDecimal("30.00"));

        assertThatThrownBy(() -> service.save(12L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("口味偏好");

        request.setTastes(List.of());
        request.setDailyBudget(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.save(12L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每日餐费限额");
    }

    @Test
    void recommendsAffordableMenuItemsMatchingSavedTasteAndPreservesYuanPrices() {
        DiningPreference preference = new DiningPreference();
        preference.setUserId(12L);
        preference.setTastePreferences("微辣");
        preference.setDailyBudget(new BigDecimal("90.00"));
        when(preferenceMapper.getByUserId(12L)).thenReturn(preference);
        when(orderMapper.sumPaidAmountForUserOnDate(
                eq(12L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("5.00"));
        when(dishMapper.listAvailableRecommendations()).thenReturn(List.of(
                new AgentDishRecommendationItemVO(
                        "微辣鸡肉饭", "菜品", "主食", "鸡肉饭",
                        new BigDecimal("38.00"), "辣度:[微辣]", null),
                new AgentDishRecommendationItemVO(
                        "家庭套餐", "套餐", "套餐", "双人套餐",
                        new BigDecimal("12.00"), "", null),
                new AgentDishRecommendationItemVO(
                        "超预算套餐", "套餐", "套餐", "微辣",
                        new BigDecimal("86.00"), "", null)));

        var result = service.recommend(12L);

        assertThat(result.getRemainingBudget()).isEqualByComparingTo("85.00");
        assertThat(result.getItems()).extracting("name")
                .containsExactly("微辣鸡肉饭", "家庭套餐");
        assertThat(result.getItems().getFirst().getPrice()).isEqualByComparingTo("38.00");
        assertThat(result.getItems().getFirst().getMatchedTastes()).containsExactly("微辣");
        verify(setmealMapper).listAvailableRecommendations();
    }
}
