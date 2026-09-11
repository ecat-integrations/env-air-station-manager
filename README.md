# 空气站房管理集成(env-air-station-manager)

管理 `logicdevice-airstation` 的 22 类站房逻辑设备（uniqueId 前缀 `logicdevice_station.*`）：消费 `device.data.update` 总线事件，提供**实时快照、三级均值聚合、历史查询、动环报警、控制审计、对外 SDK** 六项能力。设备生命周期归 airstation 集成，本集成不建/不删逻辑设备；与 env-air-device-manager 为兄弟集成，互不依赖。

## 功能总览

| 能力 | 说明 |
|---|---|
| 数据管道 | 总线消费站房设备事件 → `asm_data_sample`（raw，TimescaleDB hypertable）；新 series 首见自动 seed 聚合/单位配置 |
| 三级聚合 | minute/5min/hour 级联：数值=加权均值（非均值再均值）；非数值 bind attr（白名单 16 项，`AsmStatSeriesKindClassifier` 真相源）=ALARM（minute 窗口任一报警→alarm / 5min 点采样 / hour 全窗任一报警）与 STATE（minute 距桶标最近样本 / 5min·hour 点采样）两口径，落 `value_text` 与 avg_value 互斥；FRONT=[S,E)/BACK=(L,R] 双标物化（HJ663 口径），按 series 可配。规则真相源 `docs/stat-series-rules.md` |
| 历史查询 | REST 按粒度/mode/参数/时间窗查询 + 单位偏好出口（换算，缺行显原生） |
| 动环报警 | seed 规则（温湿度/供电/漏水/门禁/标气泄漏等 16 类，报警标识语义化 snake_case 如 `water_leak`/`room_temp_abnormal`），range+持续 / 瞬时阈值 / 状态串三类判定，热加载、报警联动（如泄漏→开排风扇） |
| 控制审计 | 统一控制收口（origin 由各入口声明：本站 web/报警联动=LOCAL，第三方代传=REMOTE），`asm_control_record` 记录来源、调用方、执行前后值、终态（PENDING→SUCCESS/FAILED/TIMEOUT） |
| 设备控制页 | 总览抽屉入口 → `device_control`：DM 配置驱动的 7 台可控设备 card 墙，批量确认/撤销 + 串行逐 attr 提交，终态 SSE 流式回显（无轮询） |
| 对外 SDK | `AirStationSdk`（api 包零依赖），其他集成进程内取用查询/报警/控制能力 |

## 总览页（monitor）

- **分组瓦片墙**：7 组 22 类设备，组内按设备名拼音序；瓦片头圆点三色 = 🔴 报警 > ⚫ 离线 > 🟢 在线；筛选 chips（全部/离线/报警 计数实时）。
- **SSE 实时推送**：`/asm-monitor/stream` 具名帧 `device.data.update` 增量 patch 瓦片/抽屉（fetch-event-source，`?token=` 鉴权，5s 心跳），无轮询。
- **单位双模式**：右上「默认/自定义」切换（显示文字 2026-08-20 起「标准」→「默认」，localStorage 存值 `standard`/`custom` 不动、老用户已存选择直接恢复）——standard 读 `asm_config_unit` STANDARD 行（seed 默认=原生单位），custom 读 MONITOR 偏好行；SSE 帧双值同推，切换零延迟。
- **单位设置抽屉**（⚙ 单位设置，2026-08-20）：编辑选中设备 MONITOR（自定义）行——设备下拉按 catalog 分组全中文名、参数行（中文参数名|当前值|单位下拉|小数位 0-6）仅数值类可编辑；单位候选 = 同类全部单位 + 气态跨类（mg/m³↔ppm，snapshot 行 `unitOptions` 按类分组、同类组在前，跨类不可换算目标按现有语义显原生）；小数位只作用于监控页（瓦片/抽屉/SSE），修约三级链 = MONITOR 行 `display_precision` → def displayPrecision → 默认 2，历史页不动；保存逐行 PUT config-unit（displayPrecision 空=不覆盖已有值），后端缓存已失效即时生效。「默认」模式读 STANDARD 行不受影响。
- **卡片报警计算**（同 ADM）：设备级报警 = 全 attr 状态 danger 档并集 ∪ 规则 episode；无设备级布尔，SSE 逐 attr patch 天然实时。attr 行带 `status` 枚举 key（非中文文案），前端按枚举映射配色。
- **详情抽屉**：顶部设备状态条（报警徽章并集+规则名+离线信息）；参数行分组序 = 状态类 → 命令类(`*_command`) → 数值类，组内拼音（「重置」按 chóng 排命令组首），行状态徽章按 danger/warning/success 档配色；数值出口 HALF_EVEN 修约（默认 2 位小数）。
- **在线判定**：`AsmOnlineJudge`——online_status attr 优先，兜底取**最新**参数时间戳（任一参数 60s 内更新=在线）。
- **设备控制入口**：详情抽屉顶部状态行「设备控制」按钮——显隐唯一判定源 = 前端常量 `control/constants.js` 的 `CONTROLLABLE_TYPES`（空调/灯光/排风扇/门禁/采样管/稳压电源 6 类型；增删受控类型改常量，须与 DM `env_device_settings` 配置保持同步）。

## 设备配置页（station_device，2026-08-21）

- **结构**（照 ADM air_device 四组件）：左 sidebar 37 类型槽（22 类型，多实例类型逐槽）分组全景 + 已配置 ✓ 徽标；右 Detail 三态状态机（未配置→「配置设备」蓝钮；已配置→更换设备/修改配置/移除）；复用弹窗（add/replace，当前台标灰，底部「+配置新设备」常驻）；配置向导（vendor 选型 + lit `<flow-form>` schema 驱动步进，CREATE_ENTRY 后端原子收口，前端只关弹窗刷新）。
- **后端契约**：`/asm-monitor/device/*` 11 端点（读 `asm-monitor:device:list` / 写 `asm-monitor:device:edit`），三态纯读 registry；provision→submit→CREATE_ENTRY 原子收口+失败回滚；变更审计落 `asm_device_change_record`（FIRST_BIND/REBIND/REPLACE/UNBIND/RECONFIGURE，append-only）。现行设计 `docs/device-config.md`。
- **启动装载门控**：airstation logic 设备就绪前显骨架横幅（轮询 snapshot 非空），勿把 registry 空渲染成「全部未配置」。
- **前端元数据**：`stationParamMeta.js` 37 槽 label/分组（与后端 StationParamMeta 枚举名 join）；槽列表/绑定状态动态取 `GET /device/params`。
- **config-flow lit lib**：`static/lib/config-flow`（webpack CopyPlugin 进 `dist/lib/config-flow/`），Dialog 从本集成 bundle script tag 反推 publicPath 加载。

## 设备控制页（device_control，2026-08-20）

- **范围**：DM `GET /device/control/settings` 配置驱动的 7 台站房设备（每类设备显示定制一个 js：`control/devices/`），element-plus 5 种 displayType 渲染（`control/renderers/`）；`?focus={uid}` 锚点滚动+高亮。
- **数据流（纯流式）**：加载仅两次查询（snapshot + DM settings），此后值变化走 SSE `device.data.update` 帧、控制终态走 SSE **`control.completed`** 帧（`AsmControlService.finalizeOutcome` → `AsmSseBroadcaster.broadcastNamed`），零轮询；SSE 断连中禁「确认」，重连一次性补偿（snapshot 重拉 + 在途项 `GET /asm-monitor/control/{id}` 单查）。
- **交互模型**：per-card 修改出「确认/撤销」（确认=串行逐 attr POST /control，行内徽章 PENDING→SUCCESS/FAILED/TIMEOUT）；dirty 字段不被 SSE 帧覆盖（其他渠道控制实时反映）；门禁 stateless 命令纳入统一待执行模型（primary+对勾角标）。
- **settled 收敛模型**：`control.completed` 终态帧带 `afterValue`（=下发设置值+当时单位，与审计 after_value/requested_value 同源同形，三态统一记录——不读回执行后镜像态，规避 logic 镜像异步刷新竞态），前端归一化为 cmd.value 同形态后作 settled 值，「帧到即收敛」无回跳；`SETTLED_MAX_MS=3s` 仅兜底防迟到旧值 poll 帧（写传播与设备轮询竞态的实证场景），窗内不同值帧判迟到旧帧忽略、同值帧=收敛交还 live；显示优先级 `pending → settled → live`。批次徽章生命周期：beginSubmit 清整卡旧徽章。

## 报警记录生命周期（episode 心跳窗）

`asm_alarm_record` 非「每次触发一行」：新 episode 落 ACTIVE（end_time=NULL）→ 心跳窗（默认 5min，`asm.alarm.heartbeat-window-minutes`）内再触发**同 id 续期** last_breach_time → 窗口过后 1min sweep 闭单（INACTIVE+end_time）→ core 重启从 ACTIVE 行重建内存徽章 registry。REST `alarm-record/list?status=ACTIVE|INACTIVE` 按状态过滤。

## 维护者快速上手

### 构建与部署

```bash
# 后端（模块目录）
mvnd clean install

# 前端：改 .vue 后必须重建 dist 再打包（mvnd 不触发 webpack）
cd src/main/resources/vue-modules && npm install && npm run release && mvnd install

# 起服务：core 必须以 workspace 根为 CWD 启动（restart-ecat-dev.sh 已封装）
# 页面：http://localhost:8081/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/{page}
#   page ∈ monitor / history_data / alarm_rule / alarm_list / control_list / config
```

DDL 手动 apply（无自动迁移）：`src/main/resources/sql/asm_data.sql`（数据表+规则 seed）与 `asm_auth.sql`（菜单/权限），用 workspace 的 adm-db.py 或 psql 执行，幂等可重跑。

### REST（8080 ruoyi 上下文，前缀 /asm-monitor，登录走 ruoyi 鉴权）

| 端点 | 用途 |
|---|---|
| `GET /snapshot?unit=standard\|custom` | 站房设备实时态（attr 行带 status 枚举/attrGroup 分组键；activeAlarms=活跃报警明细） |
| `GET /stream?token=` | 总览 SSE 长连接（具名帧 device.data.update；帧含双单位值+status+ruleAlarmActive） |
| `GET /history` | granularity / params / mode / unit / start / end / 分页；行含 `value`（数值均值）与 `value_text`（非数值统计值，互斥） |
| `GET /stat-params` | 可查参数目录（SDK 同源） |
| `/alarm-rule` CRUD、`GET /alarm-record/list?status=` | 报警规则（改后热加载；list 行含 `deviceLabels`[{slot,attrs}] 中文标注；写端点 alarmType 治理：重复 400「报警标识已存在」、预置规则（settingContent configurable!=true）标识禁改 400）与记录（状态过滤可选（ACTIVE/INACTIVE）；时间窗 = episode 区间重叠（与 SDK queryAlarmEntries 同口径，持续中 ACTIVE 行默认视图可见）；行含 `device_label`/`attr_label`/`trigger_time`(=start_time)/`recover_time`(=end_time)/`duration_ms`(活跃行 null)；alarmType 为语义化标识如 `water_leak`/`intrusion`，合法值=seed 16 类+用户新建） |
| `POST /control`、`GET /control-record/list`、`GET /control/{id}` | 控制下发（本站 web，origin=LOCAL）、审计查询、单条终态查询（仅 SSE 重连补偿用，非轮询通道） |
| `GET/PUT /config-stat`、`GET/PUT /config-unit` | 聚合配置（enabled/粒度掩码/物化 mode；GET 行含 `device_label`/`attr_label` 中文标注）与单位偏好（STANDARD 行由 seed 维护不开放写）；config-unit PUT 体含可空 `displayPrecision`（0-6，null=不覆盖），snapshot 数值行含 `unitKey`/`displayPrecision`/`unitOptions`（单位设置抽屉数据源） |

### 对外 SDK（跨集成消费方）

```java
AirStationSdk sdk = ((EnvAirStationManagerIntegration) core.getIntegrationRegistry()
    .getIntegration("com.ecat:integration-env-air-station-manager")).getAirStationSdk();
// queryStat(params, granularity, mode, start, end) —— STORAGE 单位原值，机对机口径；
//     数值 series 均值在 value，非数值 series（ALARM/STATE 白名单）value=null、
//     统计值在 valueText（ALARM: normal/alarm；STATE: 状态串），unit 空串=显无单位
// queryAlarmEntries(alarmType, start, end, limit) —— 按报警标识+时间段查报警条目：
//     窗口左开右闭 (start,end] episode 区间重叠（end 时刻触发算本期、恰在 start 闭单归上期，
//     连续分窗无缝无重），持续中 ACTIVE 行天然可查；行形状=前端报警表格列+alarmType 回显，
//     deviceLabel/attrLabel 解析不到=null（回退归消费方）、durationMs 持续中=null
// listStatParams() / listAlarmTypes()（报警标识目录）/ querySnapshot(uid)
// control(SdkControlRequest) —— 请求对象：origin 由调用方声明（LOCAL=本站/集成自身发起，
//     caller=发起方集成坐标；REMOTE=第三方代传远程侧指令，caller=最终用户标识）；
//     unit null 非法/空串=按属性默认单位/非空=full string「枚举类名.枚举常量名」（禁 °C 符号）；
//     同步受理返 PENDING，终态 SUCCESS/FAILED/TIMEOUT 异步回填（可按 recordId 回查）
```

消费方 maven 依赖本 jar（provided），只允许 import `api` 包（护栏测试强制：零 ruoyi/Spring/ecat-core 依赖）。**完整方法/DTO/横切语义手册：`docs/sdk.md`**。

## 测试与回归

- **模块单测**：`mvnd clean test`（398 个，覆盖引擎（数值 avg + 非数值 ALARM/STATE）/规则/生命周期/在线判定/排序/SDK 全域/控制请求校验与单位换算）
- **浏览器回归（强制，API 冒烟不替代）**：Playwright 套件已移至 workspace `.claude/skills/ruoyi-e2e-test/skills/env-air-station-manager/e2e/`（2026-09-09 迁出本仓，入口与陷阱表见该目录 README 与 asm-e2e-test.md）——`npm run test:asm-e2e`（ro 只读组并行 / real 写操作组串行；g1-g13 分组脚本）。前置：core+8081 起且 vue 注入（globalSetup 自检）。
- **DB 侧回归**：workspace ruoyi-e2e-test skill 的 `asm-regression.py`（bucket-check / idempotency / compute-log / alarm-check[episode 心跳断言] / control-check / linkage-check）。

## 排障与边界

- 物化排障第一入口：`asm_stat_compute_log`（逐粒度 SUCCESS/FAILED）。物化延迟 minute+10s / 5min+30s / hour+180s，属设计等待。
- 时区：history 入参是壁钟（Asia/Shanghai），stat 表 `data_time` 是 UTC；查错时段返空不是 bug。
- FRONT/BACK 同 `data_time` 双行并存是设计（BOTH 物化），查询必须带 mode（缺省 BACK）。
- 控制旁路边界：直写 core `/core-api/.../attributes/.../value` 不经本集成审计（设计如此）。
- 报警并存：env-alarm-manager 的同域规则未下线，同一次超限两边各落一条记录，按表过滤断言。
- SSE 停滞（浏览器连不上但 curl 正常）：vite dev 代理在多轮 monitor 访问/core 重启后积累孤儿 socket——重启 8081 vite 即恢复（bug-record-20260820-093451）。

## 维护约定

- 动态 jar 单例只认 `@Service`/`@RestController`（`@Component` 静默跳过）。
- `.vue` 用 Options API 时组件 `name` 必须等于路由 name（宿主 keep-alive 按名匹配）；改前端的完整链：release → install → 重启 core → 浏览器 ignoreCache reload（304 陷阱：jar mtime 重置会命中缓存旧 bundle，人工核验用 Ctrl+Shift+R）。
- `vue-modules/air-station-manager/.config.js`、`.index.js` 由 `npm run gen` 生成，勿手改、不入库；`dist/`、`node_modules/`、`test-results/` 同样不入库。
- Java 8 语法 + JUnit 5；禁 mock final 类（AttrState 用 builder 构造）；测试禁 Thread.sleep。
- 配置不回溯：改 config-stat/mask/mode 只影响后续物化窗口，不触发历史重算。
