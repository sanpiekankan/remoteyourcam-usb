package com.remoteyourcam.usb.ptp.commands;

import com.remoteyourcam.usb.ptp.Camera.RetrieveObjectListener;
import com.remoteyourcam.usb.ptp.Camera.TransferProgressListener;
import com.remoteyourcam.usb.ptp.PtpAction;
import com.remoteyourcam.usb.ptp.PtpCamera;
import com.remoteyourcam.usb.ptp.PtpCamera.IO;
import com.remoteyourcam.usb.ptp.PtpConstants.Response;
import com.remoteyourcam.usb.ptp.model.ObjectInfo;

public class RetrieveObjectAction implements PtpAction {

    private final PtpCamera camera;
    private final RetrieveObjectListener listener;
    private final int objectHandle;
    private final TransferProgressListener progressListener;

    public RetrieveObjectAction(PtpCamera camera, RetrieveObjectListener listener, int objectHandle) {
        this(camera, listener, objectHandle, null);
    }

    public RetrieveObjectAction(PtpCamera camera, RetrieveObjectListener listener, int objectHandle,
                                TransferProgressListener progressListener) {
        this.camera = camera;
        this.listener = listener;
        this.objectHandle = objectHandle;
        this.progressListener = progressListener;
    }

    @Override
    public void exec(IO io) {
        GetObjectInfoCommand getInfo = new GetObjectInfoCommand(camera, objectHandle);
        io.handleCommand(getInfo);
        if (getInfo.getResponseCode() != Response.Ok) {
            listener.onObjectRetrieved(0, null, null);
            return;
        }

        ObjectInfo objectInfo = getInfo.getObjectInfo();
        if (objectInfo == null) {
            listener.onObjectRetrieved(0, null, null);
            return;
        }

        GetObjectBytesCommand getObject = new GetObjectBytesCommand(camera, objectHandle, (transferredBytes, totalBytes) -> {
            if (progressListener != null) progressListener.onTransferProgress(objectHandle, transferredBytes, totalBytes);
        });
        io.handleCommand(getObject);
        if (getObject.getResponseCode() != Response.Ok || getObject.getData() == null) {
            listener.onObjectRetrieved(objectHandle, objectInfo, null);
            return;
        }

        listener.onObjectRetrieved(objectHandle, objectInfo, getObject.getData());
    }

    @Override
    public void reset() {
    }
}
