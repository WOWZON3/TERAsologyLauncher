// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.launcher.repositories;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.terasology.launcher.model.Build;
import org.terasology.launcher.model.GameIdentifier;
import org.terasology.launcher.model.GameRelease;
import org.terasology.launcher.model.Profile;
import org.terasology.launcher.model.ReleaseMetadata;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LegacyJenkinsRepositoryAdapter#fetchReleases() should")
class LegacyJenkinsRepositoryAdapterTest {

    static final String BASE_URL = "http://jenkins.terasology.org";
    static final String JOB = "DistroOmega";

    static Gson gson;
    static Jenkins.ApiResult validResult;
    static GameRelease expectedRelease;

    @BeforeAll
    static void setup() {
        gson = new Gson();
        validResult = gson.fromJson(JenkinsPayload.V1.validPayload(), Jenkins.ApiResult.class);
        expectedRelease = expectedRelease(validResult);
    }

    static GameRelease expectedRelease(Jenkins.ApiResult apiResult) {
        try {
            final URL expectedArtifactUrl = new URL(validResult.builds[0].url + "artifact/" + validResult.builds[0].artifacts[0].relativePath);
            final GameIdentifier id = new GameIdentifier(validResult.builds[0].number, Build.STABLE, Profile.OMEGA);
            final ReleaseMetadata releaseMetadata = new ReleaseMetadata(new ArrayList<>(), new Date(validResult.builds[0].timestamp), true);
            return new GameRelease(id, expectedArtifactUrl, releaseMetadata);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Error in test setup!");
        }
    }

    @Test
    @DisplayName("handle null Jenkins response gracefully")
    void handleNullJenkinsResponseGracefully() {
        final JenkinsClient nullClient = new StubJenkinsClient(url -> null, url -> null);
        final LegacyJenkinsRepositoryAdapter adapter =
                new LegacyJenkinsRepositoryAdapter(BASE_URL, JOB, Build.STABLE, Profile.OMEGA, nullClient);
        assertTrue(adapter.fetchReleases().isEmpty());
    }

    @Test
    @DisplayName("process valid response correctly")
    void processValidResponseCorrectly() {
        final JenkinsClient stubClient = new StubJenkinsClient(url -> validResult, url -> {
            throw new RuntimeException();
        });
        final LegacyJenkinsRepositoryAdapter adapter =
                new LegacyJenkinsRepositoryAdapter(BASE_URL, JOB, Build.STABLE, Profile.OMEGA, stubClient);

        assertEquals(1, adapter.fetchReleases().size());
        assertAll(
                () -> assertEquals(expectedRelease.getId(), adapter.fetchReleases().get(0).getId()),
                () -> assertEquals(expectedRelease.getUrl(), adapter.fetchReleases().get(0).getUrl()),
                () -> assertEquals(expectedRelease.getTimestamp(), adapter.fetchReleases().get(0).getTimestamp())
        );
    }

    @Test
    @DisplayName("process minimal valid response correctly")
    void processMinimalValidResponseCorrectly() {
        final Jenkins.ApiResult minimalValidResult = gson.fromJson(JenkinsPayload.V1.minimalValidPayload(), Jenkins.ApiResult.class);
        final JenkinsClient stubClient = new StubJenkinsClient(url -> minimalValidResult, url -> {
            throw new RuntimeException();
        });
        final LegacyJenkinsRepositoryAdapter adapter =
                new LegacyJenkinsRepositoryAdapter(BASE_URL, JOB, Build.STABLE, Profile.OMEGA, stubClient);

        assertEquals(1, adapter.fetchReleases().size());
        assertAll(
                () -> assertEquals(expectedRelease.getId(), adapter.fetchReleases().get(0).getId()),
                () -> assertEquals(expectedRelease.getUrl(), adapter.fetchReleases().get(0).getUrl()),
                () -> assertEquals(expectedRelease.getTimestamp(), adapter.fetchReleases().get(0).getTimestamp()),
                () -> assertTrue(adapter.fetchReleases().get(0).getChangelog().isEmpty())
        );
    }

    static Stream<Arguments> incompatibleApiResults() {
        return JenkinsPayload.V1.incompatiblePayloads().stream()
                .map(payload -> gson.fromJson(payload, Jenkins.ApiResult.class))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @DisplayName("skip incomplete API results")
    @MethodSource("incompatibleApiResults")
    void skipIncompatibleApiResults(Jenkins.ApiResult result) {
        final JenkinsClient stubClient = new StubJenkinsClient(url -> result, url -> {
            throw new RuntimeException();
        });
        final LegacyJenkinsRepositoryAdapter adapter =
                new LegacyJenkinsRepositoryAdapter(BASE_URL, JOB, Build.STABLE, Profile.OMEGA, stubClient);

        assertTrue(adapter.fetchReleases().isEmpty());
    }
}
