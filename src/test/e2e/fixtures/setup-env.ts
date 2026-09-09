import { FullConfig, chromium } from '@playwright/test';
import { mkdirSync } from 'fs';
import { dirname } from 'path';
import { execFileSync } from 'child_process';

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

  // 探针带重试（3 次 × 2s）：core/8081 重启窗口（jar 换包/端口短暂拒连）内 globalSetup 不整挂
  //（grid-pager 验证期实证撞过：一次瞬时拒连使套件连挂两轮）。
  const probe = async (url: string): Promise<number> => {
    for (let i = 0; i < 3; i++) {
      const st = await fetch(url).then(r => r.status).catch(() => -1);
      if (st >= 200 && st < 400) return st;
      if (i < 2) await new Promise(r => setTimeout(r, 2000));
    }
    return -1;
  };
  const fe = await probe(`${baseURL}/`);
  if (fe < 0) {
    throw new Error(`[setup] 8081 前端不可达（3 次探活失败）—— 先起 vite（restart-ecat-dev.sh）`);
  }
  const asmJs = await probe(`${apiURL}/ecat-integrations/integration-env-air-station-manager/air-station-manager.js`);
  if (asmJs !== 200) {
    throw new Error(`[setup] ASM vue 未注入（air-station-manager.js 3 次探活 status=${asmJs}）—— 检查 npm run release + mvn install + core 重启（cwd=workspace 根）`);
  }
  // 套件级登录一次 → storageState 落盘，全部用例带登录态启动（登录页慢渲染退出用例路径，
  // 每用例重复登录的三段等待/偶发全部消除——2026-09-02 G12-5a 全量尾部三次偶发的根治）。
  // 登录前先关验证码（共享公网 Redis 会被外部部署周期覆写回开，本套件登录脚本不解码，
  // 开着即整套全挂——脚本幂等，来源/机制见 ruoyi-e2e-test skill §9；失败不阻塞，登录若因
  // 验证码失败会在下面重试中暴露）。
  try {
    // e2e 运行目录 src/test/e2e → workspace 根 5 层
    const out = execFileSync('python3', [
      '../../../../../.claude/skills/ruoyi-e2e-test/scripts/disable-captcha.py',
      '../../../../..',
    ], { cwd: process.cwd(), encoding: 'utf8', timeout: 20_000 });
    console.log(`=== 验证码预处理: ${out.trim()} ===`);
  } catch (e) {
    console.log(`=== 验证码预处理跳过（${(e as Error).message.split('\n')[0]}）===`);
  }
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
