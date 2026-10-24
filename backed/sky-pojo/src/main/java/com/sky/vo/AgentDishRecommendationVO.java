package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class AgentDishRecommendationVO {
    private List<String> tastes;
    private BigDecimal dailyBudget;
    private BigDecimal spentToday;
    private BigDecimal remainingBudget;
    private List<AgentDishRecommendationItemVO> items;
}
