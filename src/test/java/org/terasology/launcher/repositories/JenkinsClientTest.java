// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.launcher.repositories;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Note: We are testing with the default Gson parser here. This can go out of sync with the instantiation of
 * JenkinsClient in RepositoryManager. Using common dependency injection for productive code and tests would ensure we
 * are using the "real thing" under test here.
 */
@DisplayName("JenkinsClient")
class JenkinsClientTest {

    @Test
    @DisplayName("should handle IOException on request(url) gracefully")
    void nullOnIoException() throws IOException {
        final Gson gson = new Gson();
        final JenkinsClient client = new JenkinsClient(gson);

        URL urlThrowingException = mock(URL.class);
        doThrow(IOException.class).when(urlThrowingException).openStream();

        assertNull(client.request(urlThrowingException));
    }

    @Test
    @DisplayName("can handle invalid JSON payload")
    void canHandleInvalidJsonPayload() throws IOException {
        final Gson gson = new Gson();
        final JenkinsClient client = new JenkinsClient(gson);

        InputStream invalidPayload = new ByteArrayInputStream("{ this is ] no json |[!".getBytes());

        URL urlToInvalidPayload = mock(URL.class);
        doReturn(invalidPayload).when(urlToInvalidPayload).openStream();

        assertNull(client.request(urlToInvalidPayload));
    }
}
