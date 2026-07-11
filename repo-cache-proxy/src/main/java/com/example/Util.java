package com.example;

import java.io.File;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Util {
    private static MessageDigest instance;
    static {
        try {
            instance = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    public static String hexdigest(byte[] value) {
        return HexFormat.of().formatHex(instance.digest(value));
    }
    public static File getCacheFilePath(File basedir, String digest) {
        File sub = Path.of(basedir.getAbsolutePath(), digest.substring(0, 2)).toFile();
        return Path.of(sub.getAbsolutePath(), digest).toFile();
    }
    public static String getHeader(Map<String, List<String>> headers, String target) {
        for (Entry<String, List<String>> entry: headers.entrySet()) {
            if (entry.getKey().toLowerCase().equals(target.toLowerCase())) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }
}
