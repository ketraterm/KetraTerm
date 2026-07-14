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
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import test from 'node:test';

const oraclePath = fileURLToPath(new URL('../oracle.mjs', import.meta.url));

function runOracle(request) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [oraclePath], { stdio: ['pipe', 'pipe', 'pipe'] });
    const output = [];
    const errors = [];
    child.stdout.on('data', (chunk) => output.push(chunk));
    child.stderr.on('data', (chunk) => errors.push(chunk));
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`oracle exited ${code}: ${Buffer.concat(errors).toString('utf8')}`));
        return;
      }
      resolve(JSON.parse(Buffer.concat(output).toString('utf8')));
    });
    child.stdin.end(JSON.stringify(request));
  });
}

function request(events) {
  return { protocolVersion: 1, columns: 5, rows: 2, maxHistory: 4, events };
}

test('captures retained grid cursor modes title and response bytes', async () => {
  const result = await runOracle(request([
    { type: 'input', hex: Buffer.from('A中\x1b[?1h\x1b]2;title\x07\x1b[6n').toString('hex') },
    { type: 'endOfInput' },
  ]));

  assert.deepEqual(result.oracle, { name: 'xterm.js', version: '6.0.0' });
  assert.equal(result.retainedRows[0].cells[0].text, 'A');
  assert.equal(result.retainedRows[0].cells[1].text, '中');
  assert.equal(result.retainedRows[0].cells[1].width, 2);
  assert.equal(result.retainedRows[0].cells[2].width, 0);
  assert.deepEqual(result.retainedRows[0].cells[0].foreground, { kind: 'default', value: 0 });
  assert.deepEqual(result.retainedRows[0].cells[0].background, { kind: 'default', value: 0 });
  assert.deepEqual(result.cursor, { column: 3, row: 0 });
  assert.equal(result.modes.applicationCursorKeys, true);
  assert.equal(result.windowTitle, 'title');
  assert.equal(Buffer.from(result.outboundHex, 'hex').toString('utf8'), '\x1b[1;4R');
});

test('preserves parser behavior across bytewise writes', async () => {
  const bytes = Buffer.from('é\x1b[31mX');
  const single = await runOracle(request([{ type: 'input', hex: bytes.toString('hex') }]));
  const bytewise = await runOracle(request(Array.from(bytes, (byte) => ({
    type: 'input',
    hex: byte.toString(16).padStart(2, '0'),
  }))));

  assert.deepEqual(bytewise, single);
});

test('rejects malformed input instead of guessing', async () => {
  await assert.rejects(
    runOracle(request([{ type: 'input', hex: 'not-hex' }])),
    /input hex must contain an even number of hexadecimal digits/,
  );
});
