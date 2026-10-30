package com.remoteyourcam.usb.ptp.commands;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.remoteyourcam.usb.ptp.PtpCamera;
import com.remoteyourcam.usb.ptp.PtpCamera.IO;
import com.remoteyourcam.usb.ptp.PtpConstants;

/** Reads original object bytes from camera memory card via PTP GetObject. */
public class GetObjectBytesCommand extends Command implements ProgressCommand {

    private final int objectHandle;
    private final ProgressListener progressListener;
    private byte[] data;

    interface ProgressListener {
        void onProgress(long transferredBytes, long totalBytes);
    }

    public GetObjectBytesCommand(PtpCamera camera, int objectHandle) {
        this(camera, objectHandle, null);
    }

    public GetObjectBytesCommand(PtpCamera camera, int objectHandle, ProgressListener progressListener) {
        super(camera);
        this.objectHandle = objectHandle;
        this.progressListener = progressListener;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public void exec(IO io) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reset() {
        super.reset();
        data = null;
    }

    @Override
    public void encodeCommand(ByteBuffer b) {
        encodeCommand(b, PtpConstants.Operation.GetObject, objectHandle);
    }

    @Override
    protected void decodeData(ByteBuffer b, int length) {
        data = Arrays.copyOfRange(b.array(), 12, length);
    }

    @Override
    public void onTransferProgress(long transferredBytes, long totalBytes) {
        if (progressListener != null) progressListener.onProgress(transferredBytes, totalBytes);
    }
}
