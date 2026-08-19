package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.api.AirStationSdk;
import com.ecat.integration.EnvAirStationManagerIntegration.api.SdkParamMeta;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * stat-params 端点与 SDK 同源：直接透传 {@link AirStationSdk#listStatParams()} 同一返回
 * （同一构建函数，不另造投影）。
 */
@ExtendWith(MockitoExtension.class)
class AsmStatParamsControllerTest {

    @Mock
    private AirStationSdk sdk;

    private AsmStatParamsController controller;

    @BeforeEach
    void setUp() {
        controller = new AsmStatParamsController(sdk);
    }

    @Test
    void list_delegatesToSdkListStatParamsAndReturnsSameInstance() {
        List<SdkParamMeta> metas = Collections.singletonList(SdkParamMeta.builder().build());
        when(sdk.listStatParams()).thenReturn(metas);

        AjaxResult result = controller.list();

        verify(sdk).listStatParams();
        assertSame(metas, result.get(AjaxResult.DATA_TAG), "端点须复用 SDK listStatParams 同一构建结果，不另造投影");
    }
}
