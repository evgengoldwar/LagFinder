package lagfinder.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lagfinder.LagFinder;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;
import lagfinder.core.ScanMode;
import lagfinder.network.Net;

public final class ClientReports {

    public static final ClientReports INSTANCE = new ClientReports();

    private List<ReportFiles.Saved> saved = Collections.emptyList();
    private int sequence, request, generation, revision;
    private int nextPart, totalParts;
    private long requestedAt, expectedSize;
    private boolean loading, opening, reading;
    private ByteArrayOutputStream bytes;
    private Report ready;
    private String error = "";

    private ClientReports() {}

    public List<ReportFiles.Saved> list() {
        return saved;
    }

    public int revision() {
        return revision;
    }

    public boolean loading() {
        return loading;
    }

    public String error() {
        return error;
    }

    public Report takeReport() {
        Report report = ready;
        ready = null;
        return report;
    }

    public void listReports() {
        start(Net.ArchiveRequest.LIST, "", 0);
    }

    public void open(ReportFiles.Saved saved) {
        start(Net.ArchiveRequest.OPEN, saved.name, saved.size);
    }

    private void start(int action, String name, long size) {
        if (loading || !ClientScanner.INSTANCE.serverArchiveAvailable()) {
            return;
        }
        sequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
        request = sequence;
        loading = true;
        opening = action == Net.ArchiveRequest.OPEN;
        reading = false;
        nextPart = totalParts = 0;
        expectedSize = size;
        requestedAt = System.currentTimeMillis();
        bytes = null;
        ready = null;
        error = "";
        Net.sendToServer(new Net.ArchiveRequest(request, action, name));
    }

    public void onCatalog(Net.ArchiveCatalog catalog) {
        ClientScanner.INSTANCE.schedule(() -> {
            if (!loading || catalog.id != request) {
                return;
            }
            if (catalog.status != Net.ArchiveCatalog.OK) {
                fail(
                    catalog.status == Net.ArchiveCatalog.DENIED ? "lagfinder.logs.denied"
                        : catalog.status == Net.ArchiveCatalog.BUSY ? "lagfinder.logs.busy"
                            : "lagfinder.logs.unreadable");
                if (catalog.status == Net.ArchiveCatalog.DENIED) {
                    saved = Collections.emptyList();
                    revision++;
                }
            } else if (!opening) {
                saved = Collections.unmodifiableList(new ArrayList<>(catalog.saved));
                loading = false;
                revision++;
            }
        });
    }

    public void onPart(Net.ReportPart part) {
        ClientScanner.INSTANCE.schedule(() -> {
            if (!loading || !opening || reading || part.id != request || !part.valid()) {
                return;
            }
            if (part.index != nextPart || totalParts != 0 && totalParts != part.total) {
                fail("lagfinder.logs.unreadable");
                return;
            }
            if (bytes == null) {
                bytes = new ByteArrayOutputStream();
                totalParts = part.total;
            }
            if (bytes.size() > ReportFiles.MAX_BYTES - part.data.length
                || bytes.size() + part.data.length > expectedSize) {
                fail("lagfinder.logs.unreadable");
                return;
            }
            bytes.write(part.data, 0, part.data.length);
            nextPart++;
            if (nextPart != totalParts) {
                return;
            }
            if (bytes.size() != expectedSize) {
                fail("lagfinder.logs.unreadable");
                return;
            }
            byte[] data = bytes.toByteArray();
            bytes = null;
            reading = true;
            int made = generation, id = request;
            Thread reader = new Thread(() -> {
                Report report = null;
                try {
                    report = Report.read(new ByteArrayInputStream(data));
                    if (report.mode != ScanMode.TPS) {
                        throw new IOException("Archive file is not a server report");
                    }
                } catch (IOException | RuntimeException ex) {
                    LagFinder.LOG.warn("Could not read a shared report", ex);
                    report = null;
                }
                Report result = report;
                ClientScanner.INSTANCE.schedule(() -> {
                    if (made != generation || id != request || !loading) {
                        return;
                    }
                    loading = reading = false;
                    if (result == null || !ClientScanner.INSTANCE.serverArchiveAvailable()) {
                        fail("lagfinder.logs.unreadable");
                    } else {
                        ready = result;
                    }
                });
            }, "LagFinder shared report reader");
            reader.setDaemon(true);
            reader.start();
        });
    }

    private void fail(String key) {
        loading = reading = false;
        bytes = null;
        ready = null;
        error = key;
    }

    public void tick() {
        if (loading && System.currentTimeMillis() - requestedAt > (opening ? 120_000 : 15_000)) {
            fail("lagfinder.logs.timeout");
        }
        if (!ClientScanner.INSTANCE.serverArchiveAvailable() && (!saved.isEmpty() || loading || ready != null)) {
            reset();
        }
    }

    public void reset() {
        generation++;
        saved = Collections.emptyList();
        loading = reading = false;
        bytes = null;
        ready = null;
        error = "";
        revision++;
    }
}
