// Real browser recovery against the disposable two-JVM harness. No API mocks.
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import path from 'node:path'

const [frontend, first, second, container, output, profile = 'chromium'] = process.argv.slice(2)
const require = createRequire(path.join(frontend, 'package.json'))
const { chromium, firefox, webkit, devices, expect } = require('@playwright/test')
const profiles = {
  chromium: [chromium, devices['Desktop Chrome']],
  firefox: [firefox, devices['Desktop Firefox']],
  webkit: [webkit, devices['Desktop Safari']],
  mobile: [chromium, devices['Pixel 7']],
  tablet: [webkit, devices['iPad (gen 7)']],
}
if (!profiles[profile]) throw new Error(`Unknown browser profile: ${profile}`)
const [engine, device] = profiles[profile]
const browser = await engine.launch({ headless: true })
const contexts = []
try {
  const pages = []
  for (const [port, user] of [[first, 'alice'], [second, 'bob']]) {
    const context = await browser.newContext(device)
    contexts.push(context)
    await context.tracing.start({ screenshots: true, snapshots: true })
    const page = await context.newPage()
    await page.goto(`http://127.0.0.1:${port}/?createMockSession=true&user=${user}`)
    // The root export redirects after hydration; finish it before record navigation.
    await page.waitForURL(url => /^\/app\/?$/.test(url.pathname), { waitUntil: 'domcontentloaded' })
    await page.goto(`http://127.0.0.1:${port}/app/person/1`)
    pages.push(page)
  }
  const unavailable = page => page.getByText('Presence temporarily unavailable', { exact: true })
  await expect(pages[0].locator('[data-qqq-id="presence-bob"]')).toBeVisible({ timeout: 90000 })
  await expect(pages[1].locator('[data-qqq-id="presence-alice"]')).toBeVisible({ timeout: 90000 })
  execFileSync('docker', ['exec', container, 'redis-cli', 'FLUSHDB'])
  for (const page of pages) await expect(unavailable(page)).toBeVisible({ timeout: 35000 })
  await expect(pages[0].locator('[data-qqq-id="presence-bob"]')).toHaveCount(0)
  await expect(pages[1].locator('[data-qqq-id="presence-alice"]')).toHaveCount(0)
  await expect(pages[0].locator('[data-qqq-id="presence-bob"]')).toBeVisible({ timeout: 90000 })
  await expect(pages[1].locator('[data-qqq-id="presence-alice"]')).toBeVisible({ timeout: 90000 })
  for (const [index, page] of pages.entries()) {
    await expect(unavailable(page)).toHaveCount(0)
    await page.screenshot({ path: path.join(output, `recovered-${index}.png`) })
  }
  console.log(`PASS: ${profile} shows recovery unavailable, clears stale people, and rejoins both JVMs without reload.`)
} finally {
  for (const [index, context] of contexts.entries()) {
    await context.tracing.stop({ path: path.join(output, `browser-${index}.zip`) })
  }
  await browser.close()
}
