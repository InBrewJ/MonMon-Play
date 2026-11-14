// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const LEGMON_URL = process.env.LEGMON_URL || 'http://127.0.0.1:9005';

describe('LegMon HTTP & REST Contract Tests (:9005)', () => {
  it('GET /health returns 200 OK and expected JSON contract', async () => {
    const res = await fetch(`${LEGMON_URL}/health`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /application\/json/);

    const body = await res.json();
    assert.equal(body.status, 'ok');
    assert.equal(body.service, 'LegMon');
  });

  it('GET /status returns 200 OK and full diagnostic contract', async () => {
    const res = await fetch(`${LEGMON_URL}/status`);
    assert.equal(res.status, 200);

    const body = await res.json();
    assert.equal(body.service, 'LegMon');
    assert.ok(['ok', 'warning'].includes(body.status));
    assert.equal(typeof body.hasError, 'boolean');
    assert.equal(typeof body.errorCount, 'number');
    assert.equal(typeof body.uptimeSeconds, 'string');
    assert.equal(typeof body.primaryPipeline, 'string');
    assert.ok(Array.isArray(body.ocrTools));
    assert.ok(body.ocrTools.includes('pdftotext'));
  });

  it('GET /api/logs returns 200 OK with log buffer array', async () => {
    const res = await fetch(`${LEGMON_URL}/api/logs`);
    assert.equal(res.status, 200);

    const body = await res.json();
    assert.equal(body.service, 'LegMon');
    assert.equal(typeof body.hasError, 'boolean');
    assert.equal(typeof body.errorCount, 'number');
    assert.ok(Array.isArray(body.logs));
    if (body.logs.length > 0) {
      assert.ok(body.logs[0].time);
      assert.ok(body.logs[0].level);
      assert.ok(body.logs[0].msg);
    }
  });

  it('POST /api/logs/clear clears error state', async () => {
    const res = await fetch(`${LEGMON_URL}/api/logs/clear`, { method: 'POST' });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'cleared');
  });

  it('POST /api/process rejects GET with 405 Method Not Allowed', async () => {
    const res = await fetch(`${LEGMON_URL}/api/process`, { method: 'GET' });
    assert.equal(res.status, 405);
  });

  it('POST /api/process rejects empty / invalid body with 400 Bad Request', async () => {
    const res = await fetch(`${LEGMON_URL}/api/process`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/pdf' },
      body: Buffer.from('too-small'),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.ok(body.error);
  });

  it('POST /api/process accepts valid PDF fixture and returns 202 Accepted with jobId', async () => {
    const pdfPath = path.resolve('legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf');
    if (!fs.existsSync(pdfPath)) {
      console.warn(`PDF fixture ${pdfPath} not found, skipping upload test`);
      return;
    }

    const pdfBuffer = fs.readFileSync(pdfPath);
    const res = await fetch(`${LEGMON_URL}/api/process?userId=contract-test-user&callbackUrl=http://127.0.0.1:9000/api/statement/webhook`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/pdf' },
      body: pdfBuffer,
    });

    assert.equal(res.status, 202);
    const body = await res.json();
    assert.equal(body.status, 'processing');
    assert.ok(body.jobId);
    assert.ok(body.jobId.startsWith('stmt_'));
  });
});
