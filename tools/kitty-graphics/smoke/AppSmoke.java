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
import io.github.ketraterm.core.TerminalBuffers;
import io.github.ketraterm.render.api.TerminalRenderImage;
import io.github.ketraterm.render.api.TerminalRenderCellFlags;
import io.github.ketraterm.session.TerminalInputAdmission;
import io.github.ketraterm.session.TerminalSession;
import io.github.ketraterm.transport.TerminalConnector;
import io.github.ketraterm.transport.TerminalConnectorListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-application smoke; runs against public APIs, outside the deterministic test suite. */
public final class AppSmoke {
    private static final int COLUMNS = 100, ROWS = 30;
    private static final int CELL_WIDTH = 8, CELL_HEIGHT = 20;
    private static final int IMAGE_WIDTH = 160, IMAGE_HEIGHT = 80;

    /**
     * Runs the selected application through the supplied PTY relay command.
     * @param args application name, output directory, and relay command with its arguments.
     * @throws Exception if launch, transport, semantic assertions, or teardown fails.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[0].equals("yazi") || args[0].equals("kitten"))) {
            throw new IllegalArgumentException("Usage: AppSmoke yazi|kitten OUTPUT_DIR RELAY_COMMAND [ARG...]");
        }
        String app = args[0];
        Path output = Files.createDirectories(Path.of(args[1]));
        var terminal = TerminalBuffers.create(COLUMNS, ROWS);
        terminal.resize(COLUMNS, ROWS, 0, COLUMNS * CELL_WIDTH, ROWS * CELL_HEIGHT);
        var connector = new Connector(app, output, Arrays.copyOfRange(args, 2, args.length));
        try (connector; var session = TerminalSession.create(terminal, connector)) {
            connector.session = session;
            session.start(COLUMNS, ROWS);
            connector.await(connector.image, "complete image placement with correct pixel extent");
            TerminalRenderImage image = connector.resource;
            int[] pixels = new int[IMAGE_WIDTH * IMAGE_HEIGHT];
            image.copyArgb(pixels, 0);
            for (int pixel : pixels) {
                if (pixel != 0xffff0000) throw new AssertionError("Unexpected pixel " + Integer.toHexString(pixel));
            }
            System.out.printf("%s: image=%dx%d displayed=%dx%d pixels observed placements=%d exact red pixels=%d%n",
                app, image.getWidth(), image.getHeight(), IMAGE_WIDTH, IMAGE_HEIGHT,
                connector.maximumPlacements, pixels.length);
            if (app.equals("yazi")) {
                connector.awaitingRemoval.set(true);
                submit(session, "j");
                connector.await(connector.removed, "image removal after navigating to text");
                submit(session, "q");
            }
            connector.await(connector.closed, "application exit");
            if (connector.exitCode != 0) throw new AssertionError("Application exit code " + connector.exitCode);
        }
        // Session cleanup can consume connector failures; check after resources close.
        connector.await(connector.cleanupDone, "connector cleanup");
        System.out.println(app + ": clean exit" + (app.equals("yazi") ? "; navigation removed placements" : ""));
    }

    private static void submit(TerminalSession session, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        if (session.submitBytes(bytes, 0, bytes.length) != TerminalInputAdmission.ACCEPTED) {
            throw new AssertionError("Session rejected smoke input");
        }
    }

    private static final class Connector implements TerminalConnector {
        private final Process process;
        private final Path capture;
        private final OutputStream replies;
        private final CountDownLatch image = new CountDownLatch(1);
        private final CountDownLatch removed = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch cleanupDone = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean awaitingRemoval = new AtomicBoolean();
        private final AtomicBoolean closing = new AtomicBoolean();
        private TerminalSession session;
        private volatile TerminalRenderImage resource;
        private volatile int maximumPlacements;
        private volatile int exitCode = -1;
        private int inputBytes;

        private Connector(String app, Path output, String[] command) throws IOException {
            capture = output.resolve(app + "-output.bin");
            replies = Files.newOutputStream(output.resolve(app + "-input.bin"));
            try {
                process = new ProcessBuilder(command).redirectError(output.resolve(app + "-stderr.log").toFile()).start();
            } catch (IOException failure) {
                try { replies.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public void start(TerminalConnectorListener listener) {
            Thread.ofPlatform().daemon().name("graphics-smoke-ingress").start(() -> {
                try (var recording = Files.newOutputStream(capture); var input = process.getInputStream()) {
                    byte[] bytes = new byte[65536];
                    int length;
                    int outputBytes = 0;
                    while ((length = input.read(bytes)) >= 0) {
                        outputBytes += length;
                        if (outputBytes > 8 * 1024 * 1024) throw new IOException("Smoke output exceeded 8 MiB");
                        recording.write(bytes, 0, length);
                        listener.onBytes(bytes, 0, length);
                        int[] count = {0};
                        int[] coveredPixels = {0};
                        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0};
                        boolean[] textPreview = {false};
                        session.readRenderFrame(frame -> {
                            frame.copyImages((r, x, y, w, h, sx, sy, sw, sh, top, bottom, z) -> {
                                if (r.getWidth() != IMAGE_WIDTH || r.getHeight() != IMAGE_HEIGHT) {
                                    throw new AssertionError("Unexpected decoded image dimensions");
                                }
                                count[0]++;
                                resource = r;
                                coveredPixels[0] += sw * sh;
                                bounds[0] = Math.min(bounds[0], x);
                                bounds[1] = Math.min(bounds[1], y);
                                bounds[2] = Math.max(bounds[2], x + w);
                                bounds[3] = Math.max(bounds[3], y + h);
                            });
                            if (awaitingRemoval.get()) {
                                int[] words = new int[frame.getColumns()];
                                int[] flags = new int[frame.getColumns()];
                                for (int row = 0; row < frame.getRows(); row++) {
                                    frame.copyTextLine(row, words, 0, flags, 0, null);
                                    var line = new StringBuilder();
                                    for (int column = 0; column < words.length; column++) {
                                        line.appendCodePoint((flags[column] & TerminalRenderCellFlags.CODEPOINT) != 0 ? words[column] : ' ');
                                    }
                                    if (line.toString().contains("KETRATERM_SMOKE_TEXT")) textPreview[0] = true;
                                }
                            }
                        });
                        maximumPlacements = Math.max(maximumPlacements, count[0]);
                        // A read may contain only part of Yazi's per-cell placements. Wait for
                        // complete source coverage before testing extent or navigating away.
                        if (coveredPixels[0] == IMAGE_WIDTH * IMAGE_HEIGHT) {
                            double width = (bounds[2] - bounds[0]) * CELL_WIDTH;
                            double height = (bounds[3] - bounds[1]) * CELL_HEIGHT;
                            if (Math.abs(width - IMAGE_WIDTH) > 0.000001 || Math.abs(height - IMAGE_HEIGHT) > 0.000001) {
                                throw new AssertionError("Unexpected displayed pixel extent: " + width + "x" + height);
                            }
                            image.countDown();
                        }
                        if (textPreview[0] && count[0] == 0) removed.countDown();
                    }
                    exitCode = process.waitFor();
                    if (image.getCount() != 0 || awaitingRemoval.get() && removed.getCount() != 0) {
                        failure.compareAndSet(null, new AssertionError("Application exited before the expected frame state"));
                    }
                    listener.onClosed(exitCode);
                } catch (Throwable error) {
                    if (!closing.get()) {
                        failure.set(error);
                        image.countDown();
                        removed.countDown();
                        listener.onError(error);
                    }
                } finally {
                    image.countDown();
                    removed.countDown();
                    closed.countDown();
                }
            });
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            try {
                inputBytes += length;
                if (inputBytes > 64 * 1024) throw new IOException("Smoke input exceeded 64 KiB");
                replies.write(bytes, offset, length);
                replies.flush();
                process.getOutputStream().write(bytes, offset, length);
                process.getOutputStream().flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override public void resize(int columns, int rows) {
            if (columns != COLUMNS || rows != ROWS) throw new IllegalArgumentException("Smoke uses fixed 100x30 geometry");
        }

        @Override public void close() {
            if (!closing.compareAndSet(false, true)) return;
            try {
                // EOF lets the relay close its PTY and reap the application on assertion failure.
                try { process.getOutputStream().close(); } catch (IOException error) { failure.compareAndSet(null, error); }
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        failure.compareAndSet(null, new IOException("Relay did not terminate after forced shutdown"));
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                failure.compareAndSet(null, error);
            } finally {
                try { replies.close(); } catch (IOException error) { failure.compareAndSet(null, error); }
                cleanupDone.countDown();
            }
        }

        private void await(CountDownLatch event, String description) throws InterruptedException {
            if (!event.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out awaiting " + description + "; failure=" + failure.get());
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
