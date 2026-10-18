package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdbClientPackageListTest {

    @Test
    void parsesPlainPackageLines() {
        String output = """
            package:com.example.beta
            package:com.example.app
            """;
        assertEquals(List.of("com.example.app", "com.example.beta"),
            AdbClient.parsePackageList(output));
    }

    @Test
    void stripsApkPathsAndTrailingUid() {
        String output = """
            package:/data/app/~~abc==/com.example.one-1/base.apk=com.example.one
            package:com.example.two uid:10234
            """;
        assertEquals(List.of("com.example.one", "com.example.two"),
            AdbClient.parsePackageList(output));
    }

    @Test
    void ignoresNoiseAndDuplicates() {
        String output = """
            Warning: something happened
            package:com.example.app

            package:com.example.app
            """;
        assertEquals(List.of("com.example.app"), AdbClient.parsePackageList(output));
    }

    @Test
    void toleratesEmptyOutput() {
        assertTrue(AdbClient.parsePackageList("").isEmpty());
        assertTrue(AdbClient.parsePackageList(null).isEmpty());
    }
}
