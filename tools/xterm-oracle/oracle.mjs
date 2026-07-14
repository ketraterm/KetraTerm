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

import {readFile} from 'node:fs/promises';
import {stdin, stdout} from 'node:process';
import xtermHeadless from '@xterm/headless';

const { Terminal } = xtermHeadless;

const PROTOCOL_VERSION = 1;
const packageMetadata = JSON.parse(
  await readFile(new URL('./node_modules/@xterm/headless/package.json', import.meta.url), 'utf8'),
);

function fail(message) {
  throw new Error(`invalid oracle request: ${message}`);
}

function requirePositiveInteger(value, name) {
  if (!Number.isInteger(value) || value <= 0) fail(`${name} must be a positive integer`);
  return value;
}

function requireNonNegativeInteger(value, name) {
  if (!Number.isInteger(value) || value < 0) fail(`${name} must be a non-negative integer`);
  return value;
}

function decodeHex(value) {
  if (typeof value !== 'string' || value.length % 2 !== 0 || !/^[0-9A-Fa-f]*$/.test(value)) {
    fail('input hex must contain an even number of hexadecimal digits');
  }
  return Uint8Array.from(Buffer.from(value, 'hex'));
}

function writeAsync(terminal, bytes) {
  return new Promise((resolve) => terminal.write(bytes, resolve));
}

function cellSnapshot(cell) {
  const foregroundKind = cell.isFgRGB() ? 'rgb' : cell.isFgPalette() ? 'palette' : 'default';
  const backgroundKind = cell.isBgRGB() ? 'rgb' : cell.isBgPalette() ? 'palette' : 'default';
  return {
    text: cell.getChars(),
    width: cell.getWidth(),
    foreground: {
      kind: foregroundKind,
      value: foregroundKind === 'default' ? 0 : cell.getFgColor(),
    },
    background: {
      kind: backgroundKind,
      value: backgroundKind === 'default' ? 0 : cell.getBgColor(),
    },
    bold: cell.isBold() !== 0,
    italic: cell.isItalic() !== 0,
    dim: cell.isDim() !== 0,
    underline: cell.isUnderline() !== 0,
    blink: cell.isBlink() !== 0,
    inverse: cell.isInverse() !== 0,
    invisible: cell.isInvisible() !== 0,
    strikethrough: cell.isStrikethrough() !== 0,
    overline: cell.isOverline() !== 0,
  };
}

function snapshot(terminal, title, outboundChunks) {
  const buffer = terminal.buffer.active;
  const rows = [];
  const reusableCell = buffer.getNullCell();
  for (let rowIndex = 0; rowIndex < buffer.length; rowIndex++) {
    const line = buffer.getLine(rowIndex);
    if (line === undefined) throw new Error(`xterm.js omitted retained row ${rowIndex}`);
    const cells = [];
    for (let column = 0; column < terminal.cols; column++) {
      const cell = line.getCell(column, reusableCell);
      if (cell === undefined) throw new Error(`xterm.js omitted cell row=${rowIndex} column=${column}`);
      cells.push(cellSnapshot(cell));
    }
    const nextLine = rowIndex + 1 < buffer.length ? buffer.getLine(rowIndex + 1) : undefined;
    rows.push({ wrapsToNext: nextLine?.isWrapped === true, cells });
  }

  return {
    protocolVersion: PROTOCOL_VERSION,
    oracle: { name: 'xterm.js', version: packageMetadata.version },
    columns: terminal.cols,
    visibleRows: terminal.rows,
    historyRows: buffer.type === 'normal' ? buffer.baseY : 0,
    liveRowStart: buffer.type === 'normal' ? buffer.baseY : 0,
    activeBuffer: buffer.type,
    cursor: { column: buffer.cursorX, row: buffer.cursorY },
    modes: {
      insertMode: terminal.modes.insertMode,
      autoWrap: terminal.modes.wraparoundMode,
      applicationCursorKeys: terminal.modes.applicationCursorKeysMode,
      applicationKeypad: terminal.modes.applicationKeypadMode,
      originMode: terminal.modes.originMode,
      bracketedPaste: terminal.modes.bracketedPasteMode,
      focusReporting: terminal.modes.sendFocusMode,
      mouseTrackingMode: terminal.modes.mouseTrackingMode,
      synchronizedOutput: terminal.modes.synchronizedOutputMode,
    },
    retainedRows: rows,
    windowTitle: title,
    outboundHex: Buffer.concat(outboundChunks).toString('hex').toUpperCase(),
  };
}

async function main() {
  const chunks = [];
  for await (const chunk of stdin) chunks.push(chunk);
  const request = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (request.protocolVersion !== PROTOCOL_VERSION) fail(`protocolVersion must be ${PROTOCOL_VERSION}`);

  const columns = requirePositiveInteger(request.columns, 'columns');
  const rows = requirePositiveInteger(request.rows, 'rows');
  const maxHistory = requireNonNegativeInteger(request.maxHistory, 'maxHistory');
  if (!Array.isArray(request.events)) fail('events must be an array');

  const terminal = new Terminal({
    cols: columns,
    rows,
    scrollback: maxHistory,
    allowProposedApi: true,
  });
  let title = '';
  const outboundChunks = [];
  terminal.onTitleChange((value) => { title = value; });
  terminal.onData((value) => { outboundChunks.push(Buffer.from(value, 'utf8')); });

  try {
    for (const event of request.events) {
      switch (event?.type) {
        case 'input':
          await writeAsync(terminal, decodeHex(event.hex));
          break;
        case 'resize':
          terminal.resize(
            requirePositiveInteger(event.columns, 'resize columns'),
            requirePositiveInteger(event.rows, 'resize rows'),
          );
          break;
        case 'endOfInput':
          break;
        default:
          fail(`unsupported event type ${JSON.stringify(event?.type)}`);
      }
    }
    stdout.write(`${JSON.stringify(snapshot(terminal, title, outboundChunks))}\n`);
  } finally {
    terminal.dispose();
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error.message}\n`);
  process.exitCode = 1;
});
