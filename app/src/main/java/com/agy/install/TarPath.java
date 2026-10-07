package com.agy.install;

import java.util.ArrayList;
import java.util.List;

/** Normalizes a tar entry name into a safe relative path (null = reject). */
public final class TarPath {

    private TarPath() {
    }

    public static String normalize(String rawName) {
        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || ".".equals(name)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }
}
