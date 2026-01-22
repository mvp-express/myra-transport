package express.mvp.myra.server;

import express.mvp.myra.transport.RegisteredBuffer;

/**
 * Optional extension for server-side connections that can provide registered buffers.
 */
public interface RegisteredBufferProvider {

    /**
     * Acquire a registered buffer, blocking if necessary.
     */
    RegisteredBuffer acquireBuffer();

    /**
     * Try to acquire a registered buffer without blocking.
     */
    RegisteredBuffer tryAcquireBuffer();
}
