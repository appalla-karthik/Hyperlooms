package dev.hyperlooms.controlplane.job;

import java.io.InputStream;

/** Private, server-credential-only object access; never expose verified keys for client writes. */
public interface InputObjectStore {

    InputStream get(String key);

    void put(String key, byte[] content);
}
