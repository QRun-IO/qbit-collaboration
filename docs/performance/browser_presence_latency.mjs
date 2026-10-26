// Real UI observations against a caller-owned two-node acceptance fixture.
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';

const [frontendRepo, base, output, countValue = '25'] = process.argv.slice(2);
const samples = Number(countValue);
if (!frontendRepo || !base || !output || !Number.isInteger(samples) || samples < 5 || samples > 100) {
  throw new Error('Expected frontend repo, loopback base URL, result path and 5..100 samples');
}
if (!['localhost', '127.0.0.1'].includes(new URL(base).hostname)) {
  throw new Error('Only the disposable loopback fixture is supported');
}
const require = createRequire(resolve(frontendRepo, 'package.json'));
const { chromium, expect } = require('@playwright/test');
const result = { status: 'FAIL', samples: [], streams: [], requests: [], errors: [],
  scope: 'Chromium focus-to-remote-DOM observation, including automation and UI debounce; no concurrent load' };
const browser = await chromium.launch({ headless: true });
try {
  async function editor(user, node) {
    const context = await browser.newContext();
    await context.addCookies([
      { name: 'collab_dev_user', value: user, url: base },
      { name: 'presence_node', value: node, url: base },
    ]);
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => result.errors.push(error.message));
    page.on('response', response => {
      const path = new URL(response.url()).pathname;
      if (path.endsWith('/realtime/stream')) {
        result.streams.push({ user, status: response.status(), type: response.headers()['content-type'] });
      }
      if (path.includes('/presence/leases')) {
        result.requests.push({ user, status: response.status(), node: response.headers()['x-acceptance-node'] });
      }
    });
    await page.goto(`${base}/app/person/1/edit?createMockSession=true`);
    await expect(page.locator('[data-qqq-id="firstName"]')).toBeVisible();
    return page;
  }
  const observer = await editor('alice', 'first');
  const sender = await editor('admin', 'second');
  await observer.locator('[data-qqq-id="firstName"]').fill('Retained observer draft');
  await sender.locator('[data-qqq-id="firstName"]').fill('Retained sender draft');
  await expect(observer.locator('[data-presence-field="firstName"]')).toContainText('Demo Administrator');
  for (let index = 0; index < samples; index++) {
    const field = index % 2 === 0 ? 'lastName' : 'firstName';
    const previous = field === 'firstName' ? 'lastName' : 'firstName';
    await expect(observer.locator(`[data-presence-field="${field}"]`)).not.toContainText('Demo Administrator');
    const before = performance.now();
    await sender.locator(`[data-qqq-id="${field}"]`).focus();
    await expect(observer.locator(`[data-presence-field="${field}"]`)).toContainText('Demo Administrator');
    result.samples.push({ field, propagation_ms: performance.now() - before });
    await expect(observer.locator(`[data-presence-field="${previous}"]`)).not.toContainText('Demo Administrator');
  }
  await expect(observer.locator('[data-qqq-id="firstName"]')).toHaveValue('Retained observer draft');
  await expect(sender.locator('[data-qqq-id="firstName"]')).toHaveValue('Retained sender draft');
  for (const user of ['alice', 'admin']) {
    expect(result.streams.some(stream => stream.user === user && stream.status === 200 && stream.type?.includes('text/event-stream'))).toBe(true);
  }
  expect(result.requests.some(request => request.user === 'alice' && request.node === 'first')).toBe(true);
  expect(result.requests.some(request => request.user === 'admin' && request.node === 'second')).toBe(true);
  expect(result.requests.every(request => request.status === 200)).toBe(true);
  expect(result.errors).toEqual([]);
  const ordered = result.samples.map(sample => sample.propagation_ms).sort((a, b) => a - b);
  result.percentiles_ms = Object.fromEntries([['p50', .5], ['p95', .95], ['p99', .99], ['max', 1]]
    .map(([name, quantile]) => [name, ordered[Math.ceil(ordered.length * quantile) - 1]]));
  result.status = 'PASS';
} catch (error) {
  result.failure = String(error);
  process.exitCode = 1;
} finally {
  await browser.close();
  await writeFile(output, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}
