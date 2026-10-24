package com.sky.service;

import com.sky.dto.DiningPreferenceDTO;
import com.sky.entity.DiningPreference;
import com.sky.mapper.DiningPreferenceMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.vo.AgentDishRecommendationItemVO;
import com.sky.vo.AgentDishRecommendationVO;
import com.sky.vo.DiningPreferenceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DiningPreferenceService {
    private static final Set<String> ALLOWED_TASTES = Set.of(
            "清淡", "微辣", "中辣", "重辣", "甜口", "少油", "素食");
    private static final BigDecimal MAX_DAILY_BUDGET = new BigDecimal("10000.00");
    private static final Map<String, List<String>> TASTE_KEYWORDS = Map.of(
            "清淡", List.of("清淡", "蒸", "煮", "白灼"),
            "微辣", List.of("微辣", "小辣"),
            "中辣", List.of("中辣", "香辣"),
            "重辣", List.of("重辣", "特辣", "麻辣"),
            "甜口", List.of("甜", "糖醋", "蜜汁"),
            "少油", List.of("少油", "清蒸", "白灼"),
            "素食", List.of("素", "蔬菜", "菌菇", "豆腐"));

    private final DiningPreferenceMapper preferenceMapper;
    private final OrderMapper orderMapper;
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;

    public DiningPreferenceVO get(Long userId) {
        requireUser(userId);
        DiningPreference preference = preferenceMapper.getByUserId(userId);
        List<String> tastes = preference == null || preference.getTastePreferences() == null
                || preference.getTastePreferences().isBlank()
                ? List.of()
                : Arrays.asList(preference.getTastePreferences().split(","));
        BigDecimal dailyBudget = preference == null ? null : preference.getDailyBudget();
        LocalDate today = LocalDate.now();
        BigDecimal spentToday = orderMapper.sumPaidAmountForUserOnDate(
                userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (spentToday == null) spentToday = BigDecimal.ZERO;
        spentToday = spentToday.setScale(2, RoundingMode.HALF_UP);
        BigDecimal remainingBudget = dailyBudget == null
                ? null
                : dailyBudget.subtract(spentToday).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new DiningPreferenceVO(tastes, dailyBudget, spentToday, remainingBudget);
    }

    @Transactional
    public DiningPreferenceVO save(Long userId, DiningPreferenceDTO request) {
        requireUser(userId);
        if (request == null || request.getTastes() == null || request.getTastes().size() > ALLOWED_TASTES.size()
                || request.getTastes().stream().anyMatch(taste ->
                taste == null || !ALLOWED_TASTES.contains(taste))
                || request.getTastes().stream().distinct().count() != request.getTastes().size()) {
            throw new IllegalArgumentException("口味偏好选择无效");
        }
        BigDecimal budget = request.getDailyBudget();
        if (budget == null || budget.compareTo(BigDecimal.ZERO) <= 0
                || budget.compareTo(MAX_DAILY_BUDGET) > 0
                || budget.scale() > 2) {
            throw new IllegalArgumentException("每日餐费限额须大于0且不超过10000元，最多保留两位小数");
        }
        preferenceMapper.save(userId, String.join(",", request.getTastes()), budget);
        return get(userId);
    }

    public AgentDishRecommendationVO recommend(Long userId) {
        DiningPreferenceVO preference = get(userId);
        BigDecimal remaining = preference.getRemainingBudget();
        List<AgentDishRecommendationItemVO> items = new ArrayList<>();
        items.addAll(dishMapper.listAvailableRecommendations());
        items.addAll(setmealMapper.listAvailableRecommendations());

        List<AgentDishRecommendationItemVO> affordableItems = items.stream()
                .filter(item -> remaining == null
                        || item.getPrice().compareTo(remaining) <= 0)
                .map(item -> {
                    item.setMatchedTastes(matchedTastes(item, preference.getTastes()));
                    return item;
                })
                .sorted(Comparator
                        .comparingInt((AgentDishRecommendationItemVO item) ->
                                item.getMatchedTastes().size()).reversed()
                        .thenComparing(AgentDishRecommendationItemVO::getPrice))
                .limit(12)
                .toList();

        return new AgentDishRecommendationVO(
                preference.getTastes(),
                preference.getDailyBudget(),
                preference.getSpentToday(),
                preference.getRemainingBudget(),
                affordableItems);
    }

    private static List<String> matchedTastes(
            AgentDishRecommendationItemVO item, List<String> tastes) {
        String searchableText = String.join(" ",
                item.getName() == null ? "" : item.getName(),
                item.getDescription() == null ? "" : item.getDescription(),
                item.getCategoryName() == null ? "" : item.getCategoryName(),
                item.getFlavorText() == null ? "" : item.getFlavorText());
        return tastes.stream()
                .filter(taste -> TASTE_KEYWORDS.getOrDefault(taste, List.of(taste))
                        .stream().anyMatch(searchableText::contains))
                .toList();
    }

    private static void requireUser(Long userId) {
        if (userId == null) throw new IllegalArgumentException("用户身份无效");
    }
}
