package com.taskreations.mametv;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.Toast;
import java.io.File;

public final class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private final NativeEngine engine = new NativeEngine();
    private Thread worker;
    private int keys, axes;
    private boolean leaving;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        SurfaceView surface = new SurfaceView(this);
        setContentView(surface);
        surface.getHolder().addCallback(this);
        Toast.makeText(this, "Select = coin · Start = play · Back = exit. A gamepad is recommended.", Toast.LENGTH_LONG).show();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (leaving) return;
        if (!engine.prepare()) { showError("The previous game is still closing. Return to the library and try again."); return; }
        String path = getIntent().getStringExtra("rom");
        File system = new File(getFilesDir(), "system"), save = new File(getFilesDir(), "saves");
        if (!system.isDirectory() && !system.mkdirs() || !save.isDirectory() && !save.mkdirs()) {
            engine.release(); showError("Could not create emulator data folders."); return;
        }
        worker = new Thread(() -> {
            String error;
            try { error = engine.run(holder.getSurface(), path, system.getAbsolutePath(), save.getAbsolutePath()); }
            catch (RuntimeException failure) { error = "Emulator failed: " + failure.getMessage(); }
            finally { try { engine.closeAudio(); } finally { engine.release(); } }
            final String result = error;
            runOnUiThread(() -> { if (!leaving) { if (result != null) showError(result); else finish(); } });
        }, "mame-emulation");
        worker.start();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("Unable to play").setMessage(message)
            .setPositiveButton("Back to library", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        engine.stop();
        // The worker owns a native-window reference until it exits. Never block the
        // TV's UI thread while a large ROM is still loading or releasing memory.
        worker = null;
    }
    @Override protected void onResume() { super.onResume(); keys = axes = 0; engine.pause(false); }
    @Override protected void onPause() { engine.pause(true); super.onPause(); }
    @Override protected void onStop() { leaving = true; engine.stop(); super.onStop(); finish(); }

    // libretro joypad IDs: B,Y,Select,Start,Up,Down,Left,Right,A,X,L,R,L2,R2,L3,R3.
    private int button(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return 8;
            case KeyEvent.KEYCODE_BUTTON_B: return 0;
            case KeyEvent.KEYCODE_BUTTON_X: return 9;
            case KeyEvent.KEYCODE_BUTTON_Y: return 1;
            case KeyEvent.KEYCODE_BUTTON_L1: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1: return 11;
            case KeyEvent.KEYCODE_BUTTON_L2: return 12;
            case KeyEvent.KEYCODE_BUTTON_R2: return 13;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_MENU: return 2;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 3;
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            default: return -1;
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int id = button(event.getKeyCode());
        if (id < 0) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) keys |= 1 << id;
        else if (event.getAction() == KeyEvent.ACTION_UP) keys &= ~(1 << id);
        engine.buttons(keys | axes);
        return true;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            float x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            axes = (x < -.4 ? 1 << 6 : x > .4 ? 1 << 7 : 0) | (y < -.4 ? 1 << 4 : y > .4 ? 1 << 5 : 0);
            engine.buttons(keys | axes); return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
