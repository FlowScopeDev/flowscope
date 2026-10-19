package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpdateCheckTest {
    /** Records what the check sent and answers with whatever the test wants. No network. */
    private static final class Recording implements FlowHttpClient {
        private final int status;
        private final String body;
        private final Exception failure;
        Map<String, String> sentHeaders;
        String sentMethod;
        URI sentUri;
        String sentBody = "unset";

        Recording(int status, String body) { this(status, body, null); }

        Recording(int status, String body, Exception failure) {
            this.status = status; this.body = body; this.failure = failure;
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body,
            long timeoutMillis) throws Exception {
            sentMethod = method; sentUri = uri; sentHeaders = headers; sentBody = body;
            if (failure != null) throw failure;
            return new Response(status, this.body, Map.of(), 5);
        }
    }

    private static String release(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":"
            + "\"https://github.com/FlowScopeDev/flowscope/releases/tag/" + tag + "\","
            + "\"body\":\"notes\",\"id\":123456789,\"assets\":[]}";
    }

    @Test
    void reportsANewerReleaseWithItsPublicUrl() {
        Recording http = new Recording(200, release("v1.1.0"));
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Available.class,
            UpdateCheck.check(http, "1.0.0", 1_000));
        assertEquals("1.1.0", outcome.version());
        assertEquals("https://github.com/FlowScopeDev/flowscope/releases/tag/v1.1.0",
            outcome.releaseUrl());
    }

    @Test
    void theSameVersionIsUpToDate() {
        assertInstanceOf(UpdateCheck.Outcome.UpToDate.class,
            UpdateCheck.check(new Recording(200, release("v1.0.0")), "1.0.0", 1_000));
    }

    /** A local build ahead of the newest release must not be told to downgrade. */
    @Test
    void anOlderPublishedReleaseIsUpToDate() {
        assertInstanceOf(UpdateCheck.Outcome.UpToDate.class,
            UpdateCheck.check(new Recording(200, release("v0.9.0")), "1.0.0", 1_000));
    }

    /**
     * GitHub's "latest release" endpoint excludes pre-releases and answers 404 when only those
     * exist — FlowScope's own state until the first DMG release is promoted.
     */
    @Test
    void aRepositoryWithNoFullReleaseIsNotAnError() {
        assertInstanceOf(UpdateCheck.Outcome.NoRelease.class, UpdateCheck.check(
            new Recording(404, "{\"message\":\"Not Found\",\"status\":\"404\"}"), "1.0.0", 1_000));
    }

    @Test
    void rateLimitingIsReportedAsSuch() {
        for (int status : List.of(403, 429)) {
            var outcome = assertInstanceOf(UpdateCheck.Outcome.Unavailable.class,
                UpdateCheck.check(new Recording(status, "{\"message\":\"rate limit\"}"), "1.0.0", 1_000));
            assertTrue(outcome.reason().contains("rate limit"), outcome.reason());
        }
    }

    @Test
    void anUnreadableAnswerIsUnavailableAndNeverQuotesTheBody() {
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Unavailable.class,
            UpdateCheck.check(new Recording(200, "not json at all {{{"), "1.0.0", 1_000));
        assertFalse(outcome.reason().contains("not json"), outcome.reason());
    }

    @Test
    void aMissingTagIsUnavailableRatherThanAnUpdate() {
        assertInstanceOf(UpdateCheck.Outcome.Unavailable.class,
            UpdateCheck.check(new Recording(200, "{\"html_url\":\"https://example.invalid\"}"),
                "1.0.0", 1_000));
    }

    /** An unparseable tag must never be presented as a newer version. */
    @Test
    void anUnrecognisedTagDoesNotNagForAnUpgrade() {
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Unavailable.class,
            UpdateCheck.check(new Recording(200, release("nightly-2026-10-19")), "1.0.0", 1_000));
        assertTrue(outcome.reason().contains("unrecognised"), outcome.reason());
    }

    @Test
    void beingOfflineIsAResultNotAnException() {
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Unavailable.class, UpdateCheck.check(
            new Recording(0, null, new java.io.IOException("nodename nor servname provided")),
            "1.0.0", 1_000));
        assertTrue(outcome.reason().contains("github.com"), outcome.reason());
    }

    @Test
    void anUnexpectedStatusNamesTheCodeOnly() {
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Unavailable.class,
            UpdateCheck.check(new Recording(500, "<html>upstream exploded</html>"), "1.0.0", 1_000));
        assertEquals("GitHub answered HTTP 500", outcome.reason());
    }

    /**
     * The request carries the version in the User-Agent GitHub requires, a read-only method, no
     * body, and nothing else that could identify the machine or the person running it.
     */
    @Test
    void sendsOnlyTheVersionAndNothingIdentifying() {
        Recording http = new Recording(200, release("v1.0.0"));
        UpdateCheck.check(http, "1.0.0", 1_000);
        assertEquals("GET", http.sentMethod);
        assertEquals(URI.create(UpdateCheck.LATEST_RELEASE_URL), http.sentUri);
        assertNull(http.sentBody);
        assertEquals("flowscope/1.0.0", http.sentHeaders.get("User-Agent"));
        assertEquals(Set.copyOf(List.of("Accept", "X-GitHub-Api-Version", "User-Agent")),
            Set.copyOf(http.sentHeaders.keySet()));
    }

    @Test
    void ordersVersionsNumericallyAndRanksPreReleasesBelowTheirRelease() {
        assertTrue(UpdateCheck.compareVersions("1.1.0", "1.0.0") > 0);
        assertTrue(UpdateCheck.compareVersions("1.0.0", "1.1.0") < 0);
        assertEquals(0, UpdateCheck.compareVersions("1.0.0", "1.0.0"));
        // Segments are integers, not text: "10" sorts above "9".
        assertTrue(UpdateCheck.compareVersions("1.10.0", "1.9.0") > 0);
        // A missing segment is zero, so 1.0 and 1.0.0 are the same release.
        assertEquals(0, UpdateCheck.compareVersions("1.0", "1.0.0"));
        assertTrue(UpdateCheck.compareVersions("1.0.0-rc1", "1.0.0") < 0);
        assertTrue(UpdateCheck.compareVersions("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(UpdateCheck.compareVersions("1.0.0-rc2", "1.0.0-rc1") > 0);
        assertEquals(0, UpdateCheck.compareVersions("1.0.0-rc1", "1.0.0-rc1"));
    }

    /**
     * The release link is read off the network and ends up somewhere a person clicks, so anything
     * that is not this project's own releases path is replaced with the canonical page.
     */
    @Test
    void pinsTheReleaseLinkToThisProjectsReleasesPath() {
        assertEquals("https://github.com/FlowScopeDev/flowscope/releases/tag/v1.1.0",
            UpdateCheck.trustedReleaseUrl("https://github.com/FlowScopeDev/flowscope/releases/tag/v1.1.0"));
        for (String hostile : List.of(
            "https://evil.invalid/FlowScopeDev/flowscope/releases/tag/v1.1.0",
            "http://github.com/FlowScopeDev/flowscope/releases/tag/v1.1.0",
            "https://github.com/someoneelse/flowscope/releases/tag/v1.1.0",
            "https://github.com/FlowScopeDev/flowscope/releases/tag/../../../settings",
            "https://github.com/FlowScopeDev/flowscope/releases/tag/v1?x=y",
            "https://github.com/FlowScopeDev/flowscope/releases")) {
            assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.trustedReleaseUrl(hostile), hostile);
        }
        assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.trustedReleaseUrl(null));
    }

    @Test
    void substitutesTheReleasesPageWhenGitHubSendsAnOddLink() {
        var outcome = assertInstanceOf(UpdateCheck.Outcome.Available.class, UpdateCheck.check(
            new Recording(200, "{\"tag_name\":\"v2.0.0\",\"html_url\":\"https://evil.invalid/x\"}"),
            "1.0.0", 1_000));
        assertEquals(UpdateCheck.RELEASES_PAGE, outcome.releaseUrl());
    }

    @Test
    void refusesToOrderAnythingThatIsNotADottedNumericVersion() {
        assertNull(UpdateCheck.compareVersions("nightly", "1.0.0"));
        assertNull(UpdateCheck.compareVersions("1.0.0", "unknown"));
        assertNull(UpdateCheck.compareVersions("1..0", "1.0.0"));
        assertNull(UpdateCheck.compareVersions("", "1.0.0"));
        assertNull(UpdateCheck.compareVersions(null, "1.0.0"));
    }
}
