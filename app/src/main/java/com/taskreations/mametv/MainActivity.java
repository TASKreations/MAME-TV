package com.taskreations.mametv;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int IMPORT_ROM = 40;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RomStore store;
    private LinearLayout library;
    private TextView status;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { store = new RomStore(getFilesDir()); }
        catch (IOException failure) { error(failure.getMessage()); return; }
        LinearLayout root = column();
        root.setPadding(dp(48), dp(28), dp(48), dp(24));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff101b2e, 0xff080e19}));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        TextView eyebrow = text("TASKREATIONS  /  ARCADE AT HOME", 13, 0xff57e3c0);
        root.addView(eyebrow);
        TextView title = text("MAME-TV", 42, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(title);
        root.addView(text("Your arcade. Your library. On the big screen.", 18, 0xffbac8dc));
        root.addView(text("Built-in MAME2003-Plus · ROM set based on 0.78 · Noncommercial", 13, 0xff8c9bb0));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setPadding(0, dp(16), 0, dp(12));
        actions.addView(button("Find & download", v -> findRoms()));
        actions.addView(button("Import ZIP", v -> importRom()));
        actions.addView(button("Help", v -> help()));
        root.addView(actions);
        status = text("", 15, 0xff57e3c0); root.addView(status);
        TextView heading = text("YOUR ROM SETS", 16, Color.WHITE);
        heading.setPadding(0, dp(18), 0, dp(8)); root.addView(heading);
        library = column(); root.addView(library);
        root.addView(text("Gamepad: Select inserts a coin · Start begins · Back returns here", 13, 0xff8c9bb0));
        refresh();
        actions.getChildAt(0).requestFocus();
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(17);
        button.setFocusable(true); button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(60)); params.setMarginEnd(dp(10)); button.setLayoutParams(params);
        return button;
    }

    @Override protected void onResume() { super.onResume(); if (library != null) refresh(); }
    private void refresh() {
        library.removeAllViews();
        List<File> games = store.games();
        if (games.isEmpty()) library.addView(text("No ROMs yet. Find an authorized download or import a ZIP from USB/storage.", 19, 0xffbac8dc));
        for (File game : games) {
            Button row = button("▶  " + game.getName() + "    ·    " + Math.max(1, game.length() / 1024) + " KB", v -> gameActions(game));
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(64))); library.addView(row);
        }
        if (!busy) status.setText(games.size() + " ROM ZIPs · Includes any imported BIOS/parent sets");
    }

    private void gameActions(File game) {
        new AlertDialog.Builder(this).setTitle(game.getName())
            .setItems(new String[]{"Play with built-in emulator", "Remove from library"}, (d, item) -> {
                if (item == 0) startActivity(new Intent(this, PlayerActivity.class).putExtra("rom", game.getAbsolutePath()));
                else new AlertDialog.Builder(this).setTitle("Remove " + game.getName() + "?")
                    .setMessage("This deletes the imported copy. Saved emulator data is kept.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                        if (!game.delete()) error("Could not remove this ROM."); refresh();
                    }).show();
            }).show();
    }

    private void findRoms() {
        new AlertDialog.Builder(this).setTitle("Find & download ROMs")
            .setItems(new String[]{"Gridlee · Authorized free download", "Browse MAME's authorized ROM catalog", "Download from an HTTPS URL"}, (d, item) -> {
                if (item == 0) new AlertDialog.Builder(this).setTitle("Gridlee · Videa (1983)")
                    .setMessage("MAME's official site offers Gridlee with the rights holders' permission. The ZIP will be stored in the MAME2003-Plus ROM folder. Gridlee originally uses a trackball; joystick play may differ. No ROMs are bundled with this app.")
                    .setNegativeButton("Cancel", null).setNeutralButton("Source & terms", (a, b) -> browse("https://www.mamedev.org/roms/gridlee/"))
                    .setPositiveButton("Download", (a, b) -> download("https://www.mamedev.org/roms/gridlee/gridlee.zip", "gridlee.zip")).show();
                else if (item == 1) browse("https://www.mamedev.org/roms/");
                else downloadForm();
            }).show();
    }

    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException missing) { error("No web browser is installed on this TV. Use the direct download option or import a ZIP from USB."); }
    }

    private void downloadForm() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.addView(text("Download only ROMs you are entitled to use. Match the MAME2003-Plus set and keep the original filename.", 16, Color.WHITE));
        EditText url = new EditText(this); url.setHint("https://…/gridlee.zip");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); url.setSingleLine(true); form.addView(url);
        EditText name = new EditText(this); name.setHint("Original filename, e.g. gridlee.zip"); name.setSingleLine(true); form.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Download a ROM ZIP").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Download", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String address = url.getText().toString().trim(), filename = name.getText().toString().trim();
                RomDownloader.checkedUrl(address); RomStore.filename(filename);
                dialog.dismiss(); download(address, filename);
            } catch (IOException failure) { url.setError(failure.getMessage()); }
        })); dialog.show();
    }

    private void download(String url, String name) { work("Downloading " + name + "…", () -> RomDownloader.download(store, url, name)); }

    @SuppressWarnings("deprecation")
    private void importRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, IMPORT_ROM); }
        catch (ActivityNotFoundException missing) { error("This TV has no document picker. Use an HTTPS download, or install a document provider for USB imports."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_ROM || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        work("Importing ROM…", () -> {
            String name = null;
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open the selected file.");
                return store.importZip(input, name);
            }
        });
    }

    private void work(String message, Callable<File> operation) {
        if (busy) { error("A download or import is already running. Please wait."); return; }
        busy = true; status.setText(message);
        io.submit(() -> {
            String failure = null; File imported = null;
            try { imported = operation.call(); } catch (Exception error) { failure = error.getMessage(); }
            String problem = failure; File completed = imported;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false; refresh();
                if (problem != null) error(problem);
                else if (completed != null) status.setText("Added " + completed.getName() + ". Select it to play.");
            });
        });
    }

    private void help() {
        new AlertDialog.Builder(this).setTitle("Your TV arcade")
            .setMessage("1. Pair a Bluetooth or USB gamepad. The remote can navigate the library.\n\n2. Use MAME2003-Plus compatible ROM ZIPs (based on 0.78). Keep ZIPs intact with their original short names. Import parent and BIOS ZIPs into the same library. A folder name alone does not make a ROM compatible.\n\n3. Select = coin, Start = play, D-pad/left stick = movement, A/B/X/Y/L/R = action buttons, Back = exit. On remotes, Menu = coin, Play/Pause = Start, OK = action. Native game notices may require Left then Right.\n\nROMs: internal app storage / roms/mame2003-plus. Saves: internal app storage / saves. Uninstalling clears these files. This first version supports one player and ZIP-based sets; CHD import, remapping, and save-state UI are not implemented.\n\nMAME2003-Plus is noncommercial; this is an unofficial derivative. Download ROMs only when legally entitled. The core and its notices remain unchanged.")
            .setPositiveButton("OK", null).setNeutralButton("Core source", (d, w) -> browse("https://github.com/libretro/mame2003-plus-libretro/tree/ca185b19ad27d531a91c92fa456aabd951004fb8"))
            .setNegativeButton("License", (d, w) -> {
                try (InputStream input = getAssets().open("MAME2003-PLUS-LICENSE.md");
                     InputStream frontend = getAssets().open("FRONTEND-LICENSE.txt")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    bytes.write("\n\nFrontend source: https://github.com/TASKreations/MAME-TV\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    while ((count = frontend.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    new AlertDialog.Builder(this).setTitle("MAME2003-Plus license").setMessage(bytes.toString("UTF-8"))
                        .setPositiveButton("OK", null).show();
                } catch (IOException failure) { error(failure.getMessage()); }
            }).show();
    }
    private void error(String message) { if (!isDestroyed()) new AlertDialog.Builder(this).setTitle("MAME-TV").setMessage(message == null ? "Operation failed." : message).setPositiveButton("OK", null).show(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
