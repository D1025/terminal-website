package com.fonline.newdawn.update;

import com.fonline.newdawn.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Set;

final class UpdatePathPolicy {
    private static final Set<String> RESERVED_NAMES = Set.of("CON", "PRN", "AUX", "NUL");
    private static final int MAX_PATH_LENGTH = 500;

    private UpdatePathPolicy() {}

    static NormalizedPath normalize(String value) {
        if (value == null) throw invalid();
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);

        if (path.isBlank() || path.length() > MAX_PATH_LENGTH || path.startsWith("/") || path.startsWith("//")
                || path.matches("^[A-Za-z]:.*") || path.endsWith("/") || path.contains("//")) {
            throw invalid();
        }

        String[] segments = path.split("/", -1);
        for (String segment : segments) validateSegment(segment);
        return new NormalizedPath(path, path.toLowerCase(Locale.ROOT), segments[segments.length - 1]);
    }

    private static void validateSegment(String segment) {
        if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                || segment.endsWith(".") || segment.endsWith(" ")) throw invalid();

        for (int index = 0; index < segment.length(); index += 1) {
            char character = segment.charAt(index);
            if (character < 32 || "<>:\"|?*".indexOf(character) >= 0) throw invalid();
        }

        String baseName = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
        String upper = baseName.toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(upper) || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) throw invalid();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_UPDATE_PATH",
                "The target path must be a safe relative Windows path without traversal, drive letters or reserved names.");
    }

    record NormalizedPath(String value, String key, String fileName) {}
}
