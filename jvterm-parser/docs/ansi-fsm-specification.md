# ANSI Finite-State Machine & CSI Dispatch Specification

The `jvterm-parser` module implements a high-performance, table-driven ANSI escape-sequence state machine that operates in $O(1)$ state transition time with zero runtime allocations.

---

## 1. The State Machine Architecture

The state transitions and actions are modeled using standard terminal FSM specifications (inspired by Paul Williams' ANSI parser state machine).

```
         Raw Byte Stream
               │
               ▼
      [ByteClass.classify]
               │ (ByteClass ID)
               ▼
     [AnsiStateMachine]
    (Transition Matrix Lookup)
               │ (FsmAction & NextState)
               ▼
        [ActionEngine]
      (Mutates ParserState)
               │
               ├─► Printable character ──► PrintableProcessor
               └─► Escape Sequence      ──► CommandDispatcher
```

### Components:
1. **`ByteClass`**: Maps raw input bytes (`0x00..0xFF`) into 16 lexical classes (such as `C0_CONTROL`, `DIGIT`, `PARAMETER`, `INTERMEDIATE`, `FINAL`, etc.).
2. **`AnsiStateMachine`**: A static, flat `IntArray` transition matrix. For any `(currentState, byteClass)` pair, a single array lookup determines the next state and the FSM action to execute.
3. **`ActionEngine`**: Executes FSM actions (e.g. `clear`, `collect`, `param`, `execute`, `dispatch`, etc.) to mutate parameter buffers or trigger string capture for OSC/DCS modes.

---

## 2. CSI Command Signatures

CSI sequences can contain private-mode prefix characters (like `?`, `>`), multiple intermediate characters (like `$`, `"`, `'`), and a final character (like `m`, `h`, `l`). 

To avoid string parsing and dynamic map lookups during dispatch, the parser packs a sequence's structural characteristics into a single 64-bit `Long` signature key:

```
+-------------------------------------------------------+
| Bit Range   | Usage                                   |
+-------------+-----------------------------------------+
| bits 0..7   | Final Character Byte                    |
| bits 8..15  | Prefix character (or 0 if none)         |
| bits 16..23 | First intermediate character            |
| bits 24..31 | Second intermediate character           |
| bits 32..63 | Reserved (currently zero)               |
+-------------------------------------------------------+
```

This allows the parser to represent commands like `CSI ? Pn h` (DECSET) and `CSI > Pn m` as unique primitive `Long` numbers.

---

## 3. High-Speed Dispatch Table

CSI command lookup is handled by the `GeneratedCsiDispatchTable`:
* All supported command signatures are placed in a sorted primitive `LongArray`.
* The `CommandDispatcher` maps the packed signature to this array using a high-speed **binary search** ($O(\log N)$).
* Upon match, it retrieves the mapped command index and invokes the corresponding callback on `TerminalCommandSink` with pre-parsed integer parameters.

---

## 4. Parser Recovery and Security Safeguards

* **Abort Sequences**: If a `CAN` (`0x18`) or `SUB` (`0x1A`) byte is received, the FSM immediately aborts the current sequence and transitions back to the `GROUND` state, preventing sequence poisoning.
* **Payload Clamping**: OSC and DCS payloads are stored in a pre-allocated buffer with a strict size ceiling (default `4096` bytes). If a payload exceeds this limit, the parser sets an overflow flag and discards subsequent bytes, neutralizing denial-of-service memory exploitation.
