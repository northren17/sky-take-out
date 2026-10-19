package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCouponPageVO {
    private long total;
    private int page;
    private int pageSize;
    private List<AgentCouponSummaryVO> coupons;
}
