package com.taskreations.mametv;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import static org.junit.Assert.*;

public class RomStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private RomStore store;
    @Before public void setup() throws IOException { store = new RomStore(temporary.newFolder()); }
    private byte[] zip(String name) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        return data.toByteArray();
    }
    @Test public void importPreservesArchiveAndNormalizesExtension() throws Exception {
        byte[] bytes = zip("rom.bin");
        File result = store.importZip(new ByteArrayInputStream(bytes), "gridlee.ZIP");
        assertEquals("gridlee.zip", result.getName());
        assertArrayEquals(bytes, Files.readAllBytes(result.toPath()));
        assertEquals(1, store.games().size());
    }
    @Test public void duplicateNeverOverwritesExistingRom() throws Exception {
        File result = store.importZip(new ByteArrayInputStream(zip("original.bin")), "gridlee.zip");
        byte[] original = Files.readAllBytes(result.toPath());
        assertThrows(IOException.class, () -> store.importZip(new ByteArrayInputStream(zip("other.bin")), "gridlee.zip"));
        assertArrayEquals(original, Files.readAllBytes(result.toPath()));
    }
    @Test public void htmlDownloadIsRejectedAndPartialRemoved() {
        assertThrows(IOException.class, () -> store.importZip(new ByteArrayInputStream("<html>not a ROM</html>".getBytes()), "gridlee.zip"));
        assertEquals(0, store.directory().list().length);
    }
    @Test public void interruptedReadDoesNotLeavePartialRom() {
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("Disconnected"); } };
        assertThrows(IOException.class, () -> store.importZip(broken, "gridlee.zip"));
        assertEquals(0, store.directory().list().length);
    }
    @Test public void filenamesCannotEscapeRomFolder() {
        for (String name : new String[]{"../gridlee.zip", "/tmp/gridlee.zip", "a/b.zip", "x\\y.zip", "game.exe", "two words.zip", ".zip"})
            assertThrows(name, IOException.class, () -> RomStore.filename(name));
    }
    @Test public void emptyZipIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new ZipOutputStream(bytes).close();
        assertThrows(IOException.class, () -> store.importZip(new ByteArrayInputStream(bytes.toByteArray()), "empty.zip"));
        assertEquals(0, store.directory().list().length);
    }
    @Test public void downloadRejectsCleartextAndEmbeddedCredentials() throws Exception {
        assertThrows(IOException.class, () -> RomDownloader.checkedUrl("http://example.com/game.zip"));
        assertThrows(IOException.class, () -> RomDownloader.checkedUrl("https://user:password@example.com/game.zip"));
        assertEquals("example.com", RomDownloader.checkedUrl("https://example.com/game.zip").getHost());
    }
}
