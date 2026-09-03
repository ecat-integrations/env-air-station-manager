import { FullConfig, chromium } from '@playwright/test';
import { mkdirSync } from 'fs';
import { dirname } from 'path';

/**
 * 全局环境前置自检（所有 spec 前跑一次，任一不过直接 fail）。
 *
 * 不启动服务（core/ruoyi/vite 由 restart-ecat-dev.sh 管），只验证：
 *   1. 8081 vite 前端可达
 *   2. 8080 ruoyi-admin 起 + ASM vue 模块已注入（air-station-manager.js 返 200，404=页面空白无意义）
 */
async function globalSetup(config: FullConfig) {
  const baseURL = process.env.BASE_URL || 'http://localhost:8081';
  const apiURL = process.env.API_BASE_URL || 'http://localhost:8080';
  console.log(`\n=== ASM E2E 环境自检 === 前端(8081): ${baseURL} 后端(8080): ${apiURL}`);

  const fe = await fetch(`${baseURL}/`).then(r => r.status).catch(() => -1);
  if (fe < 200 || fe >= 400) {
    throw new Error(`[setup] 8081 前端不可达（status=${fe}）—— 先起 vite（restart-ecat-dev.sh）`);
  }
  const asmJs = await fetch(`${apiURL}/ecat-integrations/integration-env-air-station-manager/air-station-manager.js`)
    .then(r => r.status).catch(() => -1);
  if (asmJs !== 200) {
    throw new Error(`[setup] ASM vue 未注入（air-station-manager.js status=${asmJs}）—— 检查 npm run release + mvn install + core 重启（cwd=workspace 根）`);
  }
  // 套件级登录一次 → storageState 落盘，全部用例带登录态启动（登录页慢渲染退出用例路径，
  // 每用例重复登录的三段等待/偶发全部消除——2026-09-02 G12-5a 全量尾部三次偶发的根治）。
  const statePath = 'test-results/.asm-auth.json';
  mkdirSync(dirname(statePath), { recursive: true });
  const browser = await chromium.launch();
  for (let attempt = 1; attempt <= 3; attempt++) {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.goto(`${baseURL}/#/login`);
    await page.fill('input[placeholder*="账号"]', process.env.ASM_USER || 'Admin7s9k2G5');
    await page.fill('input[placeholder*="密码"]', process.env.ASM_PASS || '7sK2pG9dR3tQ');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    try {
      await page.waitForURL((u) => !u.href.includes('/login'), { timeout: 30_000 });
      await ctx.storageState({ path: statePath });
      console.log(`=== 登录态已存 ${statePath}（attempt ${attempt}）===`);
      break;
    } catch (e) {
      if (attempt === 3) throw new Error(`[setup] 套件级登录失败（3 次）: ${e}`);
    } finally {
      await ctx.close();
    }
  }
  await browser.close();
  console.log(`=== 环境自检通过（8081 ✓ / ASM vue 注入 ✓ / 登录态 ✓）===\n`);
}

export default globalSetup;
