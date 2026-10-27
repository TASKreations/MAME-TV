package com.taskreations.mametv;

import android.media.*;
import android.view.Surface;

final class NativeEngine {
    static { System.loadLibrary("mametv"); }
    private static NativeEngine active;
    private AudioTrack track;
    private native void nativePrepare();
    native String run(Surface surface, String rom, String system, String save);
    private native void nativeStop();
    private native void nativePause(boolean value);
    private native void nativeButtons(int mask);

    boolean prepare() {
        synchronized (NativeEngine.class) {
            if (active != null) return false;
            active = this; nativePrepare(); return true;
        }
    }
    void stop() { synchronized (NativeEngine.class) { if (active == this) nativeStop(); } }
    void pause(boolean value) { synchronized (NativeEngine.class) { if (active == this) nativePause(value); } }
    void buttons(int mask) { synchronized (NativeEngine.class) { if (active == this) nativeButtons(mask); } }
    void release() { synchronized (NativeEngine.class) { if (active == this) active = null; } }

    // Called only on the emulation thread, including cleanup.
    @SuppressWarnings("unused")
    void configureAudio(int sampleRate) {
        closeAudio();
        int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Unsupported audio sample rate: " + sampleRate);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minimum * 4).setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
    }

    @SuppressWarnings("unused")
    void audio(short[] samples) {
        if (track != null) {
            int offset = 0;
            while (offset < samples.length) {
                int count = track.write(samples, offset, samples.length - offset, AudioTrack.WRITE_BLOCKING);
                if (count < 0) throw new IllegalStateException("Audio output failed: " + count);
                if (count == 0) break;
                offset += count;
            }
        }
    }

    void closeAudio() {
        if (track != null) { track.stop(); track.release(); track = null; }
    }
}
