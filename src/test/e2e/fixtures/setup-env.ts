import { FullConfig } from '@playwright/test';

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
  console.log(`=== 环境自检通过（8081 ✓ / ASM vue 注入 ✓）===\n`);
}

export default globalSetup;
