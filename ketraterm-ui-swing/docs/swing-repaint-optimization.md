# Swing Repaint Optimization & Selection Layout

The `jvterm-ui-swing` module utilizes structured repaint planning and precise cell coordinate mappings to achieve high-frequency rendering updates with minimal CPU footprint.

---

## 1. Minimal Repaint Planning (`SwingRepaintPlanner`)

Repainting the entire Swing component on every terminal update is highly inefficient. The [SwingRepaintPlanner](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-ui-swing/src/main/kotlin/io/github/jvterm/ui/swing/viewport/SwingRepaintPlanner.kt) maps changes in the `TerminalRenderCache` to schedule targeted repaint requests:

* **Cursor-Only Repaints**: When only the cursor moves, the planner computes the pixel bounding box of both the old cursor cell and the new cursor cell. Only these two small rectangular areas are repainted, avoiding full-screen refreshes.
* **Row-Level Repaints**: The planner scans the `lineGenerations` array. Rows whose generation number matches the previous frame are skipped. Only modified rows are invalidated and repainted.
* **Full Repaint triggers**: A full component repaint is requested only during structural changes (e.g. resizes, buffer swaps, scroll offsets, or column size changes).

---

## 2. Selection Drag Matrix & Text Extraction

Selection handles selection sweeps, word-level highlights, and block selections:

* **Sweep Selection**: Tracks mouse drag coordinates, mapping the screen pixels to physical grid cell rows and columns.
* **Smart Word & Path Expansion**:
  * **Standard Words**: Double-clicking a cell expands the selection left and right to contiguous letters, numbers, and underscores.
  * **Paths / URIs**: If the clicked sequence contains directory slash markers (`/`, `\`), dot indicators (`.`), or colon signs (`:`), the text extractor expands the selection across path-safe characters, allowing users to easily select full file paths or URLs.

---

## 3. Clipboards & Key Mapping Services

* **Clipboard Handlers**: Integrates with standard OS clipboards using [SwingHostServices](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-ui-swing/src/main/kotlin/io/github/jvterm/ui/swing/api/SwingHostServices.kt), sanitizing carriage returns during copy/paste based on policy options.
* **Focus Mapping**: Converts window focus gain/loss into `TerminalFocusEvent` events, routing them to the active session to trigger bracketed focus reports (`CSI I` and `CSI O`).
