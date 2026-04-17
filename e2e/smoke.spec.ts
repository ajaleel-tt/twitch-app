import { test, expect } from '@playwright/test';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';

test('page loads without JS errors', async ({ page }) => {
  const jsErrors: string[] = [];

  page.on('pageerror', (err) => {
    jsErrors.push(err.message);
  });

  const response = await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });

  // Verify the server returned HTML
  expect(response?.status()).toBe(200);

  // Check the raw HTML title is present (server-rendered, no JS needed)
  await expect(page).toHaveTitle('Twitch Category Tracker');

  // Wait for main.js to execute — the Calico app mounts into #app.
  // If JS crashes on load, the div stays empty.
  await page.waitForFunction(
    () => (document.getElementById('app')?.children.length ?? 0) > 0,
    { timeout: 30000 }
  );

  // Fail if any uncaught JS exceptions fired
  expect(jsErrors, `JS errors on page load:\n${jsErrors.join('\n')}`).toHaveLength(0);
});
