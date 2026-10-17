package com.sky.service.impl;

import com.sky.dto.CouponClaimDTO;
import com.sky.dto.CouponExchangeCodeBatchDTO;
import com.sky.dto.CouponRedeemDTO;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponExchangeCode;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponExchangeService;
import com.sky.service.CouponService;
import com.sky.vo.CouponClaimStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponExchangeServiceImpl implements CouponExchangeService {
    private static final int MAX_BATCH_SIZE = 500;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CouponMapper couponMapper;
    private final CouponExchangePersistenceService exchangePersistenceService;
    private final CouponClaimPersistenceService claimPersistenceService;
    private final CouponService couponService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> generateCodes(CouponExchangeCodeBatchDTO dto) {
        if (dto == null || dto.getActivityId() == null ||
                dto.getCount() == null || dto.getCount() < 1 || dto.getCount() > MAX_BATCH_SIZE ||
                dto.getMaxRedeemCount() == null || dto.getMaxRedeemCount() < 1 ||
                dto.getMaxRedeemCount() > 10_000) {
            throw new CouponBusinessException("兑换码批量参数无效，每批最多生成500个");
        }
        if (dto.getExpiresAt() != null && !dto.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new CouponBusinessException("兑换码过期时间必须晚于当前时间");
        }
        CouponActivity activity = couponMapper.getActivity(dto.getActivityId());
        if (activity == null) throw new CouponBusinessException("关联优惠券活动不存在");

        List<CouponExchangeCode> records = new ArrayList<>(dto.getCount());
        Set<String> uniqueCodes = new HashSet<>();
        for (int index = 0; index < dto.getCount(); index++) {
            CouponExchangeCode record = new CouponExchangeCode();
            record.setCode(generateCode(uniqueCodes));
            record.setActivityId(dto.getActivityId());
            record.setExpiresAt(dto.getExpiresAt());
            record.setMaxRedeemCount(dto.getMaxRedeemCount());
            records.add(record);
        }
        try {
            if (couponMapper.insertExchangeCodes(records) != records.size()) {
                throw new CouponBusinessException("兑换码批量写入数量不匹配");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new CouponBusinessException("兑换码生成发生唯一键冲突，请重新提交批次");
        }
        return records.stream().map(CouponExchangeCode::getCode).toList();
    }

    @Override
    public CouponClaimStatusVO redeem(Long userId, CouponRedeemDTO dto) {
        if (userId == null) throw new CouponBusinessException("用户未登录");
        if (dto == null || !StringUtils.hasText(dto.getCode())) {
            throw new CouponBusinessException("请输入兑换码");
        }
        String code = dto.getCode().trim();
        if (!code.matches("[A-Za-z0-9_-]{32}")) {
            throw new CouponBusinessException("兑换码格式无效");
        }

        CouponExchangeRedemption redemption = exchangePersistenceService.prepareRedemption(
                code, userId, UUID.randomUUID().toString());
        CouponClaimDTO claim = new CouponClaimDTO();
        claim.setActivityId(redemption.getActivityId());
        claim.setRequestId(redemption.getBusinessNo());
        try {
            CouponClaimStatusVO result = couponService.claim(userId, claim);
            if (result.getStatus() == 2) {
                claimPersistenceService.failExchangeRedemption(
                        result.getBusinessNo(), result.getFailureReason());
            }
            return result;
        } catch (CouponBusinessException failure) {
            claimPersistenceService.failExchangeRedemption(
                    redemption.getBusinessNo(), failure.getMessage());
            CouponClaimStatusVO result = new CouponClaimStatusVO();
            result.setBusinessNo(redemption.getBusinessNo());
            result.setStatus(2);
            result.setFailureReason(failure.getMessage());
            return result;
        }
    }

    private String generateCode(Set<String> generated) {
        byte[] randomBytes = new byte[24];
        String code;
        do {
            RANDOM.nextBytes(randomBytes);
            code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        } while (!generated.add(code));
        return code;
    }
}
