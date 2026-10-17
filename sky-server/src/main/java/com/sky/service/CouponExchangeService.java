package com.sky.service;

import com.sky.dto.CouponExchangeCodeBatchDTO;
import com.sky.dto.CouponRedeemDTO;
import com.sky.vo.CouponClaimStatusVO;

import java.util.List;

public interface CouponExchangeService {
    List<String> generateCodes(CouponExchangeCodeBatchDTO dto);
    CouponClaimStatusVO redeem(Long userId, CouponRedeemDTO dto);
}
