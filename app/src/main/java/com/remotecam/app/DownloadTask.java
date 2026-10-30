package com.remotecam.app;

import java.util.ArrayList;
import java.util.List;

/** One queued download job (a photo group or a batch of selected groups). */
final class DownloadTask {
    enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    final String id;
    final String title;
    final List<CameraMediaEntry> entries;

    State state = State.QUEUED;
    int fileIndex;
    int fileTotal;
    long fileBytes;
    long fileProgressBytes;
    long completedBytes;
    long totalBytes;
    long bytesPerSecond;
    String stage = "";
    String currentFile = "";
    final List<String> errors = new ArrayList<>();

    DownloadTask(String id, String title, List<CameraMediaEntry> entries) {
        this.id = id;
        this.title = title;
        this.entries = entries;
    }

    boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    int percent() {
        if (state == State.DONE) return 100;
        if (totalBytes <= 0) return 0;
        return (int) Math.max(0, Math.min(100, Math.round(completedBytes * 100d / totalBytes)));
    }

    String stateLabel() {
        return switch (state) {
            case QUEUED -> "排队中";
            case RUNNING -> "下载中";
            case DONE -> "已完成";
            case FAILED -> "有失败文件";
            case CANCELLED -> "已取消";
        };
    }

    String statusText() {
        StringBuilder builder = new StringBuilder(stateLabel());
        if (state == State.RUNNING) {
            builder.append(" · ").append(fileIndex).append('/').append(fileTotal);
            if (!currentFile.isEmpty()) builder.append(" · ").append(currentFile);
            if (!stage.isEmpty()) builder.append(" · ").append(stage);
            builder.append(" · ").append(percent()).append('%');
            if (totalBytes > 0) builder.append(" · ").append(CameraMediaEntry.humanBytes(completedBytes))
                    .append(" / ").append(CameraMediaEntry.humanBytes(totalBytes));
            if (bytesPerSecond > 0) builder.append(" · ").append(CameraMediaEntry.humanBytes(bytesPerSecond)).append("/s");
        } else if (state == State.QUEUED) {
            builder.append(" · ").append(entries.size()).append(" 个文件");
            if (totalBytes > 0) builder.append(" · ").append(CameraMediaEntry.humanBytes(totalBytes));
        } else if (state == State.DONE) {
            builder.append(" · ").append(entries.size()).append(" 个文件 · ").append(CameraMediaEntry.humanBytes(Math.max(completedBytes, totalBytes)));
        } else if (!errors.isEmpty()) {
            builder.append(" · ").append(errors.size()).append(" 个文件失败");
        }
        return builder.toString();
    }
}
