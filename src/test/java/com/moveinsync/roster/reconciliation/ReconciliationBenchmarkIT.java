package com.moveinsync.roster.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.moveinsync.roster.core.reconciliation.ExportCsv;
import com.moveinsync.roster.domain.ReconciliationStatus;
import com.moveinsync.roster.security.TenantContext;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;

@Tag("benchmark")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationBenchmarkIT extends AbstractReconciliationIT {

    private static final long MB = 1024 * 1024;
    private static final int BUCKETS = 256;
    private static final int LARGE = 200_000;
    private static final int SMALL = 20_000;
    private static final long ALLOWED_GROWTH_BYTES = 32 * MB;

    @Autowired
    private ReconciliationService reconciliation;

    private final List<Path> files = new ArrayList<>();

    @BeforeAll
    static void quietLogs() {
        for (String name : List.of("com.moveinsync.roster", "org.springframework.web", "org.springframework.security")) {
            ((Logger) LoggerFactory.getLogger(name)).setLevel(Level.INFO);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        TenantContext.clear();
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
        files.clear();
    }

    private static void report(String line) {
        System.out.println("BENCHMARK " + line);
    }

    private Path writeExport(int size, int stopAfter, boolean everyRowChanged) throws Exception {
        Path file = Files.createTempFile("roster-export-" + size + "-", ".csv");
        files.add(file);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(ExportCsv.HEADER);
            writer.newLine();
            int gone = size / 200;
            int written = 0;
            for (int i = 0; i < size - gone && written < stopAfter; i++, written++) {
                String shift = everyRowChanged || i % 100 == 0 ? "S2" : "S1";
                writer.write(new Person("EMP-" + i, shift).csv());
                writer.newLine();
            }
            for (int i = 0; i < gone && written < stopAfter; i++, written++) {
                writer.write(new Person("JOIN-" + i, "S1").csv());
                writer.newLine();
            }
        }
        return file;
    }

    private TestTenant tenantWithStore(int size) throws Exception {
        TestTenant tenant = newTenant();
        List<Person> batch = new ArrayList<>(5000);
        for (int i = 0; i < size; i++) {
            batch.add(new Person("EMP-" + i, "S1"));
            if (batch.size() == 5000) {
                seed(tenant, batch, "ACTIVE", BEFORE_EXPORT);
                batch.clear();
            }
        }
        seed(tenant, batch, "ACTIVE", BEFORE_EXPORT);
        return tenant;
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long rawPeakHeap() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static void resetPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private record Measured(RunReport report, long millis) {
    }

    private Measured timed(TestTenant tenant, Path export) {
        TenantContext.set(tenant.id());
        long started = System.nanoTime();
        RunReport report = reconciliation.start(new FileSystemResource(export), GENERATED_AT);
        return new Measured(report, (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    @Order(1)
    void twoHundredThousandRowsDiffInUnder60Seconds() throws Exception {
        TestTenant tenant = tenantWithStore(LARGE);
        Path export = writeExport(LARGE, Integer.MAX_VALUE, false);
        System.gc();
        resetPeaks();

        Measured run = timed(tenant, export);

        report("TIMING rows=" + run.report().exportRowCount() + " buckets=" + BUCKETS + " wallMillis=" + run.millis()
                + " rawPeakHeapMb=" + rawPeakHeap() / MB + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / MB
                + " status=" + run.report().status() + " created=" + run.report().createdCount() + " updated=" + run.report().updatedCount()
                + " deactivated=" + run.report().deactivatedCount() + " unchanged=" + run.report().unchangedCount());
        assertEquals(ReconciliationStatus.COMPLETED, run.report().status());
        assertEquals(LARGE, run.report().exportRowCount());
        assertEquals(1000, run.report().createdCount());
        assertEquals(1990, run.report().updatedCount());
        assertEquals(1000, run.report().deactivatedCount());
        assertTrue(run.millis() < 60_000, "took " + run.millis() + " ms");
    }

    private long peakLiveHeap(TestTenant tenant, Path export) throws Exception {
        System.gc();
        long baseline = heapUsed();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong(baseline);
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                System.gc();
                peak.accumulateAndGet(heapUsed(), Math::max);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();
        try {
            timed(tenant, export);
        } finally {
            running.set(false);
            sampler.join();
        }
        return peak.get() - baseline;
    }

    @Test
    @Order(2)
    void peakHeapDoesNotGrowMateriallyBetween20000And200000Rows() throws Exception {
        TestTenant smallTenant = tenantWithStore(SMALL);
        Path smallExport = writeExport(SMALL, Integer.MAX_VALUE, false);
        TestTenant largeTenant = tenantWithStore(LARGE);
        Path largeExport = writeExport(LARGE, Integer.MAX_VALUE, false);

        timed(newTenant(), writeExport(2000, Integer.MAX_VALUE, false));

        long small = peakLiveHeap(smallTenant, smallExport);
        long large = peakLiveHeap(largeTenant, largeExport);

        report("MEMORY liveGrowthAtSmallKb=" + small / 1024 + " (" + SMALL + " rows) liveGrowthAtLargeKb=" + large / 1024 + " (" + LARGE
                + " rows) differenceKb=" + (large - small) / 1024 + " buckets=" + BUCKETS + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / MB);
        assertTrue(large - small < ALLOWED_GROWTH_BYTES,
                "10x the rows must not mean materially more live heap: " + small / MB + " MB against " + large / MB + " MB");
    }

    @Test
    @Order(3)
    void exampleUseCase2AtFullSizeAFileCutOffAt120000Of200000Rows() throws Exception {
        TestTenant tenant = tenantWithStore(LARGE);
        Path cutOff = writeExport(LARGE, 120_000, false);

        Measured run = timed(tenant, cutOff);

        report("CUT_OFF wallMillis=" + run.millis() + " report=" + objectMapper.writeValueAsString(run.report()));
        assertEquals(ReconciliationStatus.AWAITING_APPROVAL, run.report().status());
        assertEquals(120_000, run.report().exportRowCount());
        assertEquals(80_000, run.report().missingCount());
        assertEquals(0, run.report().deactivatedCount());
        assertEquals(LARGE, count(tenant, "ACTIVE"), "nobody was deactivated");
    }

    @Test
    @Order(4)
    void everyRowChangedShowsWhatTheBatchedWritePatternCosts() throws Exception {
        TestTenant tenant = tenantWithStore(LARGE);
        Path export = writeExport(LARGE, Integer.MAX_VALUE, true);

        Measured run = timed(tenant, export);

        report("WORST_CASE rows=" + run.report().exportRowCount() + " updated=" + run.report().updatedCount() + " created=" + run.report().createdCount()
                + " deactivated=" + run.report().deactivatedCount() + " wallMillis=" + run.millis());
        assertEquals(LARGE - LARGE / 200, run.report().updatedCount());
    }
}
