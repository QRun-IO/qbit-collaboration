// Verify owning-node routing with real published UI and backend artifacts.
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { writeFile } from 'node:fs/promises';

const [frontendRepo, base, output] = process.argv.slice(2);
if (!frontendRepo || !output || !['localhost', '127.0.0.1'].includes(new URL(base).hostname)) {
  throw new Error('Expected frontend repo, owned loopback URL and result path');
}
const require = createRequire(resolve(frontendRepo, 'package.json'));
const { chromium, expect } = require('@playwright/test');
const result = { status: 'FAIL', checks: [], errors: [] };
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  await context.addCookies([
    { name: 'collab_dev_user', value: 'alice', url: base },
    { name: 'presence_node', value: 'first', url: base },
  ]);
  const page = await context.newPage();
  page.on('pageerror', error => result.errors.push(error.message));
  const subscribed = page.waitForResponse(response =>
    new URL(response.url()).pathname.endsWith('/realtime/subscriptions') && response.status() === 200);
  await page.goto(`${base}/app/person/1/edit?createMockSession=true`);
  await expect(page.locator('[data-qqq-id="firstName"]')).toBeVisible();
  await page.locator('[data-qqq-id="firstName"]').fill('Keep this unsaved draft');
  const response = await subscribed;
  expect(response.headers()['x-acceptance-node']).toBe('first');
  const { connectionId } = response.request().postDataJSON();
  expect(typeof connectionId).toBe('string');
  for (const [node, expected] of [['first', 200], ['second', 404], ['first', 200]]) {
    // Keep the same session/user cookies and change only the test routing cookie.
    const cookies = (await context.cookies(base)).map(cookie =>
      `${cookie.name}=${cookie.name === 'presence_node' ? node : cookie.value}`).join('; ');
    const changed = await context.request.post(`${base}/qqq/v1/realtime/subscriptions`, {
      headers: { Cookie: cookies },
      data: { connectionId, add: ['record:person:1'], remove: [] }, timeout: 10000,
    });
    try {
      result.checks.push({ node: changed.headers()['x-acceptance-node'], status: changed.status() });
      expect(changed.headers()['x-acceptance-node']).toBe(node);
      expect(changed.status()).toBe(expected);
      if (expected === 200) {
        const body = await changed.json();
        expect(body.active).toContain('record:person:1');
        expect(body.refused ?? []).toEqual([]);
      }
    } finally { await changed.dispose(); }
  }
  await expect(page.locator('[data-qqq-id="firstName"]')).toHaveValue('Keep this unsaved draft');
  expect(result.errors).toEqual([]);
  result.status = 'PASS';
} catch (error) {
  result.failure = String(error);
  process.exitCode = 1;
} finally {
  await browser.close();
  await writeFile(output, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}
