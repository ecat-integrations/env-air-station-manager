# ASM（env-air-station-manager）Playwright E2E

黑盒验收 G 段（docs/asm-blackbox-acceptance.md §G）固化为可重跑套件。仿
`env-air-device-manager/src/test/e2e` 范式：自包含 package + page-objects + specs，`workers=1` 串行。

## 前置

- core 起（**cwd=workspace 根**）+ ASM vue 注入（`npm run release` + `mvnd install`）+ ruoyi 8081/8080 起。
- globalSetup 自检：8081 可达 + `air-station-manager.js` 返 200，不过直接 fail。
- 凭据默认 `Admin7s9k2G5/7sK2pG9dR3tQ`（`ASM_USER`/`ASM_PASS` 可覆盖）。

## 运行

```bash
cd ecat-integrations/env-air-station-manager/src/test/e2e
npm run test:asm-e2e        # 全量 G 段 7 用例（G1×1 + G2×5 + G3×1）+ G4 样式 5 用例
npm run test:g1|test:g2|test:g3   # 分段
npm run report              # html 报告
```

## 用例

| tag | 覆盖 | 说明 |
|---|---|---|
| @g1 | G1 六页渲染 | `.app-main` innerText 非空 + `.asm-page` 挂载 + console/pageerror 0；ignoreCache reload |
| @g2 history | G2 历史查询出图出表 | MINUTE 粒度近 30min，stat-params 首个候选；echarts canvas + 明细行>0 + 分页器 |
| @g2 monitor | G2 总览 SSE 实时推送 | /asm-monitor/stream 请求 + `window.__asmSseFrames` ≥1 帧（device.data.update patch 瓦片）；断连横幅默认不显示 |
| @g2 alarm_rule | G2 规则编辑保存 | seed 规则「设备间温度异常」duration 5→6 保存生效 → 还原 5 |
| @g2 control | G2 控制下发+终态回查 | exhaust_fan speed=low → SUCCESS/REMOTE/after 非空 → 还原 off |
| @g2 config | G2 配置行内保存 | config-stat 首行物化范围 BOTH→FRONT 生效 → 还原 BOTH |
| @g3 | G3 权限 | admin 建无权限角色+账号（测完删）；API snapshot body code=403（HTTP 200）+ 浏览器不渲染数据卡片 |

## 陷阱（写新用例前必读）

- **登录后路由竞态**：登录/整页 reload 后集成路由（webintegration 加载）注册晚于路由解析，先落 404 catchall；
  同 hash 再 goto 不重匹配 → 必须经 `/#/index` 中转跳一次（`AsmBasePage.navToOwn()` 已封装）。
- **keep-alive 缓存**：hash 导航重激活不重取，断言前点「刷新/查询」。
- **SSE 长连接让 networkidle 永不可达**：monitor 页 `/asm-monitor/stream` 长连接不关闭，任何
  `waitForLoadState('networkidle')` 必超时——等待一律用 `load` + 内容就绪（`AsmBasePage.goto` 已封装
  `.asm-page` innerText>50 确定性等待）；SSE 帧断言用 `window.__asmSseFrames` 计数器（组件 handleSseUpdate 暴露）。
- **控制列表同秒并列**：连发两行 created_at 同秒时列表首行不稳定，断言扫全行不认 `.first()`。
- **ruoyi 登录用户名长度**：>20 字符用户名登录恒「用户不存在」（DB 行在库），测试账号用户名 ≤20。
- **G3 无权限账号**：集成路由对其不注册，monitor 404 是预期——只断言数据卡片为 0，不能等 `.asm-page`。
- 测试数据全部用后还原（规则 duration、排风扇 speed、config mode），勿留脏状态。
