package com.ecat.integration.EnvAirStationManagerIntegration.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.EcatCore;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import lombok.Value;

/**
 * ASM 模块级调度车道解析（本地双轨已统一）
 * ——模块周期任务（三粒度物化 / 报警 sweep）：<b>计时</b>挂集成业务计时器（biz 池，
 * IntegrationBase.getBizScheduler 同池，纯计算节律与 IO 故障域隔离），到点 tick 只做
 * O(1) 投递；<b>工作体</b>进 {@code HostedExecutors.bounded(1, 集成)} 模块单飞道
 * （三粒度 tick 与 sweep 同道 FIFO 互斥，物化 tick 间不并发——互斥语义与原引擎车道一致）。
 *
 * <p>道由集成 onStart 经 {@link #wireWorkLane(ExecutorService)} 接线（签名强制：道的创建
 * 必须过宿主 {@code HostedExecutors.bounded(1, this)}，拆卸挂集成 onRemove sweep）——本类
 * 服务的 @Service bean 集成实例面不可达。道零治理（无看门狗/熔断）：body 挂死归域侧事务
 * 超时；拒绝＝REE 逐投递记账（过期即弃，下周期自愈——「永不注销」由计时体只做投递、
 * 投递拒绝不异常逃逸保证）。任务取消由调度器自持 {@code ScheduledFuture} 表达。</p>
 *
 * <p>解析顺序（{@link #resolve()}）：
 * <ol>
 *   <li>{@link #bind(ScheduledExecutorService) 显式绑定}计时器——无 core 上下文的单测注入
 *       biz 池替身的边界（工作道经 {@link #wireWorkLane} 注入）；生产代码不得依赖此层。</li>
 *   <li>运行中的 ECAT 平台（{@code EcatCore.getInstance()} 非空）→ biz 池计时 + 接线道
 *       （生产路径）。core 缺席且未绑定=装配缺陷，显式抛 ISE（原本地兜底双轨
 *       已删除，不再猜测自建计时器）。</li>
 * </ol></p>
 *
 * @author coffee
 */
public final class AsmLanes {

    private static final Log log = LogFactory.getLogger(AsmLanes.class);

    /** 测试显式注入的计时器（bind/unbind）；null = 未注入，走默认解析（core biz 池）。 */
    private static volatile ScheduledExecutorService boundTimer;

    /** 模块单飞道（bounded(1, 集成) 产出），集成 onStart 接线；null=未接线（消费即装配缺陷）。 */
    private static volatile ExecutorService workLane;

    private AsmLanes() {
    }

    /**
     * 显式注入计时器（测试边界）：注入后 {@link #resolve()} 恒返回绑定计时器 + 当前接线
     * 的道，便于无 core 上下文的单测确定性断言周期注册与投递路径。
     *
     * @param timer 测试自有的计时器实例（生命周期由测试管理，本类不关停）
     */
    public static void bind(ScheduledExecutorService timer) {
        if (timer == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        boundTimer = timer;
    }

    /** 解除显式绑定，恢复默认解析（core biz 池）。 */
    public static void unbind() {
        boundTimer = null;
    }

    /**
     * 集成接线（onStart 顶部、装配 @Service bean 之前）：道经
     * {@code HostedExecutors.bounded(1, this)} 创建（拆卸挂集成 onRemove）。
     * 幂等 keep-first：disable→re-enable 同实例重跑 onStart 时复用既有道
     * （onPause 不 sweep，道存活仍有效）；既有道已停机（release 后同类加载器重建的
     * 静态残留）则用新道顶替——保留死道会让此后全部提交恒 REE 静默丢失。
     */
    public static void wireWorkLane(ExecutorService lane) {
        if (lane == null) {
            throw new IllegalArgumentException("wireWorkLane(null) 不允许——接线必须传入真实执行道");
        }
        if (workLane == null || workLane.isShutdown()) {
            workLane = lane;
        }
    }

    /** 测试解线（还原未接线态；生产代码不得调用）。 */
    public static void unwireWorkLane() {
        workLane = null;
    }

    /** 道解析：未接线=装配缺陷（集成 onStart 须先 wire），显式抛。 */
    private static ExecutorService workLane() {
        ExecutorService current = workLane;
        if (current == null) {
            throw new IllegalStateException(
                    "ASM 工作道未接线——集成 onStart 须先 AsmLanes.wireWorkLane(HostedExecutors.bounded(1, 集成))");
        }
        return current;
    }

    /**
     * 解析 ASM 模块调度车道（解析顺序见类 Javadoc；无 core 且未 bind 计时器显式抛）。
     */
    public static Lane resolve() {
        ScheduledExecutorService timer = boundTimer;
        if (timer == null) {
            EcatCore core = EcatCore.getInstance();
            if (core == null) {
                throw new IllegalStateException(
                        "ASM 车道解析需要 ECAT core 上下文（biz 计时池）——单测请显式 bind 计时器 + wireWorkLane 接线道");
            }
            timer = core.getTaskManager().getBizScheduler();
        }
        return new Lane(timer, workLane());
    }

    /**
     * 适配既有注入 executor（调度器测试构造）为非拥有车道：计时与工作道均测试自管
     * （本类不关停）；周期到点工作体投递到注入的工作道执行。
     */
    public static Lane laneOf(ScheduledExecutorService timer, ExecutorService workLane) {
        return new Lane(timer, workLane);
    }

    /**
     * 调度车道视图：只暴露周期调度所需能力（{@link #atFixedRate}），不为适配委派整张
     * ScheduledExecutorService 契约。计时/工作道均为共享设施或宿主生命周期所有——
     * 无自有资源，无收尾方法（原本地兜底双轨已删除）。
     */
    @Value
    public static class Lane {

        /** 计时设施（biz 池 / 测试注入），非空。 */
        ScheduledExecutorService timer;

        /** 工作体单飞道（bounded(1, 集成) 或测试注入），非空。 */
        ExecutorService workLane;

        /**
         * 周期任务登记：到点 tick 只做 O(1) 投递（工作体进模块单飞道，计时线程不做 DB IO）。
         */
        public ScheduledFuture<?> atFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            return timer.scheduleAtFixedRate(() -> submitWork(task), initialDelay, period, unit);
        }

        /**
         * 到点投递：工作体进模块单飞道；道拒绝（队列满/宿主已拆卸 REE）记账即弃不异常
         * 逃逸——周期计时不受影响（永不注销），下周期 tick 重投自愈（过期即弃）。
         */
        private void submitWork(Runnable task) {
            try {
                workLane.execute(task);
            } catch (RejectedExecutionException e) {
                log.warn("ASM 模块工作道投递拒绝（丢弃记账，下周期自愈）: {}", e.getMessage());
            }
        }
    }
}
