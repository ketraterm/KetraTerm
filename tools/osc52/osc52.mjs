#!/usr/bin/env node
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
import {open} from 'node:fs/promises';
import {Buffer} from 'node:buffer';
import process from 'node:process';
import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';
import {parseArgs, TextDecoder} from 'node:util';

const ESC = '\x1b';
const ST = `${ESC}\\`;
const MAX_TEXT_BYTES = 1024 * 1024;

/**
 * Exact, bounded exchanges on a terminal. No clipboard contents enter diagnostics.
 * @param {{input: import('node:tty').ReadStream, output: import('node:tty').WriteStream,
 * expected: Buffer, selections?: string[], terminators?: string[], timeoutMs?: number,
 * signal?: AbortSignal}} options
 */
export async function probe({ input, output, expected, selections = ['c'], terminators = ['bel'], timeoutMs = 10000, signal }) {
  if (!input.isTTY || !output.isTTY) throw new Error('Run inside the terminal under test, without pipes or redirection.');
  if (!Buffer.isBuffer(expected) || expected.length > MAX_TEXT_BYTES) throw new Error('Expected text must fit the 1 MiB test limit.');
  new TextDecoder('utf-8', { fatal: true }).decode(expected);
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000) throw new Error('Timeout must be 1..30000 ms.');
  if (!selections.length || selections.some(s => !/^[cpqs0-7]{0,16}$/.test(s))) throw new Error('Invalid selection list.');
  if (!terminators.length || terminators.some(t => !['bel', 'st'].includes(t))) throw new Error('Invalid request terminator.');

  const wasRaw = input.isRaw;
  let pending;
  let failure;
  const fail = error => {
    failure ??= error;
    if (pending) {
      clearTimeout(pending.timer);
      pending.reject(failure);
      pending = undefined;
    }
  };
  const onData = chunk => {
    if (failure || chunk.length === 0) return;
    if (!pending) return fail(new Error('Unexpected input between responses.'));
    const remaining = pending.bytes.length - pending.offset;
    if (chunk.length > remaining || !chunk.equals(pending.bytes.subarray(pending.offset, pending.offset + chunk.length))) {
      return fail(new Error(`Unexpected response bytes at offset ${pending.offset}; contents withheld. Do not type during a check.`));
    }
    pending.offset += chunk.length;
    if (pending.offset === pending.bytes.length) {
      clearTimeout(pending.timer);
      const { resolve } = pending;
      pending = undefined;
      resolve();
    }
  };
  const onEnd = () => fail(new Error('Terminal input closed before completion.'));
  const onError = () => fail(new Error('Terminal I/O failed.'));
  const onAbort = () => fail(new Error('Check interrupted.'));
  const exchange = (request, reply) => new Promise((resolve, reject) => {
    if (failure) return reject(failure);
    pending = {
      bytes: Buffer.from(reply, 'ascii'), offset: 0, resolve, reject,
      timer: setTimeout(() => fail(new Error('Response timed out or was incomplete. No retry was sent.')), timeoutMs),
    };
    try {
      output.write(request, 'ascii', error => { if (error) onError(); });
    } catch {
      onError();
    }
  });

  const results = [];
  try {
    input.setRawMode(true);
    input.on('data', onData);
    input.on('end', onEnd);
    input.on('error', onError);
    output.on('error', onError);
    signal?.addEventListener('abort', onAbort);
    if (signal?.aborted) onAbort();
    input.resume();
    // Proves the return path works before interpreting an empty clipboard reply.
    await exchange(`${ESC}[5n`, `${ESC}[0n`);
    const base64 = expected.toString('base64');
    for (const selection of selections) {
      for (const terminator of terminators) {
        const normalized = [...new Set(selection || 'c')].join('');
        const start = performance.now();
        await exchange(`${ESC}]52;${selection};?${terminator === 'bel' ? '\x07' : ST}`, `${ESC}]52;${normalized};${base64}${ST}`);
        const elapsedMs = Math.round(performance.now() - start);
        // Catch extra bytes already emitted and re-establish framing before the next query.
        await exchange(`${ESC}[5n`, `${ESC}[0n`);
        results.push({ selection, terminator, bytes: expected.length, elapsedMs });
      }
    }
    if (failure) throw failure;
    return results;
  } finally {
    if (pending) clearTimeout(pending.timer);
    signal?.removeEventListener('abort', onAbort);
    input.pause();
    input.off('data', onData);
    input.off('end', onEnd);
    input.off('error', onError);
    output.off('error', onError);
    input.setRawMode(wasRaw);
  }
}

async function main() {
  const { values } = parseArgs({ options: {
    'expect-text': { type: 'string' }, 'expect-file': { type: 'string' },
    'expect-empty': { type: 'boolean' }, suite: { type: 'boolean' },
    selection: { type: 'string' }, terminator: { type: 'string' },
    'timeout-ms': { type: 'string', default: '10000' }, help: { type: 'boolean' },
  } });
  if (values.help) {
    console.log('OSC 52 exact-reply check (Node 24.2+). Does not write the clipboard.\n' +
      'node tools/osc52/osc52.mjs (--expect-text TEXT | --expect-file UTF8_FILE | --expect-empty)\n' +
      '  [--suite | --selection c --terminator bel] [--timeout-ms 10000]\n' +
      'Suite: c, s, omitted, and duplicate selectors, each with BEL and ST requests.\n' +
      'Keep stdin/stdout attached to the terminal. Ctrl+C aborts. See docs/osc52-testing.md.');
    return;
  }
  if ([values['expect-text'] !== undefined, values['expect-file'] !== undefined, values['expect-empty'] === true].filter(Boolean).length !== 1) {
    throw new Error('Choose exactly one of --expect-text, --expect-file, or --expect-empty.');
  }
  if (values.suite && (values.selection !== undefined || values.terminator !== undefined)) {
    throw new Error('--suite cannot be combined with --selection or --terminator.');
  }
  let expected = Buffer.from(values['expect-text'] ?? '', 'utf8');
  if (values['expect-file'] !== undefined) {
    const file = await open(values['expect-file'], 'r');
    try {
      // Read at most one byte beyond the ceiling, including if the file grows.
      const bytes = Buffer.alloc(MAX_TEXT_BYTES + 1);
      let size = 0;
      while (size < bytes.length) {
        const { bytesRead } = await file.read(bytes, size, bytes.length - size);
        if (!bytesRead) break;
        size += bytesRead;
      }
      expected = bytes.subarray(0, size);
    } finally {
      await file.close();
    }
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  process.on('SIGINT', abort);
  process.on('SIGTERM', abort);
  try {
    console.error('OSC 52 check: keep the clipboard unchanged; use the consent buttons if prompted. Do not type.');
    const results = await probe({
      input: process.stdin, output: process.stdout, expected,
      selections: values.suite ? ['c', 's', '', 'ccsc'] : [values.selection ?? 'c'],
      terminators: values.suite ? ['bel', 'st'] : [values.terminator ?? 'bel'],
      timeoutMs: Number(values['timeout-ms']), signal: controller.signal,
    });
    for (const r of results) console.log(`PASS selection=${r.selection || '(omitted)'} terminator=${r.terminator} bytes=${r.bytes} elapsedMs=${r.elapsedMs}`);
    console.log(`PASS ${results.length} clipboard checks; Node ${process.version}; ${process.platform}/${process.arch}`);
  } finally {
    process.off('SIGINT', abort);
    process.off('SIGTERM', abort);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    console.error(`FAIL: ${error.message}`);
    process.exitCode = 1;
  });
}
