package com.remoteyourcam.usb.ptp.commands;

import com.remoteyourcam.usb.ptp.Camera.RetrieveObjectInfoListener;
import com.remoteyourcam.usb.ptp.PtpAction;
import com.remoteyourcam.usb.ptp.PtpCamera;
import com.remoteyourcam.usb.ptp.PtpCamera.IO;
import com.remoteyourcam.usb.ptp.PtpConstants.Response;
import com.remoteyourcam.usb.ptp.model.ObjectInfo;

public class RetrieveObjectInfoAction implements PtpAction {

    private final PtpCamera camera;
    private final RetrieveObjectInfoListener listener;
    private final int objectHandle;

    public RetrieveObjectInfoAction(PtpCamera camera, RetrieveObjectInfoListener listener, int objectHandle) {
        this.camera = camera;
        this.listener = listener;
        this.objectHandle = objectHandle;
    }

    @Override
    public void exec(IO io) {
        GetObjectInfoCommand getInfo = new GetObjectInfoCommand(camera, objectHandle);
        io.handleCommand(getInfo);

        ObjectInfo objectInfo = getInfo.getResponseCode() == Response.Ok ? getInfo.getObjectInfo() : null;
        listener.onObjectInfoRetrieved(objectHandle, objectInfo);
    }

    @Override
    public void reset() {
    }
}
