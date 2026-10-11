package lagfinder.client;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;

import lagfinder.LagFinder;
import lagfinder.core.Report;
import lagfinder.core.ScanFocus;
import lagfinder.core.ScanMode;
import lagfinder.core.ScanSide;

/**
 * Reports saved in {@code .minecraft/lagfinder/reports}, the newest few kept, to open again later or delete. A file is
 * named after when the scan started, its side and, unless it was a lag scan, what it was for:
 * {@code 2026-10-09_18-30-00_TPS-METHODS.lfr}, or {@code BOTH-METHODS} when both sides were scanned and added up.
 */
public final class ReportStore {

    private static final int KEEP = 50;
    private static final String SUFFIX = ".lfr";

    /** A saved report: what its file name tells, read without opening it. */
    public static final class Saved {

        public final File file;
        public final ScanMode mode;
        public final ScanSide side;
        public final ScanFocus focus;
        public final long time;
        /** Size of the file (bytes). */
        public final long size;

        Saved(File file, ScanSide side, ScanFocus focus, long time) {
            this.file = file;
            this.side = side;
            this.mode = side == ScanSide.CLIENT ? ScanMode.FPS : ScanMode.TPS;
            this.focus = focus;
            this.time = time;
            this.size = file.length();
        }
    }

    private ReportStore() {}

    public static File folder() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "lagfinder"), "reports");
    }

    private static SimpleDateFormat format() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    }

    /** Saves the report (as received) on another thread, and drops the oldest ones. */
    public static void save(Report report, byte[] data) {
        String where = report.bothSides ? ScanSide.BOTH.name() : report.mode.name();
        String kind = where + (report.focus == ScanFocus.LAG ? "" : "-" + report.focus.name());
        String name = format().format(new Date(report.startedAt)) + "_" + kind + SUFFIX;
        Thread writer = new Thread(() -> {
            File folder = folder();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            try (OutputStream out = new FileOutputStream(new File(folder, name))) {
                out.write(data);
            } catch (Throwable t) {
                LagFinder.LOG.warn("Could not save the report", t);
            }
            List<Saved> saved = list();
            for (int i = KEEP; i < saved.size(); i++) {
                if (!saved.get(i).file.delete()) {
                    LagFinder.LOG.warn("Could not delete the old report {}", saved.get(i).file);
                }
            }
        }, "LagFinder report saver");
        writer.setDaemon(true);
        writer.start();
    }

    /** The saved reports, newest first. */
    public static List<Saved> list() {
        List<Saved> list = new ArrayList<>();
        File[] files = folder().listFiles((dir, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            return list;
        }
        Arrays.sort(
            files,
            (a, b) -> b.getName()
                .compareTo(a.getName()));
        SimpleDateFormat format = format();
        for (File file : files) {
            String name = file.getName();
            String base = name.substring(0, name.length() - SUFFIX.length());
            int split = base.lastIndexOf('_');
            if (split < 0) {
                continue;
            }
            try {
                String kind = base.substring(split + 1);
                int dash = kind.indexOf('-');
                String where = dash < 0 ? kind : kind.substring(0, dash);
                ScanSide side = ScanSide.BOTH.name()
                    .equals(where) ? ScanSide.BOTH : ScanSide.of(ScanMode.valueOf(where));
                ScanFocus focus = dash < 0 ? ScanFocus.LAG : ScanFocus.valueOf(kind.substring(dash + 1));
                long time = format.parse(base.substring(0, split))
                    .getTime();
                list.add(new Saved(file, side, focus, time));
            } catch (Exception ignored) {
                // Not one of ours.
            }
        }
        return list;
    }

    public static Report load(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return Report.read(in);
        } catch (Throwable t) {
            LagFinder.LOG.warn("Could not read the report {}", file, t);
            return null;
        }
    }

    /** Deletes the saved report; whether it is gone. */
    public static boolean delete(Saved saved) {
        if (saved.file.delete() || !saved.file.exists()) {
            return true;
        }
        LagFinder.LOG.warn("Could not delete the report {}", saved.file);
        return false;
    }
}
