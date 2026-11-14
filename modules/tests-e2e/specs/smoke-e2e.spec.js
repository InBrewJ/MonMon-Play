// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Production Smoke Test: Full User Lifecycle via RAuthy', () => {

  test('Log in via RAuthy, verify SPOG metrics, add account, record balance, and delete account', async ({ page }) => {
    test.setTimeout(60000);
    const targetBaseUrl = process.env.BASE_URL || 'https://monmon.dryark.uk';
    const demoEmail = process.env.DEMO_EMAIL || 'demo@urawizard.com';
    const demoPassword = process.env.DEMO_PASSWORD || 'demo';

    // 1. Navigate to /login and verify redirect to RAuthy OIDC authorize endpoint
    console.log(`Step 1: Navigating to ${targetBaseUrl}/login...`);
    await page.goto(`${targetBaseUrl}/login`, { waitUntil: 'networkidle' });

    // Expect to be on Rauthy authorize page
    expect(page.url()).toContain('identity.dryark.uk');
    expect(page.url()).toContain('/auth/v1/oidc/authorize');

    // 2. Perform 2-step login on RAuthy
    console.log(`Step 2: Authenticating with user credentials (${demoEmail})...`);
    const emailInput = page.locator('input[type="email"]');
    await expect(emailInput).toBeVisible({ timeout: 10000 });
    await emailInput.fill(demoEmail);
    await page.click('button:has-text("Login")');

    // Wait for password step to appear
    const passwordInput = page.locator('input[type="password"]');
    await expect(passwordInput).toBeVisible({ timeout: 10000 });
    await passwordInput.fill(demoPassword);
    
    // Submit password and follow OIDC authorization code redirect back to MonMon
    await page.click('button:has-text("Login")');

    // 3. Check that Rauthy redirects to the SPOG page
    console.log('Step 3: Checking redirect to /spog...');
    await page.waitForURL('**/spog', { timeout: 20000 });
    expect(page.url()).toContain('/spog');
    await expect(page).toHaveTitle(/MonMon/);

    // 4. Make sure numbers appear on the screen
    console.log('Step 4: Verifying numbers appear on SPOG...');
    // Verify that primary financial figures (e.g. £ amounts, max/day, savings pot) are rendered
    const currencyValues = page.locator('text=/£\\s*[0-9]+(?:\\.[0-9]{2})?/');
    await expect(currencyValues.first()).toBeVisible({ timeout: 10000 });
    const count = await currencyValues.count();
    expect(count).toBeGreaterThan(0);
    console.log(`Found ${count} currency metric elements on SPOG`);

    // 5. Navigate to /myAccounts and add an account
    console.log('Step 5: Adding a new account...');
    await page.goto(`${targetBaseUrl}/myAccounts`, { waitUntil: 'networkidle' });
    await expect(page).toHaveTitle(/MonMon Accounts/);

    const uniqueAccountName = 'Smoke Vault ' + Date.now();
    await page.locator('button[onclick="openAccountModal()"]').click();
    await expect(page.locator('#account-modal')).toBeVisible();

    await page.fill('#acc-name', uniqueAccountName);
    await page.selectOption('#type', 'SHORT_TERM_SAVINGS');
    await page.fill('#availableLimit', '750.00');

    await Promise.all([
      page.waitForNavigation(),
      page.locator('#account-modal-form button[type="submit"]').click()
    ]);

    // Verify account is present in list
    await expect(page.locator('body')).toContainText(uniqueAccountName);
    console.log(`Created account verified: ${uniqueAccountName}`);

    // 6. Navigate to /myBalances and add a balance for that account
    console.log('Step 6: Recording balance for new account...');
    await page.goto(`${targetBaseUrl}/myBalances`, { waitUntil: 'networkidle' });
    await expect(page.locator('#record-balance-form')).toBeVisible();

    await page.fill('#balance-value', '425.50');
    // Select the newly created account by label
    await page.selectOption('#account', { label: `${uniqueAccountName} (SHORT_TERM_SAVINGS)` });

    await Promise.all([
      page.waitForNavigation(),
      page.locator('#record-balance-form button[type="submit"]').click()
    ]);
    console.log('Balance successfully recorded');

    // 7. Delete that account on /myAccounts
    console.log('Step 7: Deleting the account...');
    await page.goto(`${targetBaseUrl}/myAccounts`, { waitUntil: 'networkidle' });
    await expect(page.locator('body')).toContainText(uniqueAccountName);

    // Accept confirmation dialog if present
    page.on('dialog', dialog => dialog.accept());

    // Locate the row/card containing the account and submit delete
    const accountRowOrCard = page.locator('tr, .item-card', { hasText: uniqueAccountName }).first();
    const deleteButton = accountRowOrCard.locator('form[action*="/binAccount"] button, .btn-delete').first();
    await deleteButton.click();

    // Verify the account is gone from /myAccounts
    await expect(page.locator(`text=${uniqueAccountName}`)).not.toBeVisible({ timeout: 10000 });
    console.log('Account deletion verified cleanly!');
  });

});
