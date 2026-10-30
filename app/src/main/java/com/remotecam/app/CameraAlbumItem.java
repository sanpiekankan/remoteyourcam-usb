package com.remotecam.app;

import android.graphics.Bitmap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class CameraAlbumItem {
    final String id;
    final List<CameraMediaEntry> assets;
    final LocalDateTime shotTime;
    final LocalDate day;

    private CameraAlbumItem(String id, List<CameraMediaEntry> assets, LocalDateTime shotTime, LocalDate day) {
        this.id = id;
        this.assets = assets;
        this.shotTime = shotTime;
        this.day = day;
    }

    static List<CameraAlbumItem> mergePairs(List<CameraMediaEntry> entries) {
        List<CameraMediaEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing((CameraMediaEntry entry) -> entry.captureDateTime(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(entry -> -entry.handle));

        java.util.LinkedHashMap<String, List<CameraMediaEntry>> grouped = new java.util.LinkedHashMap<>();
        for (CameraMediaEntry entry : sorted) {
            String key = pairKey(entry);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        List<CameraAlbumItem> items = new ArrayList<>();
        for (List<CameraMediaEntry> group : grouped.values()) {
            group.sort(Comparator
                    .comparing((CameraMediaEntry entry) -> entry.raw)
                    .thenComparing(entry -> entry.extension == null ? "" : entry.extension)
                    .thenComparingInt(entry -> entry.handle));
            CameraMediaEntry anchor = previewEntry(group);
            LocalDateTime shotTime = anchor.captureDateTime();
            LocalDate day = shotTime != null ? shotTime.toLocalDate() : null;
            String id = anchor.storageId + ":" + anchor.stem() + ":" + (day == null ? anchor.handle : day.toString());
            items.add(new CameraAlbumItem(id, group, shotTime, day));
        }
        items.sort(Comparator
                .comparing((CameraAlbumItem item) -> item.shotTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.id));
        return items;
    }

    private static String pairKey(CameraMediaEntry entry) {
        LocalDate day = entry.captureDay();
        return entry.storageId + "|" + entry.stem() + "|" + (day == null ? "unknown" : day.toString());
    }

    private static CameraMediaEntry previewEntry(List<CameraMediaEntry> group) {
        for (CameraMediaEntry entry : group) if (!entry.raw) return entry;
        return group.isEmpty() ? null : group.get(0);
    }

    CameraMediaEntry cover() {
        return previewEntry(assets);
    }

    CameraMediaEntry jpegAsset() {
        for (CameraMediaEntry entry : assets) if (!entry.raw) return entry;
        return null;
    }

    CameraMediaEntry rawAsset() {
        for (CameraMediaEntry entry : assets) if (entry.raw) return entry;
        return null;
    }

    boolean hasRaw() { return rawAsset() != null; }
    boolean hasJpeg() { return jpegAsset() != null; }

    String title() {
        CameraMediaEntry cover = cover();
        return cover == null ? "未命名照片" : cover.stem().toUpperCase(Locale.ROOT);
    }

    String badge() {
        if (hasJpeg() && hasRaw()) return "JPEG + RAW";
        if (hasRaw()) return "RAW";
        CameraMediaEntry cover = cover();
        return cover == null ? "IMAGE" : cover.badge();
    }

    String subtitle() {
        CameraMediaEntry cover = cover();
        StringBuilder builder = new StringBuilder();
        if (cover != null && !cover.timeLabel().isEmpty()) builder.append(cover.timeLabel());
        if (cover != null) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(cover.storageLabel == null || cover.storageLabel.isEmpty() ? "相机存储" : cover.storageLabel);
        }
        if (cover != null && cover.width > 0 && cover.height > 0) builder.append(" · ").append(cover.width).append("×").append(cover.height);
        builder.append(" · ").append(assets.size()).append(" 文件");
        builder.append(" · ").append(CameraMediaEntry.humanBytes(totalBytes()));
        return builder.toString();
    }

    long totalBytes() {
        long total = 0;
        for (CameraMediaEntry entry : assets) total += Math.max(0, entry.sizeBytes);
        return total;
    }

    String detailText() {
        StringBuilder builder = new StringBuilder();
        builder.append(title()).append('\n');
        builder.append("拍摄时间：").append(dayTitle()).append(shotTime == null ? "" : " " + cover().timeLabel()).append('\n');
        builder.append("格式组合：").append(badge()).append('\n');
        builder.append("总大小：").append(CameraMediaEntry.humanBytes(totalBytes())).append('\n');
        builder.append("文件数：").append(assets.size()).append('\n');
        for (CameraMediaEntry entry : assets) {
            builder.append('\n').append("• ").append(entry.displayName())
                    .append(" · ").append(entry.formatName())
                    .append(" · ").append(CameraMediaEntry.humanBytes(entry.sizeBytes));
        }
        return builder.toString();
    }

    String dayTitle() {
        LocalDate localDay = day;
        if (localDay == null) return "未知日期";
        LocalDate today = LocalDate.now();
        if (Objects.equals(localDay, today)) return "今天";
        if (Objects.equals(localDay, today.minusDays(1))) return "昨天";
        if (localDay.getYear() == today.getYear()) {
            return localDay.getMonthValue() + "月" + localDay.getDayOfMonth() + "日 " +
                    localDay.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA);
        }
        return localDay.getYear() + "年" + localDay.getMonthValue() + "月" + localDay.getDayOfMonth() + "日";
    }

    Bitmap coverBitmap() {
        CameraMediaEntry cover = cover();
        return cover == null ? null : cover.thumbnail;
    }
}
