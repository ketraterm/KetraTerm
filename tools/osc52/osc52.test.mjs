/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';
import { probe } from './osc52.mjs';

const ESC = '\x1b';
const ST = `${ESC}\\`;

function terminal(respond) {
  const input = new EventEmitter();
  const output = new EventEmitter();
  input.isTTY = output.isTTY = true;
  input.isRaw = false;
  input.setRawMode = raw => { input.isRaw = raw; };
  input.resume = () => { input.paused = false; };
  input.pause = () => { input.paused = true; };
  const requests = [];
  output.write = (request, encoding, callback) => {
    assert.equal(encoding, 'ascii');
    requests.push(request);
    queueMicrotask(() => {
      if (request === `${ESC}[5n`) input.emit('data', Buffer.from(`${ESC}[0n`));
      else respond(input, request, callback);
    });
    return true;
  };
  return { input, output, requests };
}

function assertRestored(t, raw = false) {
  assert.equal(t.input.isRaw, raw);
  assert.equal(t.input.paused, true);
  assert.deepEqual(t.input.eventNames(), []);
  assert.deepEqual(t.output.eventNames(), []);
}

test('suite checks exact UTF-8 bytes, normalized selectors, BEL/ST and every split', async () => {
  const expected = Buffer.from('e\u0301\u00e9\ud83d\ude42\r\n\x00\x1b');
  const reply = `${ESC}]52;c;${expected.toString('base64')}${ST}`;
  for (let split = 0; split <= reply.length; split++) {
    const t = terminal(input => {
      input.emit('data', Buffer.from(reply.slice(0, split)));
      input.emit('data', Buffer.from(reply.slice(split)));
    });
    // Empty chunks are legal stream input and must not become unsolicited replies.
    const results = await probe({ ...t, expected, terminators: ['bel', 'st'] });
    assert.equal(results.length, 2);
    assert.deepEqual(t.requests, [`${ESC}[5n`, `${ESC}]52;c;?\x07`, `${ESC}[5n`, `${ESC}]52;c;?${ST}`, `${ESC}[5n`]);
    assertRestored(t);
  }
  const t = terminal((input, request) => {
    const selection = request.split(';')[1];
    const normalized = [...new Set(selection || 'c')].join('');
    for (const byte of Buffer.from(`${ESC}]52;${normalized};${expected.toString('base64')}${ST}`)) {
      input.emit('data', Buffer.from([byte]));
    }
  });
  const results = await probe({ ...t, expected, selections: ['c', 's', '', 'ccsc', 'pc', 'q01234567'], terminators: ['bel', 'st'] });
  assert.equal(results.length, 12);
  assertRestored(t);
});

test('empty response is a complete frame, and an existing raw mode is preserved', async () => {
  const t = terminal(input => input.emit('data', Buffer.from(`${ESC}]52;c;${ST}`)));
  t.input.isRaw = true;
  assert.equal((await probe({ ...t, expected: Buffer.alloc(0) }))[0].bytes, 0);
  assertRestored(t, true);
});

test('wrong content, selectors, padding, and duplicate frames fail without exposing content', async () => {
  for (const reply of [
    `${ESC}]52;c;c2VjcmV0${ST}`,
    `${ESC}]52;p;YQ==${ST}`,
    `${ESC}]52;c;YQ${ST}`,
    `${ESC}]52;c;YQ==${ST}${ESC}]52;c;YQ==${ST}`,
  ]) {
    const t = terminal(input => input.emit('data', Buffer.from(reply)));
    await assert.rejects(probe({ ...t, expected: Buffer.from('a') }), error => {
      assert.match(error.message, /Unexpected response bytes/);
      assert.doesNotMatch(error.message, /secret|c2VjcmV0|YQ/);
      return true;
    });
    assert.equal(t.requests.length, 2);
    assertRestored(t);
  }
});

test('extra frame in a separate chunk cannot be lost between exchanges', async () => {
  const t = terminal(input => {
    input.emit('data', Buffer.from(`${ESC}]52;c;${ST}`));
    input.emit('data', Buffer.from(`${ESC}]52;c;${ST}`));
  });
  await assert.rejects(probe({ ...t, expected: Buffer.alloc(0) }), /Unexpected input/);
  assertRestored(t);
});

test('silence and partial replies time out using a controlled clock, without retry', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  for (const prefix of ['', `${ESC}]52;c;`, `${ESC}]52;c;YQ==\x07`]) {
    let entered;
    const requestEntered = new Promise(resolve => { entered = resolve; });
    const t = terminal(input => {
      if (prefix) input.emit('data', Buffer.from(prefix));
      entered();
    });
    const pending = probe({ ...t, expected: Buffer.from('a'), timeoutMs: 10000 });
    const rejected = assert.rejects(pending, /timed out|Unexpected response/);
    await requestEntered;
    context.mock.timers.tick(10000);
    await rejected;
    assert.equal(t.requests.length, 2);
    assertRestored(t);
  }
});

test('interrupt, EOF and write failure restore raw mode and remove listeners', async () => {
  for (const reason of ['interrupt', 'end', 'write']) {
    const controller = new AbortController();
    const t = terminal((input, request, callback) => {
      if (reason === 'interrupt') controller.abort();
      if (reason === 'end') input.emit('end');
      if (reason === 'write') callback(new Error('do not leak provider data'));
    });
    await assert.rejects(probe({ ...t, expected: Buffer.alloc(0), signal: controller.signal }), /interrupted|closed|I\/O failed/);
    assertRestored(t);
  }
});

test('rejects non-terminal, invalid UTF-8, excessive text, selectors and timeout before queries', async () => {
  for (const options of [
    { expected: Buffer.from([0xff]) },
    { expected: Buffer.alloc(1024 * 1024 + 1) },
    { selections: ['c\x1b'] },
    { timeoutMs: NaN },
    { timeoutMs: 0 },
    { terminators: ['other'] },
  ]) {
    const t = terminal(() => assert.fail('unexpected query'));
    await assert.rejects(probe({ ...t, expected: Buffer.alloc(0), ...options }));
    assert.deepEqual(t.requests, []);
    assert.equal(t.input.isRaw, false);
  }
  const t = terminal(() => assert.fail('unexpected query'));
  t.input.isTTY = false;
  await assert.rejects(probe({ ...t, expected: Buffer.alloc(0) }), /without pipes/);
});
