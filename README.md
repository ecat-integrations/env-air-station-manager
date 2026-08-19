# 空气站房管理集成(env-air-station-manager)

管理 `logicdevice-airstation` 的 22 类站房逻辑设备（uniqueId 前缀 `logicdevice_station.*`）：消费 `device.data.update` 总线事件，提供**实时快照、三级均值聚合、历史查询、动环报警、控制审计、对外 SDK** 六项能力。设备生命周期归 airstation 集成，本集成不建/不删逻辑设备；与 env-air-device-manager 为兄弟集成，互不依赖。

## 功能总览

| 能力 | 说明 |
|---|---|
| 数据管道 | 总线消费站房设备事件 → `asm_data_sample`（raw，TimescaleDB hypertable）；新 series 首见自动 seed 聚合/单位配置 |
| 三级均值聚合 | minute/5min/hour 级联（加权均值，非均值再均值）；FRONT=[S,E)/BACK=(L,R] 双标物化（HJ663 口径），按 series 可配 |
| 历史查询 | REST 按粒度/mode/参数/时间窗查询 + 单位偏好出口（MONITOR/HISTORY 换算，缺行显原生） |
| 动环报警 | 16 条 seed 规则（温湿度/供电/漏水/门禁/标气泄漏等），range+持续 / 瞬时阈值 / 状态串三类判定，5 分钟去重、热加载、报警联动（如泄漏→开排风扇） |
| 控制审计 | 统一控制收口（REST=REMOTE / SDK=LOCAL 双入口），`asm_control_record` 记录调用方、执行前后值、终态（PENDING→SUCCESS/FAILED/TIMEOUT） |
| 对外 SDK | `AirStationSdk`（api 包零依赖），其他集成进程内取用查询/报警/控制能力 |

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
| `GET /snapshot` | 站房设备实时态（live 优先，raw 回放标 LIVE/RAW） |
| `GET /history` | granularity / params / mode / unit / start / end / 分页 |
| `GET /stat-params` | 可查参数目录（SDK 同源） |
| `/alarm-rule` CRUD、`GET /alarm-record/list` | 报警规则（改后热加载）与记录 |
| `POST /control`、`GET /control-record/list` | 控制下发（REMOTE）与审计查询 |
| `GET/PUT /config-stat`、`GET/PUT /config-unit` | 聚合配置（enabled/粒度掩码/物化 mode）与单位偏好 |

### 对外 SDK（跨集成消费方）

```java
AirStationSdk sdk = ((EnvAirStationManagerIntegration) core.getIntegrationRegistry()
    .getIntegration("com.ecat:integration-env-air-station-manager")).getAirStationSdk();
// queryStat(params, granularity, mode, start, end) —— STORAGE 单位原值，机对机口径
// listStatParams() / querySnapshot(uid) / queryAlarmRecords(uid, start, end, limit)
// control(uid, attrId, value, caller) —— origin=LOCAL，caller=消费方坐标（必填）
```

消费方 maven 依赖本 jar（provided），只允许 import `api` 包（护栏测试强制：零 ruoyi/Spring/ecat-core 依赖）。

## 测试与回归

- **模块单测**：`mvnd clean test`（当前 199 个，覆盖引擎/规则/控制/SDK 全域）。
- **浏览器回归（强制，API 冒烟不替代）**：`src/test/e2e/` Playwright 套件，`npm run test:asm-e2e`（g1 六页渲染 / g2 五项核心交互 / g3 无权限 403 / g4 样式结构）。前置：core+8081 起且 vue 注入（globalSetup 自检）。陷阱表见 e2e/README.md。
- **DB 侧回归**：workspace ruoyi-e2e-test skill 的 `asm-regression.py`（bucket-check / idempotency / compute-log / alarm-check / control-check / linkage-check 六子命令）。

## 排障与边界

- 物化排障第一入口：`asm_stat_compute_log`（逐粒度 SUCCESS/FAILED）。物化延迟 minute+10s / 5min+30s / hour+180s，属设计等待。
- 时区：history 入参是壁钟（Asia/Shanghai），stat 表 `data_time` 是 UTC；查错时段返空不是 bug。
- FRONT/BACK 同 `data_time` 双行并存是设计（BOTH 物化），查询必须带 mode（缺省 BACK）。
- 控制旁路边界：直写 core `/core-api/.../attributes/.../value` 不经本集成审计（设计如此）。
- 报警并存：env-alarm-manager 的同域规则未下线，同一次超限两边各落一条记录，按表过滤断言。

## 维护约定

- 动态 jar 单例只认 `@Service`/`@RestController`（`@Component` 静默跳过）。
- `.vue` 用 Options API 时组件 `name` 必须等于路由 name（宿主 keep-alive 按名匹配）；改前端的完整链：release → install → 重启 core → 浏览器 ignoreCache reload。
- `vue-modules/air-station-manager/.config.js`、`.index.js` 由 `npm run gen` 生成，勿手改、不入库；`dist/`、`node_modules/` 同样不入库。
- Java 8 语法 + JUnit 5；禁 mock final 类（AttrState 用 builder 构造）；测试禁 Thread.sleep。
- 配置不回溯：改 config-stat/mask/mode 只影响后续物化窗口，不触发历史重算。
