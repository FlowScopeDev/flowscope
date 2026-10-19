package dev.flowscope.host;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * An explicitly requested check for a newer FlowScope release. FlowScope has no telemetry: nothing
 * here runs unless a person asks for it, and the only thing sent is the running version, in the
 * User-Agent that the GitHub API requires of every caller.
 *
 * <p>The response body is read for {@code tag_name} and {@code html_url} and then discarded. It is
 * never logged, reported, or persisted — the same rule the runner applies to API response bodies.
 */
public final class UpdateCheck {
    /** The published releases of the repository this build came from. */
    public static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/FlowScopeDev/flowscope/releases/latest";

    private static final long DEFAULT_TIMEOUT_MILLIS = 10_000;

    /** The canonical releases page, used whenever GitHub's own link is absent or unexpected. */
    public static final String RELEASES_PAGE =
        "https://github.com/FlowScopeDev/flowscope/releases";

    private UpdateCheck() {}

    /** What a check found. Deliberately total: a failed check is a result, not an exception. */
    public sealed interface Outcome {
        /** The running build is the newest published release, or newer than it. */
        record UpToDate(String version) implements Outcome {}

        /** A newer release exists. [releaseUrl] is GitHub's public release page. */
        record Available(String version, String releaseUrl) implements Outcome {}

        /**
         * The repository has no full release yet. GitHub's "latest release" endpoint excludes
         * pre-releases and answers 404 when only those exist, which is FlowScope's state until the
         * first DMG release is promoted out of pre-release.
         */
        record NoRelease() implements Outcome {}

        /**
         * The check could not be completed — offline, rate-limited, or an unreadable answer.
         * [reason] is a short fixed phrase, never response text.
         */
        record Unavailable(String reason) implements Outcome {}
    }

    /**
     * Runs the check against GitHub with the platform TLS client. The only public entry point:
     * {@link FlowHttpClient} stays internal to this module, so callers cannot be handed a
     * different transport by accident.
     */
    public static Outcome check(String currentVersion) {
        return check(FlowHttpClient.platform(), currentVersion, DEFAULT_TIMEOUT_MILLIS);
    }

    static Outcome check(FlowHttpClient http, String currentVersion, long timeoutMillis) {
        FlowHttpClient.Response response;
        try {
            response = http.send("GET", URI.create(LATEST_RELEASE_URL), Map.of(
                "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28",
                "User-Agent", "flowscope/" + currentVersion), null, timeoutMillis);
        } catch (Exception unreachable) {
            // The message can name a host or a proxy but never carries response content.
            return new Outcome.Unavailable("could not reach github.com");
        }
        if (response.status() == 404) return new Outcome.NoRelease();
        if (response.status() == 403 || response.status() == 429) {
            return new Outcome.Unavailable("GitHub rate limit reached — try again later");
        }
        if (response.status() != 200) {
            return new Outcome.Unavailable("GitHub answered HTTP " + response.status());
        }

        String tag;
        String releaseUrl;
        try {
            Object json = ApiJson.parse(response.body());
            tag = string(json, "tag_name");
            releaseUrl = string(json, "html_url");
        } catch (Exception unreadable) {
            return new Outcome.Unavailable("could not read GitHub's answer");
        }
        if (tag == null) return new Outcome.Unavailable("could not read GitHub's answer");

        String released = tag.startsWith("v") ? tag.substring(1) : tag;
        Integer order = compareVersions(released, currentVersion);
        // A tag we cannot parse must not nag someone to "upgrade" to an unknown build.
        if (order == null) return new Outcome.Unavailable("unrecognised release tag");
        if (order <= 0) return new Outcome.UpToDate(released);
        return new Outcome.Available(released, trustedReleaseUrl(releaseUrl));
    }

    /**
     * Accepts GitHub's {@code html_url} only when it is an {@code https} URL on this project's own
     * releases path, and substitutes {@link #RELEASES_PAGE} otherwise. The value arrives over the
     * network and ends up somewhere a person can click, so it is pinned here rather than trusted
     * by the caller.
     */
    static String trustedReleaseUrl(String candidate) {
        if (candidate == null || !candidate.startsWith(RELEASES_PAGE + "/")) return RELEASES_PAGE;
        // Nothing beyond an ordinary tag path: no credentials, no query, no fragment.
        String rest = candidate.substring(RELEASES_PAGE.length() + 1);
        boolean plain = !rest.isEmpty() && rest.chars()
            .allMatch(c -> Character.isLetterOrDigit(c) || "./-_".indexOf(c) >= 0);
        return plain && !rest.contains("..") ? candidate : RELEASES_PAGE;
    }

    private static String string(Object json, String field) {
        if (!(json instanceof Map<?, ?> map)) return null;
        Object value = map.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Orders two versions: negative when [left] is older, 0 when equal, positive when newer, and
     * null when either side is not a dotted numeric version with an optional {@code -suffix}.
     *
     * <p>A pre-release suffix ranks below the same release, so {@code 1.0.0-rc1 < 1.0.0}, and
     * {@code 1.10.0 > 1.9.0} because segments are compared as integers rather than as text.
     */
    static Integer compareVersions(String left, String right) {
        List<Integer> leftNumbers = numbers(left);
        List<Integer> rightNumbers = numbers(right);
        if (leftNumbers == null || rightNumbers == null) return null;
        for (int i = 0; i < Math.max(leftNumbers.size(), rightNumbers.size()); i++) {
            int a = i < leftNumbers.size() ? leftNumbers.get(i) : 0;
            int b = i < rightNumbers.size() ? rightNumbers.get(i) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        String leftSuffix = suffix(left);
        String rightSuffix = suffix(right);
        if (leftSuffix.equals(rightSuffix)) return 0;
        if (leftSuffix.isEmpty()) return 1;
        if (rightSuffix.isEmpty()) return -1;
        return Integer.signum(leftSuffix.compareTo(rightSuffix));
    }

    private static List<Integer> numbers(String version) {
        if (version == null) return null;
        String core = version.strip();
        int dash = core.indexOf('-');
        if (dash >= 0) core = core.substring(0, dash);
        if (core.isEmpty()) return null;
        List<Integer> result = new java.util.ArrayList<>();
        for (String segment : core.split("\\.", -1)) {
            if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) return null;
            try {
                result.add(Integer.parseInt(segment));
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
        return result;
    }

    private static String suffix(String version) {
        int dash = version.strip().indexOf('-');
        return dash < 0 ? "" : version.strip().substring(dash + 1);
    }
}
