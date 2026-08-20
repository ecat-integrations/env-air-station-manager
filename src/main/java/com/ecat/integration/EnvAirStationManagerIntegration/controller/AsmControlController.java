package com.ecat.integration.EnvAirStationManagerIntegration.controller;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmControlRecordMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.service.AsmControlService;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REMOTE 控制入口（{@code POST /asm-monitor/control}）——caller 取认证 principal，
 * 汇入 {@link AsmControlService}（与 SDK LOCAL 路同收口，全程落 asm_control_record 审计）。
 * 响应含审计记录 id + 当前 result（异步执行则 PENDING，终态按 id 回查）。
 *
 * @author coffee
 */
@RestController
@RequestMapping("/asm-monitor/control")
@RequiredArgsConstructor
public class AsmControlController extends BaseController {

    private final AsmControlService controlService;
    private final AsmControlRecordMapper recordMapper;

    /** 控制请求体（uid/attrId/value）。 */
    public static class AsmControlRequest {
        private String uid;
        private String attrId;
        private String value;

        public AsmControlRequest() {
        }

        public AsmControlRequest(String uid, String attrId, String value) {
            this.uid = uid;
            this.attrId = attrId;
            this.value = value;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getAttrId() {
            return attrId;
        }

        public void setAttrId(String attrId) {
            this.attrId = attrId;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** REMOTE 控制（caller=认证 principal；origin=REMOTE）。 */
    @PreAuthorize("@ss.hasPermi('asm-monitor:control:execute')")
    @PostMapping
    public AjaxResult execute(@RequestBody AsmControlRequest request) {
        return executeForCaller(request, SecurityUtils.getUsername());
    }

    /** principal 注入点拆开（单测免静态 mock 直接验收口契约）。 */
    AjaxResult executeForCaller(AsmControlRequest request, String caller) {
        if (request == null) {
            throw new IllegalArgumentException("控制请求体为空");
        }
        AsmControlRecord record = controlService.execute(AsmControlOrigin.REMOTE, caller,
                request.getUid(), request.getAttrId(), request.getValue());
        return AjaxResult.success(record);
    }

    /**
     * 按主键单查控制记录——<b>仅设计用途=SSE 重连补偿单查</b>（前端重连成功后对在途 PENDING 项
     * 一次性按 id 对齐终态，生命周期事件触发，非轮询通道）；不存在明确抛 IAE（400，模块惯例）。
     */
    @PreAuthorize("@ss.hasPermi('asm-monitor:control:execute')")
    @GetMapping("/{id}")
    public AjaxResult getById(@PathVariable("id") long id) {
        AsmControlRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("控制记录不存在: id=" + id);
        }
        return AjaxResult.success(record);
    }
}
