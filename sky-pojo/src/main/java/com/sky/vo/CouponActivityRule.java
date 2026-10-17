package com.sky.vo;

import com.sky.entity.CouponActivity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponActivityRule {
    private Long id;
    private Long templateId;
    private String name;
    private Integer perUserLimit;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;

    public CouponActivity toActivity(CouponActivity stock) {
        CouponActivity activity = new CouponActivity();
        activity.setId(id);
        activity.setTemplateId(templateId);
        activity.setName(name);
        activity.setPerUserLimit(perUserLimit);
        activity.setStartTime(startTime);
        activity.setEndTime(endTime);
        activity.setStatus(status);
        activity.setStockTotal(stock.getStockTotal());
        activity.setStockRemaining(stock.getStockRemaining());
        activity.setIssuedCount(stock.getIssuedCount());
        activity.setUsedCount(stock.getUsedCount());
        return activity;
    }
}
