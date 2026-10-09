package LagFinder.client;

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

import LagFinder.LagFinder;
import LagFinder.core.Report;
import LagFinder.core.ScanMode;

/** Reports saved in {@code .minecraft/lagfinder/reports}, the newest few kept, to open again later. */
public final class ReportStore {

    private static final int KEEP = 20;
    private static final String SUFFIX = ".lfr";

    /** A saved report: what its file name tells, read without opening it. */
    public static final class Saved {

        public final File file;
        public final ScanMode mode;
        public final long time;

        Saved(File file, ScanMode mode, long time) {
            this.file = file;
            this.mode = mode;
            this.time = time;
        }
    }

    private ReportStore() {}

    private static File folder() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "lagfinder"), "reports");
    }

    private static SimpleDateFormat format() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    }

    /** Saves the report (as received) on another thread, and drops the oldest ones. */
    public static void save(Report report, byte[] data) {
        String name = format().format(new Date(report.startedAt)) + "_" + report.mode.name() + SUFFIX;
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
        Arrays.sort(files, (a, b) -> b.getName()
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
                ScanMode mode = ScanMode.valueOf(base.substring(split + 1));
                long time = format.parse(base.substring(0, split))
                    .getTime();
                list.add(new Saved(file, mode, time));
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
}
