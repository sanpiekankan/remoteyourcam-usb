package com.remoteyourcam.usb.ptp.commands;

public interface ProgressCommand {
    void onTransferProgress(long transferredBytes, long totalBytes);
}
