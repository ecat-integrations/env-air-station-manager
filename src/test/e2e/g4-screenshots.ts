/**
 * 一次性截图脚本（node g4-screenshots.ts）：六页 fullPage 截图 → docs/screenshots/{page}.png。
 * 像素级视觉核验由人工完成，截图仅存档。非 playwright test（无断言），CI 外手动跑。
 */
import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const BASE = process.env.BASE_URL || 'http://localhost:8081';
const ROUTE = '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index';
const PAGES = ['monitor', 'history_data', 'alarm_rule', 'alarm_list', 'control_list', 'config'];
const OUT = path.resolve(__dirname, '../../../docs/screenshots');

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto(`${BASE}${ROUTE}/monitor`);
  await page.waitForLoadState('networkidle');
  const u = page.locator('input[placeholder*="账号"]');
  if (await u.count() > 0) {
    await u.fill(process.env.ASM_USER || 'Admin7s9k2G5');
    await page.locator('input[placeholder*="密码"]').fill(process.env.ASM_PASS || '7sK2pG9dR3tQ');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await page.waitForURL((x) => !x.href.includes('/login'), { timeout: 20000 });
  }
  for (const key of PAGES) {
    await page.goto(BASE + '/#/index');
    await page.waitForLoadState('networkidle');
    await page.goto(`${BASE}${ROUTE}/${key}`);
    await page.waitForLoadState('networkidle');
    await page.locator('.asm-page').first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(1500); // 等数据/图表初绘
    await page.screenshot({ path: path.join(OUT, `${key}.png`), fullPage: true });
    console.log(`saved ${key}.png`);
  }
  await browser.close();
})();
