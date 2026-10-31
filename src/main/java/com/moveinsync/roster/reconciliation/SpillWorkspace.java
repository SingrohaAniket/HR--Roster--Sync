package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.core.reconciliation.ExportRow;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class SpillWorkspace implements Closeable {

    private static final String SEPARATOR = "\u001F";
    private static final int BUFFER_CHARS = 4096;

    private final Path directory;
    private final BufferedWriter[] buckets;
    private BufferedWriter changes;

    private SpillWorkspace(Path directory, int bucketCount) {
        this.directory = directory;
        this.buckets = new BufferedWriter[bucketCount];
    }

    static SpillWorkspace create(int bucketCount) throws IOException {
        SpillWorkspace workspace = new SpillWorkspace(Files.createTempDirectory("roster-reconciliation-"), bucketCount);
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            workspace.buckets[bucket] = writer(workspace.bucketFile(bucket));
        }
        workspace.changes = writer(workspace.changesFile());
        return workspace;
    }

    private static BufferedWriter writer(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8), BUFFER_CHARS);
    }

    private Path bucketFile(int bucket) {
        return directory.resolve("bucket-" + bucket);
    }

    private Path changesFile() {
        return directory.resolve("changes");
    }

    void appendRow(int bucket, ExportRow row) throws IOException {
        append(buckets[bucket], format(row));
    }

    void appendUnusable(int bucket, String externalId) throws IOException {
        append(buckets[bucket], externalId + SEPARATOR);
    }

    void appendChange(String spilledLine) throws IOException {
        append(changes, spilledLine);
    }

    private static void append(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    void finishPartitioning() throws IOException {
        for (BufferedWriter bucket : buckets) {
            bucket.close();
        }
    }

    BufferedReader readBucket(int bucket) throws IOException {
        return Files.newBufferedReader(bucketFile(bucket), StandardCharsets.UTF_8);
    }

    BufferedReader readChanges() throws IOException {
        changes.close();
        return Files.newBufferedReader(changesFile(), StandardCharsets.UTF_8);
    }

    private static String format(ExportRow row) {
        return String.join(SEPARATOR, row.externalId(), row.contentHash(), orEmpty(row.name()), orEmpty(row.email()),
                orEmpty(row.phone()), orEmpty(row.managerExternalId()), orEmpty(row.officeCode()), orEmpty(row.shiftCode()));
    }

    static String externalId(String line) {
        return line.substring(0, line.indexOf(SEPARATOR));
    }

    static String hash(String line) {
        int start = line.indexOf(SEPARATOR) + 1;
        int end = line.indexOf(SEPARATOR, start);
        return end < 0 ? null : line.substring(start, end);
    }

    static ExportRow row(String line) {
        String[] cells = line.split(SEPARATOR, -1);
        return new ExportRow(cells[0], nullIfEmpty(cells[2]), nullIfEmpty(cells[3]), nullIfEmpty(cells[4]),
                nullIfEmpty(cells[5]), nullIfEmpty(cells[6]), nullIfEmpty(cells[7]));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }

    @Override
    public void close() throws IOException {
        for (BufferedWriter bucket : buckets) {
            closeQuietly(bucket);
        }
        closeQuietly(changes);
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
            }
        }
    }
}
