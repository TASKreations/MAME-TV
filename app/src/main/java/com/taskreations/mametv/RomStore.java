package com.taskreations.mametv;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** ROM archives stay zipped: the core resolves members and companion BIOS/parent sets. */
public final class RomStore {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    private final File directory;

    public RomStore(File root) throws IOException {
        directory = new File(root, "roms/mame2003-plus");
        Files.createDirectories(directory.toPath());
    }

    public File directory() { return directory; }

    public List<File> games() {
        File[] files = directory.listFiles(f -> f.isFile() && f.getName().endsWith(".zip"));
        List<File> result = new ArrayList<>();
        if (files != null) Collections.addAll(result, files);
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    public static String filename(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_+\\-]{0,63}\\.[zZ][iI][pP]"))
            throw new IOException("Use the original ROM short name, for example gridlee.zip. Paths and spaces are not allowed.");
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized File importZip(InputStream input, String name) throws IOException {
        File target = new File(directory, filename(name));
        if (target.exists()) throw new IOException("That ROM already exists. Remove it from the library before replacing it.");
        File partial = File.createTempFile("import-", ".part", directory);
        try {
            try (OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import canceled");
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("This version supports ROM ZIPs up to 512 MB.");
                    output.write(buffer, 0, read);
                }
            }
            try (ZipFile zip = new ZipFile(partial)) {
                if (zip.size() == 0 || !zip.stream().anyMatch(e -> !e.isDirectory()))
                    throw new IOException("The ZIP contains no ROM files.");
            }
            // Same-directory rename prevents incomplete downloads appearing in the library.
            Files.move(partial.toPath(), target.toPath());
            return target;
        } finally { Files.deleteIfExists(partial.toPath()); }
    }
}
