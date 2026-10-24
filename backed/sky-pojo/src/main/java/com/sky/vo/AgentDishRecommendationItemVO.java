package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDishRecommendationItemVO {
    private String name;
    private String type;
    private String categoryName;
    private String description;
    private BigDecimal price;
    private String flavorText;
    private List<String> matchedTastes;
}
