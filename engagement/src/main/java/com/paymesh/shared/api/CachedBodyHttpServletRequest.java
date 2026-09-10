package com.paymesh.shared.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Holds a request body in memory so a filter can read it and the handler can still read it too.
 * <p>
 * A servlet body is a one-shot stream: hashing or signing it without this wrapper hands the
 * controller an empty request.
 * <p>
 * Shared because two filters need the same thing for two different reasons -- idempotency hashes the
 * body to detect a key reused with different content, and the provider callback filter verifies an
 * HMAC over the RAW bytes. Two copies would be two chances for one of them to stop being byte-exact,
 * and a signature check against a re-serialized body is not a signature check.
 */
public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** The raw bytes, exactly as they arrived. Not a re-encoding of a parsed value. */
    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffered = new ByteArrayInputStream(body);

        return new ServletInputStream() {

            @Override
            public int read() {
                return buffered.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return buffered.read(target, offset, length);
            }

            @Override
            public boolean isFinished() {
                return buffered.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Cached bodies are read synchronously");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();

        return new BufferedReader(new InputStreamReader(
            getInputStream(),
            encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)
        ));
    }
}
