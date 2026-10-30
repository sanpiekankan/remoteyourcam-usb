package com.remotecam.app;

import android.graphics.Bitmap;

import com.remoteyourcam.usb.ptp.PtpConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class CameraMediaEntry {
    private static final DateTimeFormatter PTP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    final int handle;
    final int storageId;
    final int objectFormat;
    final String storageLabel;
    final String filename;
    final String captureDate;
    final String modificationDate;
    final int sizeBytes;
    final int width;
    final int height;
    final boolean raw;
    final String extension;
    Bitmap thumbnail;

    CameraMediaEntry(int handle, int storageId, int objectFormat, String storageLabel, String filename,
                     String captureDate, String modificationDate, int sizeBytes, int width, int height,
                     boolean raw, String extension, Bitmap thumbnail) {
        this.handle = handle;
        this.storageId = storageId;
        this.objectFormat = objectFormat;
        this.storageLabel = storageLabel;
        this.filename = filename;
        this.captureDate = captureDate;
        this.modificationDate = modificationDate;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.raw = raw;
        this.extension = extension;
        this.thumbnail = thumbnail;
    }

    String badge() {
        return raw ? "RAW" : formatName();
    }

    String formatName() {
        if (extension != null && !extension.isEmpty()) return extension.toUpperCase(Locale.ROOT);
        String format = PtpConstants.objectFormatToString(objectFormat);
        return format == null || format.isEmpty() ? "IMAGE" : format.replace("Eos", "").replace('_', ' ');
    }

    String subtitle() {
        StringBuilder builder = new StringBuilder();
        builder.append(storageLabel == null || storageLabel.isEmpty() ? "相机存储" : storageLabel);
        if (width > 0 && height > 0) builder.append(" · ").append(width).append("×").append(height);
        if (sizeBytes > 0) builder.append(" · ").append(humanBytes(sizeBytes));
        return builder.toString();
    }

    String detailText() {
        StringBuilder builder = new StringBuilder();
        builder.append(displayName());
        builder.append("\n格式：").append(formatName());
        if (raw) builder.append("（RAW 原始文件）");
        if (width > 0 && height > 0) builder.append("\n尺寸：").append(width).append(" × ").append(height);
        if (sizeBytes > 0) builder.append("\n大小：").append(humanBytes(sizeBytes));
        if (captureDate != null && !captureDate.isEmpty()) builder.append("\n拍摄时间：").append(CameraController.formatPtpDate(captureDate));
        if (modificationDate != null && !modificationDate.isEmpty()) builder.append("\n修改时间：").append(CameraController.formatPtpDate(modificationDate));
        builder.append("\n存储：").append(storageLabel == null || storageLabel.isEmpty() ? "相机存储" : storageLabel);
        builder.append("\nHandle：0x").append(Integer.toHexString(handle));
        return builder.toString();
    }

    String displayName() {
        return filename == null || filename.isEmpty() ? ("对象 #" + handle) : filename;
    }

    boolean canDecodeLargePreview() {
        return !raw && ("jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension));
    }

    String stem() {
        if (filename == null || filename.isEmpty()) return "object_" + handle;
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 ? filename.substring(0, dot) : filename).toLowerCase(Locale.ROOT);
    }

    LocalDateTime captureDateTime() {
        return parseDateTime(captureDate);
    }

    LocalDate captureDay() {
        LocalDateTime dateTime = captureDateTime();
        return dateTime != null ? dateTime.toLocalDate() : null;
    }

    String timeLabel() {
        LocalDateTime dateTime = captureDateTime();
        if (dateTime == null) return "";
        return String.format(Locale.getDefault(), "%02d:%02d", dateTime.getHour(), dateTime.getMinute());
    }

    long sortableEpochMinute() {
        LocalDateTime time = captureDateTime();
        if (time == null) return Long.MIN_VALUE + handle;
        return time.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() / 60;
    }

    static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.length() >= 15 && value.charAt(8) == 'T') return LocalDateTime.parse(value.substring(0, 15), PTP_TIME);
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    static String humanBytes(long value) {
        if (value < 1024) return value + " B";
        String[] units = {"KB", "MB", "GB"};
        double size = value;
        int unit = -1;
        do {
            size /= 1024d;
            unit++;
        } while (size >= 1024d && unit < units.length - 1);
        return String.format(Locale.getDefault(), "%.1f %s", size, units[unit]);
    }
}
