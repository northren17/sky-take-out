package com.sky;

import com.sky.dto.CouponClaimDTO;
import com.sky.dto.CouponExchangeCodeBatchDTO;
import com.sky.dto.CouponRedeemDTO;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponExchangeRedemption;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponService;
import com.sky.service.impl.CouponClaimPersistenceService;
import com.sky.service.impl.CouponExchangePersistenceService;
import com.sky.service.impl.CouponExchangeServiceImpl;
import com.sky.vo.CouponClaimStatusVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponExchangeServiceImplTest {
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponExchangePersistenceService exchangePersistenceService;
    @Mock
    private CouponClaimPersistenceService claimPersistenceService;
    @Mock
    private CouponService couponService;
    @InjectMocks
    private CouponExchangeServiceImpl exchangeService;

    @Test
    void generatesUniqueUnpredictableCodesWithinConfiguredBatch() {
        CouponExchangeCodeBatchDTO request = new CouponExchangeCodeBatchDTO();
        request.setActivityId(4L);
        request.setCount(10);
        request.setMaxRedeemCount(1);
        when(couponMapper.getActivity(4L)).thenReturn(new CouponActivity());
        when(couponMapper.insertExchangeCodes(anyList())).thenReturn(10);

        List<String> codes = exchangeService.generateCodes(request);

        assertEquals(10, codes.size());
        assertEquals(10, codes.stream().distinct().count());
        codes.forEach(code -> assertEquals(32, code.length()));
    }

    @Test
    void rejectsOversizedBatchBeforeWriting() {
        CouponExchangeCodeBatchDTO request = new CouponExchangeCodeBatchDTO();
        request.setActivityId(4L);
        request.setCount(501);
        request.setMaxRedeemCount(1);

        assertThrows(CouponBusinessException.class, () -> exchangeService.generateCodes(request));
    }

    @Test
    void redemptionUsesTheAuthenticatedUserAndExistingClaimPipeline() {
        CouponExchangeRedemption redemption = new CouponExchangeRedemption();
        redemption.setBusinessNo("e7893298-4d06-47aa-97c1-4e60108c68f8");
        redemption.setActivityId(4L);
        when(exchangePersistenceService.prepareRedemption(any(), any(), any())).thenReturn(redemption);
        CouponClaimStatusVO pending = new CouponClaimStatusVO();
        pending.setBusinessNo(redemption.getBusinessNo());
        pending.setStatus(0);
        when(couponService.claim(any(), any())).thenReturn(pending);

        CouponRedeemDTO request = new CouponRedeemDTO();
        request.setCode("A".repeat(32));
        CouponClaimStatusVO result = exchangeService.redeem(9L, request);

        assertEquals(0, result.getStatus());
        verify(couponService).claim(org.mockito.ArgumentMatchers.eq(9L), any(CouponClaimDTO.class));
    }
}
