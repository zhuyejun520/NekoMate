package com.nekocyte.companion;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** 16kHz 单声道 PCM WAV 录音，兼容 GLM-ASR 等只接受 wav/mp3 的语音转写接口。 */
public class VoiceRecorder {
    private static final int SAMPLE_RATE = 16000;

    public static class Result {
        public final File file;
        public final int seconds;

        Result(File file, int seconds) {
            this.file = file;
            this.seconds = seconds;
        }
    }

    private AudioRecord recorder;
    private Thread thread;
    private ByteArrayOutputStream pcm;
    private volatile boolean recording;
    private long startedAt;

    public boolean start(Context context) {
        cancel();
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(min, 4096);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                cancel();
                return false;
            }
            pcm = new ByteArrayOutputStream();
            recording = true;
            startedAt = System.currentTimeMillis();
            recorder.startRecording();
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[4096];
                    while (recording) {
                        int n = recorder.read(buffer, 0, buffer.length);
                        if (n > 0) pcm.write(buffer, 0, n);
                    }
                }
            });
            thread.start();
            return true;
        } catch (Exception e) {
            DebugLog.i("voice", "start failed: " + e.getMessage());
            cancel();
            return false;
        }
    }

    public Result stop() {
        if (!recording || recorder == null) return null;
        recording = false;
        try {
            if (thread != null) thread.join(500);
        } catch (Exception ignored) {
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
        thread = null;
        byte[] data = pcm == null ? new byte[0] : pcm.toByteArray();
        pcm = null;
        if (data.length < 32000) return null;
        try {
            File dir = new File(App.I.getCacheDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "voice_" + System.currentTimeMillis() + ".wav");
            FileOutputStream out = new FileOutputStream(file);
            writeWav(out, data);
            out.close();
            int seconds = Math.max(1, Math.round(data.length / (float) (SAMPLE_RATE * 2)));
            DebugLog.i("voice", "recorded bytes=" + file.length() + " seconds=" + seconds);
            return new Result(file, seconds);
        } catch (Exception e) {
            DebugLog.i("voice", "save failed: " + e.getMessage());
            return null;
        }
    }

    private void writeWav(FileOutputStream out, byte[] pcm) throws Exception {
        int dataLen = pcm.length;
        int byteRate = SAMPLE_RATE * 2;
        out.write(new byte[]{'R', 'I', 'F', 'F'});
        writeInt(out, 36 + dataLen);
        out.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, 1);
        writeInt(out, SAMPLE_RATE);
        writeInt(out, byteRate);
        writeShort(out, 2);
        writeShort(out, 16);
        out.write(new byte[]{'d', 'a', 't', 'a'});
        writeInt(out, dataLen);
        out.write(pcm);
    }

    private void writeInt(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private void writeShort(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    public void cancel() {
        recording = false;
        if (thread != null) {
            try {
                thread.join(300);
            } catch (Exception ignored) {
            }
            thread = null;
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        pcm = null;
    }

    public boolean isRecording() {
        return recording;
    }
}
