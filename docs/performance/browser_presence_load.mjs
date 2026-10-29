// Bounded real HTTP load using caller-owned authenticated browser contexts.
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';

export async function startPresenceLoad(browser, base, expect) {
  const state = { workers: 16, completed: 0, errors: [], nodes: {}, elapsed_ms: 0 };
  const contexts = [];
  const leases = [];
  let running = true;
  let stopped = false;
  let tasks = [];
  const began = performance.now();
  const endpoint = `${base}/qqq/v1/qbit/collaboration/presence/leases`;
  async function post(context, path, data) {
    const response = await context.request.post(endpoint + path, { data, timeout: 10000 });
    try {
      expect(response.status()).toBe(200);
      const node = response.headers()['x-acceptance-node'];
      state.nodes[node] = (state.nodes[node] ?? 0) + 1;
      return await response.json();
    } finally { await response.dispose(); }
  }
  async function stop() {
    if (stopped) return;
    stopped = true;
    running = false;
    await Promise.all(tasks);
    state.elapsed_ms = performance.now() - began;
    for (const lease of leases) {
      try {
        const response = await lease.context.request.delete(`${base}/qqq/v1/qbit/collaboration/presence`, {
          data: { ...lease.target, connectionId: lease.id, sequence: ++lease.sequence }, timeout: 10000,
        });
        try { expect(response.status()).toBe(200); } finally { await response.dispose(); }
      } catch (error) { state.errors.push(String(error)); }
    }
    for (const context of contexts) await context.close();
  }
  try {
    for (const [index, user] of ['bob', 'carol', 'dave', 'erin'].entries()) {
      const context = await browser.newContext();
      contexts.push(context);
      await context.addCookies([
        { name: 'collab_dev_user', value: user, url: base },
        { name: 'presence_node', value: index % 2 ? 'second' : 'first', url: base },
      ]);
      const page = await context.newPage();
      await page.goto(`${base}/app/person/1?createMockSession=true`);
      await expect(page.locator('[data-qqq-id="record-view-title-person"]')).toBeVisible();
      await page.close();
      for (let tab = 0; tab < 4; tab++) {
        const target = { table: 'person', pk: '1' };
        const opened = await post(context, '', target);
        expect(typeof opened.connectionId).toBe('string');
        leases.push({ context, target, id: opened.connectionId, sequence: 0, completed: 0 });
      }
    }
    tasks = leases.map(async lease => {
      try {
        while (running) {
          await post(lease.context, '/renew', { ...lease.target, connectionId: lease.id,
            sequence: ++lease.sequence, mode: 'VIEWING' });
          lease.completed++;
          state.completed++;
          await delay(100);
        }
      } catch (error) { state.errors.push(String(error)); running = false; }
    });
    await expect.poll(() => leases.filter(lease => lease.completed > 0).length).toBe(16);
    return { state, stop };
  } catch (error) {
    await stop();
    throw error;
  }
}
