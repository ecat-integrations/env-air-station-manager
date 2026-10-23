package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import java.util.concurrent.CompletableFuture;

/**
 * ASM 控制提交句柄——{@code AsmControlService.executeAsync} 的不可变出参。
 *
 * <p>生命周期：{@code record} 在同步提交完成后即可读（id 已回填、result=PENDING）；
 * {@code completion} 在控制终态落库与 SSE 广播之后完成，且终态完成动作只发生在
 * 调用方传入的 callback executor 线程。</p>
 *
 * @author coffee
 */
public final class AsmControlSubmission {

    private final AsmControlRecord record;
    private final CompletableFuture<AsmControlRecord> completion;

    public AsmControlSubmission(AsmControlRecord record,
                                CompletableFuture<AsmControlRecord> completion) {
        this.record = record;
        this.completion = completion;
    }

    public AsmControlRecord getRecord() {
        return record;
    }

    public CompletableFuture<AsmControlRecord> getCompletion() {
        return completion;
    }
}
