package lagfinder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ReportFiles {

    public static final int KEEP = 50;
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final int MAX_NAME = 96;
    private static final Pattern NAME = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}(?:-[0-9]{3})?_(?:TPS|FPS|BOTH)(?:-(?:LAG|METHODS|OBJECTS|ALLOCATIONS|STEADY))?\\.lfr");

    public static final class Saved {

        public final String name;
        public final ScanMode mode;
        public final ScanSide side;
        public final ScanFocus focus;
        public final long time, size;

        private Saved(String name, ScanSide side, ScanFocus focus, long time, long size) {
            this.name = name;
            this.side = side;
            this.mode = side == ScanSide.CLIENT ? ScanMode.FPS : ScanMode.TPS;
            this.focus = focus;
            this.time = time;
            this.size = size;
        }
    }

    private ReportFiles() {}

    public static Saved describe(String name, long size) {
        if (name == null || name.length() > MAX_NAME
            || !NAME.matcher(name)
                .matches()
            || size < 1
            || size > MAX_BYTES) {
            return null;
        }
        int split = name.lastIndexOf('_');
        String date = name.substring(0, split);
        SimpleDateFormat format = format(date.length() == 23);
        ParsePosition position = new ParsePosition(0);
        Date time = format.parse(date, position);
        if (time == null || position.getIndex() != date.length()) {
            return null;
        }
        String kind = name.substring(split + 1, name.length() - 4);
        int dash = kind.indexOf('-');
        String where = dash < 0 ? kind : kind.substring(0, dash);
        ScanSide side = "BOTH".equals(where) ? ScanSide.BOTH : ScanSide.of(ScanMode.valueOf(where));
        ScanFocus focus = dash < 0 ? ScanFocus.LAG : ScanFocus.valueOf(kind.substring(dash + 1));
        return new Saved(name, side, focus, time.getTime(), size);
    }

    public static Saved describe(String name, long size, long time) {
        Saved saved = describe(name, size);
        return saved == null ? null : new Saved(name, saved.side, saved.focus, time, size);
    }

    private static SimpleDateFormat format(boolean millis) {
        SimpleDateFormat format = new SimpleDateFormat(
            millis ? "yyyy-MM-dd_HH-mm-ss-SSS" : "yyyy-MM-dd_HH-mm-ss",
            Locale.ROOT);
        format.setLenient(false);
        return format;
    }

    private static Path directory(File folder) throws IOException {
        Path path = folder.toPath()
            .toAbsolutePath()
            .normalize();
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())) {
            throw new IOException("Report directory is a symbolic link");
        }
        return path;
    }

    public static List<Saved> list(File folder) throws IOException {
        List<Saved> saved = new ArrayList<>();
        Path path = directory(folder);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return saved;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.lfr")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Saved entry = describe(
                        file.getFileName()
                            .toString(),
                        Files.size(file));
                    if (entry != null) {
                        saved.add(entry);
                    }
                } catch (IOException ignored) {}
            }
        }
        saved.sort((a, b) -> {
            int order = Long.compare(b.time, a.time);
            return order != 0 ? order : b.name.compareTo(a.name);
        });
        return saved;
    }

    public static synchronized void save(File folder, Report report, byte[] data) throws IOException {
        if (data == null || data.length < 1 || data.length > MAX_BYTES) {
            throw new IOException("Report exceeds the storage limit");
        }
        Path directory = directory(folder);
        Files.createDirectories(directory);
        String where = report.bothSides ? ScanSide.BOTH.name() : report.mode.name();
        String kind = where + (report.focus == ScanFocus.LAG ? "" : "-" + report.focus.name());
        Path target = null;
        for (int i = 0; i < 1000; i++) {
            String name = format(true).format(new Date(report.startedAt + i)) + "_" + kind + ".lfr";
            Path candidate = directory.resolve(name);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            throw new IOException("No free report filename");
        }
        Path temporary = Files.createTempFile(directory, ".report-", ".tmp");
        try {
            Files.write(temporary, data);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        List<Saved> saved = list(folder);
        for (int i = KEEP; i < saved.size(); i++) {
            delete(folder, saved.get(i).name);
        }
    }

    private static Path resolve(File folder, String name) throws IOException {
        if (describe(name, 1) == null) {
            throw new IOException("Invalid report filename");
        }
        Path path = directory(folder).resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Report is missing or is not a regular file");
        }
        return path;
    }

    public static byte[] bytes(File folder, String name) throws IOException {
        Path path = resolve(folder, name);
        long size = Files.size(path);
        if (size < 1 || size > MAX_BYTES) {
            throw new IOException("Report exceeds the transfer limit");
        }
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_BYTES - count) {
                    throw new IOException("Report exceeds the transfer limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static Report load(File folder, String name) throws IOException {
        return Report.read(new ByteArrayInputStream(bytes(folder, name)));
    }

    public static boolean delete(File folder, String name) throws IOException {
        if (describe(name, 1) == null) {
            return false;
        }
        return Files.deleteIfExists(directory(folder).resolve(name));
    }
}
