package com.aboezzat.receiver;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public final class HistoryMediaDownloader {
    public interface Callback {
        void onFinished(boolean success, String message);
    }

    private HistoryMediaDownloader() {}

    public static void download(Context context, String videoUrl, String audioUrl,
                                String title, Map<String, String> headers,
                                boolean asMp3, Callback callback) {
        final Context app = context.getApplicationContext();
        final String video = videoUrl == null ? "" : videoUrl.trim();
        final String audio = audioUrl == null ? "" : audioUrl.trim();
        if (video.isEmpty() && audio.isEmpty()) {
            callback.onFinished(false, "الرابط غير متاح للتنزيل");
            return;
        }
        if (asMp3 && video.isEmpty() && audio.isEmpty()) {
            callback.onFinished(false, "لا يوجد مسار صوت");
            return;
        }

        File workDir = new File(app.getCacheDir(), "history_downloads");
        if (!workDir.exists() && !workDir.mkdirs()) {
            callback.onFinished(false, "تعذر تجهيز مساحة التنزيل");
            return;
        }
        String base = safeName(title);
        String extension = asMp3 ? ".mp3" : ".mp4";
        File output = new File(workDir, "history_" + System.nanoTime() + extension);
        String command;
        String headerBlock = makeHeaders(headers);

        if (asMp3) {
            String source = !audio.isEmpty() ? audio : video;
            command = "-y " + inputArgs(source, headerBlock)
                    + " -map 0:a:0 -vn -c:a libmp3lame -q:a 2 "
                    + quote(output.getAbsolutePath());
        } else if (!video.isEmpty() && !audio.isEmpty()) {
            command = "-y " + inputArgs(video, headerBlock)
                    + " " + inputArgs(audio, headerBlock)
                    + " -map 0:v:0 -map 1:a:0 -c copy -shortest -movflags +faststart "
                    + quote(output.getAbsolutePath());
        } else {
            String source = video.isEmpty() ? audio : video;
            command = "-y " + inputArgs(source, headerBlock)
                    + " -map 0:v? -map 0:a? -c copy -movflags +faststart "
                    + quote(output.getAbsolutePath());
        }

        FFmpegKit.executeAsync(command, session -> {
            boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                    && output.isFile() && output.length() > 0;
            String message;
            if (ok) {
                try {
                    Uri saved = publish(app, output, base + extension, asMp3);
                    message = saved == null
                            ? "تم التنزيل داخل مجلد التطبيق: " + output.getAbsolutePath()
                            : "تم حفظ الملف في مجلد Download/AboEzzat Receiver";
                } catch (Exception e) {
                    ok = false;
                    message = "فشل حفظ الملف: " + safeMessage(e);
                }
            } else {
                message = "تعذر إنشاء الملف. قد يكون المصدر محميًا أو غير متوافق مع الدمج.";
                String details = session.getFailStackTrace();
                if (details != null && !details.trim().isEmpty()) {
                    android.util.Log.w("ReceiverHistoryDownload", details);
                }
            }
            if (!ok) output.delete();
            callback.onFinished(ok, message);
        });
    }

    private static String inputArgs(String url, String headers) {
        String args = "";
        if (!headers.isEmpty()) args = "-headers " + quote(headers) + " ";
        return args + "-i " + quote(url);
    }

    private static String makeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if (!(key.equals("user-agent") || key.equals("referer") || key.equals("origin")
                    || key.equals("accept") || key.equals("accept-language"))) continue;
            String value = entry.getValue();
            if (value.length() > 2048 || value.contains("\r") || value.contains("\n")) continue;
            out.append(entry.getKey()).append(": ").append(value).append("\r\n");
        }
        return out.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeName(String value) {
        String name = value == null ? "AboEzzat_Media" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", " ");
        if (name.isEmpty()) name = "AboEzzat_Media";
        if (name.length() > 80) name = name.substring(0, 80).trim();
        return name;
    }

    private static Uri publish(Context context, File source, String name, boolean audio)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mpeg" : "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/AboEzzat Receiver");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri item = context.getContentResolver().insert(collection, values);
            if (item == null) throw new IllegalStateException("MediaStore insert returned null");
            boolean complete = false;
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(item, "w")) {
                if (out == null) throw new IllegalStateException("Cannot open destination");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
                complete = true;
            } finally {
                if (complete) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    context.getContentResolver().update(item, done, null, null);
                } else {
                    context.getContentResolver().delete(item, null, null);
                }
            }
            source.delete();
            return item;
        }
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) downloads = context.getFilesDir();
        if (!downloads.exists() && !downloads.mkdirs())
            throw new IllegalStateException("Cannot create downloads folder");
        File target = new File(downloads, name);
        if (!source.renameTo(target)) {
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            source.delete();
        }
        return null;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
