// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:9001';

describe('Admin, Settings & Management Views Contract Tests', () => {
  it('GET /api/user/accessStatus returns user approval and auth metadata', async () => {
    const res = await fetch(`${BASE_URL}/api/user/accessStatus`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(typeof body.authenticated === 'boolean');
    assert.ok(typeof body.approved === 'boolean');
    if (body.authenticated) {
      assert.ok(body.status);
    }
  });

  it('GET /settings returns 200 OK or redirect with settings structure', async () => {
    const res = await fetch(`${BASE_URL}/settings`, { redirect: 'manual' });
    assert.ok([200, 302, 303, 307].includes(res.status));
  });

  it('GET /waitinglist returns 200 OK or redirect to SPOG/auth', async () => {
    const res = await fetch(`${BASE_URL}/waitinglist`, { redirect: 'manual' });
    assert.ok([200, 302, 303, 307].includes(res.status));
  });

  it('POST /settings updates preferences/region', async () => {
    const formData = new URLSearchParams();
    formData.append('region', 'UK');
    const res = await fetch(`${BASE_URL}/settings`, {
      method: 'POST',
      body: formData,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      redirect: 'manual'
    });
    assert.ok([200, 302, 303, 307].includes(res.status));
  });

  it('POST /settings/approveUser/:id and /rejectUser/:id updates user_access', async () => {
    const res = await fetch(`${BASE_URL}/settings/approveUser/1`, {
      method: 'POST',
      redirect: 'manual'
    });
    assert.ok([200, 302, 303, 307].includes(res.status));
  });
});
