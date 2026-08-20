package com.ecat.integration.EnvAirStationManagerIntegration.support;

import com.ecat.core.Bus.event.EventContext;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AsmOnlineJudge 单测——设备级在线/离线判定（总览页瓦片在线点 + 离线时长红字的数据源）。
 *
 * <p>判定契约（设计定案 5）：
 * <ol>
 *   <li>优先 online_status attr（LOnlineStatusAttribute 60s 语义）的 state.lastUpdated；</li>
 *   <li>无该 attr（或其尚无 state）→ 全部 attr 最新 lastUpdated 兜底（任一参数 60s 内更新=在线）；</li>
 *   <li>online 窗口 60s：age &lt;= 60_000ms 为在线（60s 恰好=在线，与 LOnlineStatusAttribute
 *       「一分钟内更新过=online」同语义边界）；</li>
 *   <li>全部 attr 无 state（设备从未喂数）→ 离线 + offlineMs=null（时长未知）。</li>
 * </ol>
 */
class AsmOnlineJudgeTest {

    private static final Instant NOW = Instant.parse("2026-08-19T04:00:00Z");

    @Test
    void onlineStatusAttrPreferred_ageWithin60s_online() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("online_status", attrWithLastUpdated(NOW.minusMillis(10_000)));
        attrs.put("temperature", attrWithLastUpdated(NOW.minusMillis(300_000)));  // 老 5min，不应参与

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertTrue(j.isOnline(), "online_status 10s 前更新应在线");
        assertEquals(Long.valueOf(10_000L), j.getOfflineMs());
    }

    @Test
    void boundary_exactly60s_online() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("online_status", attrWithLastUpdated(NOW.minusMillis(60_000)));

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertTrue(j.isOnline(), "恰好 60s（LOnlineStatusAttribute「一分钟内」同边界）应在线");
        assertEquals(Long.valueOf(60_000L), j.getOfflineMs());
    }

    @Test
    void boundary_justOver60s_offline() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("online_status", attrWithLastUpdated(NOW.minusMillis(60_001)));

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertFalse(j.isOnline(), "60s+1ms 应离线");
    }

    @Test
    void noOnlineStatusAttr_anyFreshAttr_online() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("temperature", attrWithLastUpdated(NOW.minusMillis(30_000)));   // 最新（新鲜）
        attrs.put("humidity", attrWithLastUpdated(NOW.minusMillis(1_860_000)));   // 31min 前陈旧

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertTrue(j.isOnline(), "无 online_status 时任一 attr 60s 内更新（30s 前）应在线（按最新兜底）");
        assertEquals(Long.valueOf(30_000L), j.getOfflineMs());
    }

    @Test
    void noOnlineStatusAttr_allStale_offline_distanceToNewest() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        attrs.put("temperature", attrWithLastUpdated(NOW.minusMillis(1_860_000)));  // 31min 前（最新）
        attrs.put("humidity", attrWithLastUpdated(NOW.minusMillis(3_600_000)));     // 60min 前（最老）

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertFalse(j.isOnline(), "全部 attr 陈旧应离线");
        assertEquals(Long.valueOf(1_860_000L), j.getOfflineMs(), "offlineMs=距最新更新（非最老）");
    }

    @Test
    void onlineStatusAttrWithoutState_fallsBackToNewest() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        AttributeBase<?> noState = mock(AttributeBase.class);
        when(noState.getState()).thenReturn(null);
        attrs.put("online_status", noState);
        attrs.put("speed", attrWithLastUpdated(NOW.minusMillis(5_000)));
        attrs.put("old", attrWithLastUpdated(NOW.minusMillis(600_000)));

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertTrue(j.isOnline(), "online_status 无 state（未喂数）→ 兜底最新 attr=5s 前应在线");
        assertEquals(Long.valueOf(5_000L), j.getOfflineMs());
    }

    @Test
    void noStatesAtAll_offline_unknownDuration() {
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        AttributeBase<?> noState = mock(AttributeBase.class);
        when(noState.getState()).thenReturn(null);
        attrs.put("temperature", noState);

        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(attrs, NOW);

        assertNotNull(j, "无任何 state 也有判定（离线）");
        assertFalse(j.isOnline());
        assertNull(j.getOfflineMs(), "从未喂数 → 离线时长未知 null");
    }

    @Test
    void emptyAttrs_offline_unknownDuration() {
        AsmOnlineJudge.Judgement j = AsmOnlineJudge.judge(new HashMap<String, AttributeBase<?>>(), NOW);
        assertFalse(j.isOnline());
        assertNull(j.getOfflineMs());
    }

    private static AttributeBase<?> attrWithLastUpdated(Instant t) {
        @SuppressWarnings("unchecked")
        AttributeBase<Object> attr = mock(AttributeBase.class);
        AttrState<Object> state = AttrState.<Object>builder()
                .deviceId("d").attrId("a").value((Object) 1.0)
                .valueType(Object.class)
                .status(AttributeStatus.NORMAL)
                .context(EventContext.root(EventContext.Source.DEVICE_POLL, null))
                .lastUpdated(t).build();
        when(attr.getState()).thenReturn(state);
        return attr;
    }
}
