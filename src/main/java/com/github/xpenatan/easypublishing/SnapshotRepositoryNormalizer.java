package com.github.xpenatan.easypublishing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Converts Gradle's unique local snapshot names to stable {@code -SNAPSHOT} names. */
public final class SnapshotRepositoryNormalizer {
    private static final Map<String, String> CHECKSUM_ALGORITHMS = Map.of(
        "md5", "MD5",
        "sha1", "SHA-1",
        "sha256", "SHA-256",
        "sha512", "SHA-512"
    );

    private SnapshotRepositoryNormalizer() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one snapshot repository directory");
        }
        normalize(new File(arguments[0]));
    }

    public static void normalize(File repositoryDirectory) {
        if (!repositoryDirectory.isDirectory()) {
            return;
        }

        try (Stream<java.nio.file.Path> paths = Files.walk(repositoryDirectory.toPath())) {
            paths.filter(Files::isDirectory)
                .map(java.nio.file.Path::toFile)
                .filter(directory -> directory.getName().endsWith("-SNAPSHOT"))
                .forEach(SnapshotRepositoryNormalizer::normalizeVersionDirectory);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to normalize prepared snapshot repository", exception);
        }
    }

    private static void normalizeVersionDirectory(File versionDirectory) {
        String snapshotVersion = versionDirectory.getName();
        String baseVersion = snapshotVersion.substring(
            0,
            snapshotVersion.length() - "-SNAPSHOT".length()
        );
        Pattern uniqueVersion = Pattern.compile(
            Pattern.quote(baseVersion) + "-\\d{8}\\.\\d{6}-\\d+"
        );

        File metadata = new File(versionDirectory, "maven-metadata.xml");
        if (metadata.isFile()) {
            normalizeMetadata(metadata, uniqueVersion, snapshotVersion);
        }

        File[] files = versionDirectory.listFiles(File::isFile);
        if (files == null) {
            return;
        }
        for (File source : files) {
            Matcher matcher = uniqueVersion.matcher(source.getName());
            if (!matcher.find()) {
                continue;
            }
            File target = new File(
                versionDirectory,
                matcher.replaceAll(Matcher.quoteReplacement(snapshotVersion))
            );
            if (target.exists()) {
                throw new IllegalStateException("Snapshot target already exists: " + target);
            }
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException exception) {
                throw new IllegalStateException(
                    "Unable to normalize snapshot artifact " + source.getAbsolutePath(),
                    exception
                );
            }
        }
    }

    private static void normalizeMetadata(
        File metadata,
        Pattern uniqueVersion,
        String snapshotVersion
    ) {
        try {
            String content = Files.readString(metadata.toPath(), StandardCharsets.UTF_8);
            String normalized = uniqueVersion.matcher(content)
                .replaceAll(Matcher.quoteReplacement(snapshotVersion));
            if (normalized.equals(content)) {
                return;
            }

            Files.writeString(metadata.toPath(), normalized, StandardCharsets.UTF_8);
            byte[] metadataBytes = Files.readAllBytes(metadata.toPath());
            for (Map.Entry<String, String> checksum : CHECKSUM_ALGORITHMS.entrySet()) {
                byte[] digest = MessageDigest.getInstance(checksum.getValue()).digest(metadataBytes);
                Files.writeString(
                    new File(metadata.getParentFile(), metadata.getName() + "." + checksum.getKey()).toPath(),
                    HexFormat.of().formatHex(digest),
                    StandardCharsets.UTF_8
                );
            }
        }
        catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "Unable to normalize snapshot metadata " + metadata.getAbsolutePath(),
                exception
            );
        }
    }
}
