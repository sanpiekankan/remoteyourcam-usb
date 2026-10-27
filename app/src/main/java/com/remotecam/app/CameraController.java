package com.remotecam.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.remoteyourcam.usb.ptp.*;
import com.remoteyourcam.usb.ptp.model.LiveViewData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-context session owner. All observable state is confined to the main thread. */
final class CameraController implements Camera.CameraListener {
    interface Observer { void onStateChanged(); void onFrame(Bitmap bitmap); }
    enum Status { DISCONNECTED, PERMISSION, CONNECTING, CONNECTED, ERROR }

    private final Context context;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final String permissionAction;
    private int generation;
    private UsbDevice selected;
    private PtpCamera camera;
    private Observer observer;
    private boolean foreground;
    private boolean framePending;
    private boolean liveRequested;
    private boolean closed;
    private final Set<Integer> receivedHandles = new HashSet<>();
    Status status = Status.DISCONNECTED;
    String message = "连接相机，开始创作";
    Bitmap frame;
    Bitmap lastPhoto;
    String lastFilename = "RemoteCam-preview.jpg";
    boolean bulb;
    boolean capturePending;
    boolean focusPending;
    boolean liveTransition;
    int shotCount;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            UsbDevice device = deviceExtra(intent);
            if (!permissionAction.equals(intent.getAction()) || !sameDevice(device, selected)
                    || status != Status.PERMISSION) return;
            if (usb.hasPermission(device)) open(device);
            else fail("USB 授权被拒绝。点击连接可重新授权。");
        }
    };
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            UsbDevice device = deviceExtra(intent);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && sameDevice(device, selected)) {
                disconnect();
                message = "相机已拔出，请检查 USB 连接";
            }
            notifyState();
        }
    };
    private final Runnable captureTimeout = () -> {
        capturePending = false;
        message = "尚未收到照片事件；请检查相机存储卡和拍摄状态";
        notifyState();
    };
    private final Runnable liveTimeout = () -> {
        liveTransition = false;
        liveRequested = false;
        message = "实时取景未响应，请重连相机";
        notifyState();
    };
    private final Runnable focusTimeout = () -> {
        focusPending = false;
        message = "对焦未响应，请检查镜头 AF 开关";
        notifyState();
    };
    private final Runnable nextFrame = () -> {
        if (foreground && liveRequested && camera != null && camera.isLiveViewOpen() && !framePending) {
            framePending = true;
            // Do not reuse a Bitmap that the UI render thread may still be drawing.
            camera.getLiveViewPicture(null);
        }
    };

    CameraController(Context context) {
        this.context = context.getApplicationContext();
        usb = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        permissionAction = this.context.getPackageName() + ".USB_PERMISSION";
        IntentFilter permission = new IntentFilter(permissionAction);
        IntentFilter devices = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        devices.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(permissionReceiver, permission, Context.RECEIVER_NOT_EXPORTED);
            this.context.registerReceiver(deviceReceiver, devices, Context.RECEIVER_EXPORTED);
        } else {
            this.context.registerReceiver(permissionReceiver, permission);
            this.context.registerReceiver(deviceReceiver, devices);
        }
    }

    void observe(Observer observer) { this.observer = observer; notifyState(); }
    Camera camera() { return camera; }
    boolean connected() { return status == Status.CONNECTED && camera != null && camera.isSessionOpen(); }
    boolean live() { return liveRequested; }

    List<UsbDevice> devices() {
        List<UsbDevice> result = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) {
            if ((device.getVendorId() == PtpConstants.CanonVendorId || device.getVendorId() == PtpConstants.NikonVendorId)
                    && findInterface(device) != null) result.add(device);
        }
        result.sort(Comparator.comparing(UsbDevice::getDeviceName));
        return result;
    }

    void connect(UsbDevice device) {
        if (closed || device == null) return;
        disconnect();
        selected = device;
        if (usb.hasPermission(device)) { open(device); return; }
        status = Status.PERMISSION;
        message = "请在系统弹窗中允许访问 USB 相机";
        notifyState();
        PendingIntent permission = PendingIntent.getBroadcast(context, ++generation,
                new Intent(permissionAction).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { usb.requestPermission(device, permission); }
        catch (RuntimeException e) { fail("无法请求 USB 授权：" + e.getMessage()); }
    }

    private void open(UsbDevice device) {
        status = Status.CONNECTING;
        message = "正在打开 PTP 会话…";
        notifyState();
        int token = ++generation;
        io.execute(() -> {
            UsbDeviceConnection connection = null;
            try {
                UsbInterface intf = findInterface(device);
                if (intf == null) throw new IllegalStateException("未找到 PTP 接口");
                connection = usb.openDevice(device);
                if (connection == null) throw new IllegalStateException("设备已拔出或没有 USB 权限");
                if (!connection.claimInterface(intf, true)) throw new IllegalStateException("USB 接口被其他应用占用");
                UsbDeviceConnection opened = connection;
                main.post(() -> {
                    if (closed || token != generation) { opened.close(); return; }
                    UsbEndpoint in = endpoint(intf, UsbConstants.USB_DIR_IN);
                    UsbEndpoint out = endpoint(intf, UsbConstants.USB_DIR_OUT);
                    PtpUsbConnection transport = new PtpUsbConnection(opened, in, out, device.getVendorId(), device.getProductId());
                    camera = device.getVendorId() == PtpConstants.CanonVendorId
                            ? new EosCamera(transport, this, null) : new NikonCamera(transport, this, null);
                    camera.setCapturedPictureSampleSize(2);
                });
            } catch (RuntimeException e) {
                if (connection != null) connection.close();
                main.post(() -> { if (!closed && token == generation) fail("连接失败：" + e.getMessage()); });
            }
        });
    }

    void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (!foreground && liveRequested) toggleLive();
        if (!foreground && bulb && camera != null) camera.capture();
    }

    void toggleLive() {
        if (!connected() || !camera.isLiveViewSupported() || liveTransition) return;
        liveRequested = !liveRequested;
        framePending = false;
        liveTransition = true;
        main.removeCallbacks(nextFrame);
        camera.setLiveView(liveRequested);
        main.postDelayed(liveTimeout, 10000);
        notifyState();
    }

    void capture() {
        if (!connected() || liveTransition || (capturePending && !bulb)) return;
        if (!bulb) {
            capturePending = true;
            main.postDelayed(captureTimeout, 15000);
        }
        camera.capture();
        message = bulb ? "正在结束 B 门曝光…" : "拍摄命令已发送…";
        notifyState();
    }

    void focus() {
        if (!connected() || !camera.isAutoFocusSupported() || focusPending) return;
        focusPending = true;
        main.postDelayed(focusTimeout, 10000);
        camera.focus();
        notifyState();
    }

    void focusAt(float x, float y) {
        if (connected() && liveRequested && camera.isLiveViewAfAreaSupported()) camera.setLiveViewAfArea(x, y);
    }

    void disconnect() {
        ++generation;
        main.removeCallbacksAndMessages(null);
        if (camera != null) {
            PtpCamera previous = camera;
            previous.setListener(null);
            previous.shutdown();
            // Graceful close first, bounded hard close even if a camera stops responding.
            main.postDelayed(previous::shutdownHard, 2000);
            camera = null;
        }
        selected = null;
        frame = null;
        liveRequested = false;
        framePending = false;
        liveTransition = false;
        capturePending = false;
        focusPending = false;
        bulb = false;
        receivedHandles.clear();
        status = Status.DISCONNECTED;
        message = "连接相机，开始创作";
        notifyState();
    }

    void close() {
        disconnect();
        closed = true;
        observer = null;
        context.unregisterReceiver(permissionReceiver);
        context.unregisterReceiver(deviceReceiver);
        io.shutdown();
    }

    private void fail(String message) {
        disconnect();
        status = Status.ERROR;
        this.message = message;
        notifyState();
    }
    private void notifyState() { if (observer != null) observer.onStateChanged(); }

    static UsbInterface findInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE
                    && endpoint(intf, UsbConstants.USB_DIR_IN) != null && endpoint(intf, UsbConstants.USB_DIR_OUT) != null) return intf;
        }
        return null;
    }
    private static UsbEndpoint endpoint(UsbInterface intf, int direction) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint endpoint = intf.getEndpoint(i);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.getDirection() == direction) return endpoint;
        }
        return null;
    }
    private static boolean sameDevice(UsbDevice a, UsbDevice b) {
        return a != null && b != null && a.getDeviceName().equals(b.getDeviceName());
    }
    @SuppressWarnings("deprecation")
    static UsbDevice deviceExtra(Intent intent) {
        return Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override public void onCameraStarted(Camera camera) { status = Status.CONNECTED; message = "相机已就绪"; notifyState(); }
    @Override public void onCameraStopped(Camera camera) { disconnect(); }
    @Override public void onNoCameraFound() { fail("未找到兼容的 Canon / Nikon PTP 相机"); }
    @Override public void onError(String message) { fail(message); }
    @Override public void onPropertyChanged(int property, int value) { notifyState(); }
    @Override public void onPropertyStateChanged(int property, boolean enabled) { notifyState(); }
    @Override public void onPropertyDescChanged(int property, int[] values) { notifyState(); }
    @Override public void onLiveViewStarted() {
        liveTransition = false;
        main.removeCallbacks(liveTimeout);
        if (!foreground || !liveRequested) {
            liveRequested = false;
            if (camera != null) camera.setLiveView(false);
        } else main.post(nextFrame);
        notifyState();
    }
    @Override public void onLiveViewData(LiveViewData data) {
        framePending = false;
        if (data != null && data.bitmap != null && liveRequested) {
            frame = data.bitmap;
            if (observer != null) observer.onFrame(frame);
        }
        if (liveRequested && foreground) main.postDelayed(nextFrame, data == null ? 160 : 80);
    }
    @Override public void onLiveViewStopped() {
        framePending = false;
        liveTransition = false;
        main.removeCallbacks(liveTimeout);
        main.removeCallbacks(nextFrame);
        if (liveRequested && foreground && camera != null) {
            // Nikon may stop Live View internally during capture.
            camera.setLiveView(true);
        }
        notifyState();
    }
    @Override public void onCapturedPictureReceived(int handle, String filename, Bitmap thumbnail, Bitmap bitmap) {
        capturePending = false;
        main.removeCallbacks(captureTimeout);
        lastPhoto = bitmap != null ? bitmap : thumbnail;
        lastFilename = filename == null ? "RemoteCam-preview.jpg" : filename;
        shotCount++;
        message = lastPhoto == null ? "照片已拍摄，无法解码预览" : "照片已就绪 · " + lastFilename;
        notifyState();
    }
    @Override public void onBulbStarted() { bulb = true; main.removeCallbacks(captureTimeout); message = "B 门曝光中，点击快门结束"; notifyState(); }
    @Override public void onBulbExposureTime(int seconds) { message = "B 门曝光 · " + seconds + " 秒"; notifyState(); }
    @Override public void onBulbStopped() { bulb = false; capturePending = false; notifyState(); }
    @Override public void onFocusStarted() { focusPending = true; notifyState(); }
    @Override public void onFocusEnded(boolean focused) {
        focusPending = false;
        main.removeCallbacks(focusTimeout);
        message = focused ? "对焦完成" : "未能合焦，请检查镜头 AF 开关";
        notifyState();
    }
    @Override public void onFocusPointsChanged() { notifyState(); }
    @Override public void onObjectAdded(int handle, int format) {
        if (camera != null && (format == PtpConstants.ObjectFormat.EXIF_JPEG || format == PtpConstants.ObjectFormat.JFIF)
                && receivedHandles.add(handle)) {
            if (receivedHandles.size() > 256) { receivedHandles.clear(); receivedHandles.add(handle); }
            camera.retrievePicture(handle);
        }
    }
}
