package com.ecat.integration.EnvAirStationManagerIntegration.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsmGranularityMask 单测——位定义 / isApplicable 过滤 / validate 严格拒绝脏掩码。
 *
 * @author coffee
 */
class AsmGranularityMaskTest {

    @Test
    void maskBits_bitLayout() {
        assertEquals(1, AsmGranularityMask.MINUTE);
        assertEquals(2, AsmGranularityMask.FIVE_MIN);
        assertEquals(4, AsmGranularityMask.HOUR);
        assertEquals(7, AsmGranularityMask.ALL);
    }

    @Test
    void isApplicable_filtersByGranularityBit() {
        // seed 默认全开：三粒度全适用
        for (AsmStatGranularity gran : AsmStatGranularity.values()) {
            assertTrue(AsmGranularityMask.isApplicable(AsmGranularityMask.ALL, gran), gran.name());
        }
        // mask=1（仅分钟）：5min/hour 被过滤
        assertTrue(AsmGranularityMask.isApplicable(1, AsmStatGranularity.MINUTE));
        assertFalse(AsmGranularityMask.isApplicable(1, AsmStatGranularity.FIVE_MIN));
        assertFalse(AsmGranularityMask.isApplicable(1, AsmStatGranularity.HOUR));
        // mask=6（5min+hour）：minute 被过滤（引擎跳过 minute，级联上游无桶则下游自然空）
        assertFalse(AsmGranularityMask.isApplicable(6, AsmStatGranularity.MINUTE));
        assertTrue(AsmGranularityMask.isApplicable(6, AsmStatGranularity.HOUR));
    }

    @Test
    void validate_rejectsDirtyMask() {
        assertEquals(7, AsmGranularityMask.validate(7));
        assertEquals(1, AsmGranularityMask.validate(1));
        assertThrows(IllegalArgumentException.class, () -> AsmGranularityMask.validate(0));
        assertThrows(IllegalArgumentException.class, () -> AsmGranularityMask.validate(-1));
        assertThrows(IllegalArgumentException.class, () -> AsmGranularityMask.validate(8));  // 未知位
        assertThrows(IllegalArgumentException.class, () -> AsmGranularityMask.validate(15)); // bit3=day 不存在
    }

    @Test
    void granularity_maskBitsAlignWithEnum() {
        // 枚举携带位与常量位一一对应（单一真相源互检）
        assertEquals(AsmGranularityMask.MINUTE, AsmStatGranularity.MINUTE.mask());
        assertEquals(AsmGranularityMask.FIVE_MIN, AsmStatGranularity.FIVE_MIN.mask());
        assertEquals(AsmGranularityMask.HOUR, AsmStatGranularity.HOUR.mask());
    }
}
