package com.mentalfrostbyte.jello.music;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * A byte buffer that one thread downloads into while others read it from any offset. Readers block until the
 * bytes they want arrive, the download completes, or the buffer is closed. Keeping the whole stream (a few
 * megabytes of MP3) is what lets a seek re-read from the start without downloading again.
 */
final class GrowingBuffer {
    private byte[] data = new byte[1 << 20];
    private int size;
    private boolean complete, closed;

    synchronized void append(byte[] bytes, int offset, int length) {
        if (this.closed) return;
        if (this.size + length > this.data.length) this.data = Arrays.copyOf(this.data, Math.max(this.data.length * 2, this.size + length));
        System.arraycopy(bytes, offset, this.data, this.size, length);
        this.size += length;
        notifyAll();
    }

    synchronized void finish() {
        this.complete = true;
        notifyAll();
    }

    synchronized void close() {
        this.closed = true;
        notifyAll();
    }

    synchronized boolean isComplete() {
        return this.complete;
    }

    synchronized int size() {
        return this.size;
    }

    /** A stream over the buffer from the start; reads block for data that hasn't arrived yet. */
    InputStream stream() {
        return new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) return 0;
                synchronized (GrowingBuffer.this) {
                    while (this.position >= GrowingBuffer.this.size && !GrowingBuffer.this.complete && !GrowingBuffer.this.closed) {
                        try {
                            GrowingBuffer.this.wait(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for audio data", e);
                        }
                    }
                    if (GrowingBuffer.this.closed) throw new IOException("Audio stream closed");
                    int available = GrowingBuffer.this.size - this.position;
                    if (available <= 0) return -1;
                    int count = Math.min(length, available);
                    System.arraycopy(GrowingBuffer.this.data, this.position, target, offset, count);
                    this.position += count;
                    return count;
                }
            }
        };
    }
}
