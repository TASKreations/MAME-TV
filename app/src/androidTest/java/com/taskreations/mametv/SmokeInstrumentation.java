package com.taskreations.mametv;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/** Device checks without an external test framework. No copyrighted ROM data. */
public final class SmokeInstrumentation extends Instrumentation {
    private int passed;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void passed(String name) { passed++; Bundle result = new Bundle(); result.putString("stream", "PASS " + name + "\n"); sendStatus(0, result); }
    private void uiCheck(Runnable check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { check.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("UI check failed: " + failure.get(), failure.get());
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        File root = new File(getTargetContext().getCacheDir(), "smoke-" + System.nanoTime());
        try {
            RomStore store = new RomStore(root);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("test.txt")); zip.write("test fixture, not a game".getBytes()); zip.closeEntry();
            }
            File fakeRom = store.importZip(new ByteArrayInputStream(bytes.toByteArray()), "notagame.zip");
            require(store.games().size() == 1, "Android ZIP import was not listed");
            passed("Android storage import");

            NativeEngine engine = new NativeEngine();
            require(engine.prepare(), "Native session already active");
            File system = new File(root, "system"), saves = new File(root, "saves");
            require(system.mkdirs() && saves.mkdirs(), "Native data directories missing");
            try (ImageReader reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)) {
                AtomicReference<String> error = new AtomicReference<>();
                CountDownLatch finished = new CountDownLatch(1);
                Thread core = new Thread(() -> {
                    try { error.set(engine.run(reader.getSurface(), fakeRom.getAbsolutePath(), system.getAbsolutePath(), saves.getAbsolutePath())); }
                    catch (Throwable failure) { error.set("Unexpected Java error: " + failure); }
                    finally { try { engine.closeAudio(); } finally { engine.release(); finished.countDown(); } }
                }, "native-smoke");
                core.start();
                boolean completed = finished.await(20, TimeUnit.SECONDS);
                if (!completed) engine.stop();
                require(completed, "Native invalid-ROM check timed out");
                require(error.get() != null && error.get().contains("Game driver not found"), "Core should report unknown ROM: " + error.get());
                passed("Embedded core loads and rejects unsupported ROM");
            }

            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent); waitForIdleSync();
            Activity screen = activity;
            long focusDeadline = SystemClock.uptimeMillis() + 30000;
            while (!screen.hasWindowFocus() && SystemClock.uptimeMillis() < focusDeadline) SystemClock.sleep(100);
            require(screen.hasWindowFocus(), "Library window did not become foreground");
            uiCheck(() -> {
                View content = screen.findViewById(android.R.id.content);
                require(hasText(content, "MAME-TV"), "Library heading missing");
                require(hasText(content, "Find & download"), "Download action missing");
                View focused = screen.getCurrentFocus();
                require(focused instanceof Button && "Find & download".contentEquals(((Button) focused).getText()), "TV initial focus missing");
            });
            long now = SystemClock.uptimeMillis();
            require(getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0), true), "D-pad press injection failed");
            require(getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0), true), "D-pad release injection failed");
            waitForIdleSync();
            uiCheck(() -> require(screen.getCurrentFocus() instanceof Button && "Import ZIP".contentEquals(((Button) screen.getCurrentFocus()).getText()), "D-pad navigation failed"));
            passed("TV library launch and D-pad focus");

            result.putInt("testsPassed", passed); result.putString("stream", "PASS: " + passed + " device smoke checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putInt("testsPassed", passed); result.putString("stream", "FAIL: " + failure + "\n");
            finish(1, result);
        } finally {
            if (activity != null) { Activity screen = activity; runOnMainSync(screen::finish); }
            delete(root);
        }
    }

    private boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
