package lagfinder.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import lagfinder.LagFinder;
import lagfinder.core.Report;
import lagfinder.core.ReportFiles;

/** The reports this client kept: the server's reports it received, saved under {@code lagfinder/reports}. */
public final class ReportStore {

    private ReportStore() {}

    public static File folder() {
        return new File(new File(Minecraft.getInstance().gameDirectory, "lagfinder"), "reports");
    }

    public static void save(Report report, byte[] data) {
        Thread writer = new Thread(() -> {
            try {
                ReportFiles.save(folder(), report, data);
            } catch (IOException ex) {
                LagFinder.LOG.warn("Could not save the report", ex);
            }
        }, "LagFinder report saver");
        writer.setDaemon(true);
        writer.start();
    }

    public static List<ReportFiles.Saved> list() {
        try {
            return ReportFiles.list(folder());
        } catch (IOException ex) {
            LagFinder.LOG.warn("Could not list reports", ex);
            return new ArrayList<>();
        }
    }

    public static Report load(String name) {
        try {
            return ReportFiles.load(folder(), name);
        } catch (IOException | RuntimeException ex) {
            LagFinder.LOG.warn("Could not read the report {}", name, ex);
            return null;
        }
    }

    public static boolean delete(ReportFiles.Saved saved) {
        try {
            return ReportFiles.delete(folder(), saved.name);
        } catch (IOException ex) {
            LagFinder.LOG.warn("Could not delete the report {}", saved.name, ex);
            return false;
        }
    }
}
