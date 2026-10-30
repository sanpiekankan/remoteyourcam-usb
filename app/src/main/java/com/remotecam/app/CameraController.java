package com.remotecam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Size;

import com.remoteyourcam.usb.ptp.*;
import com.remoteyourcam.usb.ptp.model.LiveViewData;
import com.remoteyourcam.usb.ptp.model.ObjectInfo;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Application-context session owner. All observable state is confined to the main thread. */
final class CameraController implements Camera.CameraListener {
    interface Observer { void onStateChanged(); void onFrame(Bitmap bitmap); }

    interface MediaLibraryCallback {
        void onStarted();
        void onProgress(int loaded, int total);
        /** Incremental snapshot of everything loaded so far; posted while scanning continues. */
        default void onBatch(List<CameraMediaEntry> entries) { }
        void onLoaded(List<CameraMediaEntry> entries);
        void onError(String message);
    }

    interface ThumbnailCallback {
        void onLoaded(CameraMediaEntry entry, Bitmap bitmap);
        void onError(CameraMediaEntry entry, String message);
    }

    interface MediaPreviewCallback {
        void onStarted(CameraMediaEntry entry);
        void onLoaded(CameraMediaEntry entry, Bitmap bitmap, String note);
        void onError(CameraMediaEntry entry, String message);
    }

    interface DownloadCallback {
        void onStarted(int totalFiles, long totalBytes);
        void onFileStarted(int index, int totalFiles, String filename, long fileBytes, long totalBytes);
        void onFileProgress(int index, int totalFiles, String filename, long fileProgressBytes, long fileBytes,
                            long completedBytes, long totalBytes, long bytesPerSecond, String stage);
        void onCompleted(int successCount, int totalFiles, long totalBytes, List<String> errors);
        void onError(String message);
    }

    enum Status { DISCONNECTED, PERMISSION, CONNECTING, CONNECTED, ERROR }

    private static final long CAMERA_WAIT_SECONDS = 25L;
    private static final String DOWNLOAD_CHANNEL_ID = "camera_downloads";
    private static final int DOWNLOAD_NOTIFICATION_ID = 3101;

    /** Lower value runs first. Preview work jumps ahead of library scanning and downloads. */
    private static final int PRIORITY_CONNECT = 0;
    private static final int PRIORITY_PREVIEW = 1;
    private static final int PRIORITY_LIBRARY = 3;
    private static final int PRIORITY_DOWNLOAD = 4;

    private final Context context;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong ioSequence = new AtomicLong();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
    private final String permissionAction;
    private final NotificationManager notifications;

    private int generation;
    private int libraryGeneration;
    private int thumbnailGeneration;
    private int downloadGeneration;
    private UsbDevice selected;
    private PtpCamera camera;
    private Observer observer;
    private boolean foreground;
    private boolean framePending;
    private boolean liveRequested;
    private boolean closed;
    private boolean downloadActive;
    private final Set<Integer> receivedHandles = new HashSet<>();
    private final Set<Integer> thumbnailRequests = new HashSet<>();

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
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                if (shouldAutoConnect(device)) {
                    message = "检测到 USB 相机，正在自动连接…";
                    notifyState();
                    connect(device);
                    return;
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && sameDevice(device, selected)) {
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
            camera.getLiveViewPicture(null);
        }
    };

    CameraController(Context context) {
        this.context = context.getApplicationContext();
        usb = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        notifications = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        permissionAction = this.context.getPackageName() + ".USB_PERMISSION";
        createNotificationChannel();

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
    boolean hasBackgroundWork() { return downloadActive; }

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
        submitIo(PRIORITY_CONNECT, () -> {
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
        if (!foreground) {
            main.removeCallbacks(nextFrame);
            framePending = false;
            if (liveRequested && camera != null && camera.isLiveViewOpen()) camera.setLiveView(false);
            return;
        }
        if (liveRequested && camera != null && !camera.isLiveViewOpen() && !liveTransition) {
            liveTransition = true;
            camera.setLiveView(true);
            main.postDelayed(liveTimeout, 10000);
            notifyState();
        } else if (liveRequested && camera != null && camera.isLiveViewOpen() && !framePending) {
            main.post(nextFrame);
        }
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

    void loadMediaLibrary(MediaLibraryCallback callback) {
        if (!connected() || camera == null) {
            post(() -> callback.onError("请先连接相机"));
            return;
        }
        final int token = ++libraryGeneration;
        submitIo(PRIORITY_LIBRARY, () -> {
            post(callback::onStarted);
            try {
                LinkedHashMap<Integer, String> storages = retrieveStoragesSync(token);
                if (!validLibraryToken(token)) return;
                List<HandleRef> handles = new ArrayList<>();
                for (Map.Entry<Integer, String> entry : storages.entrySet()) {
                    int[] values = retrieveHandlesSync(token, entry.getKey());
                    Arrays.sort(values);
                    for (int i = values.length - 1; i >= 0; i--) handles.add(new HandleRef(values[i], entry.getKey(), entry.getValue()));
                }
                final int total = handles.size();
                if (total == 0) {
                    post(() -> callback.onLoaded(new ArrayList<>()));
                    return;
                }
                // Scan in small chunks queued at library priority so thumbnail / preview
                // requests (PRIORITY_PREVIEW) interleave between chunks: the grid fills in live.
                final List<CameraMediaEntry> accumulated = Collections.synchronizedList(new ArrayList<>());
                final AtomicBoolean failed = new AtomicBoolean(false);
                final int chunkSize = 24;
                for (int start = 0; start < total; start += chunkSize) {
                    final int from = start;
                    final int to = Math.min(total, start + chunkSize);
                    final boolean lastChunk = to == total;
                    submitIo(PRIORITY_LIBRARY, () -> {
                        if (failed.get() || !validLibraryToken(token)) return;
                        try {
                            List<CameraMediaEntry> batch = new ArrayList<>();
                            for (int i = from; i < to; i++) {
                                if (!validLibraryToken(token)) return;
                                CameraMediaEntry item = retrieveMediaInfoSync(token, handles.get(i));
                                if (item != null && isImageObject(item)) batch.add(item);
                                final int loaded = i + 1;
                                post(() -> callback.onProgress(loaded, total));
                            }
                            accumulated.addAll(batch);
                            post(() -> callback.onBatch(new ArrayList<>(accumulated)));
                            if (lastChunk) {
                                accumulated.sort(Comparator
                                        .comparing((CameraMediaEntry entry) -> entry.captureDateTime(), Comparator.nullsLast(Comparator.reverseOrder()))
                                        .thenComparingInt(entry -> -entry.handle));
                                if (!validLibraryToken(token)) return;
                                post(() -> callback.onLoaded(new ArrayList<>(accumulated)));
                            }
                        } catch (Exception e) {
                            if (failed.compareAndSet(false, true) && validLibraryToken(token))
                                post(() -> callback.onError(e.getMessage() == null ? "读取相机照片失败" : e.getMessage()));
                        }
                    });
                }
            } catch (Exception e) {
                if (!validLibraryToken(token)) return;
                post(() -> callback.onError(e.getMessage() == null ? "读取相机照片失败" : e.getMessage()));
            }
        });
    }

    void loadThumbnail(CameraMediaEntry entry, ThumbnailCallback callback) {
        if (entry == null) return;
        if (entry.thumbnail != null && !entry.thumbnail.isRecycled()) {
            post(() -> callback.onLoaded(entry, entry.thumbnail));
            return;
        }
        synchronized (thumbnailRequests) {
            if (!thumbnailRequests.add(entry.handle)) return;
        }
        submitIo(PRIORITY_PREVIEW, () -> {
            try {
                if (!connected() || camera == null) throw new IllegalStateException("相机未连接");
                Bitmap thumb = retrieveThumbnailSync(entry.handle);
                // No generation check here: prefetch enqueues many thumbnails at once and each
                // one must reach its own callback, otherwise almost every tile stays blank.
                if (thumb == null) throw new IllegalStateException("相机未返回缩略图");
                entry.thumbnail = prepareThumbnail(thumb);
                post(() -> callback.onLoaded(entry, entry.thumbnail));
            } catch (Exception e) {
                post(() -> callback.onError(entry, e.getMessage() == null ? "缩略图读取失败" : e.getMessage()));
            } finally {
                synchronized (thumbnailRequests) { thumbnailRequests.remove(entry.handle); }
            }
        });
    }

    void loadLargePreview(CameraMediaEntry entry, Size target, MediaPreviewCallback callback) {
        if (entry == null) return;
        if (!connected() || camera == null) {
            post(() -> callback.onError(entry, "相机未连接"));
            return;
        }
        submitIo(PRIORITY_PREVIEW, () -> {
            post(() -> callback.onStarted(entry));
            try {
                DownloadedObject object = retrieveObjectSync(entry.handle, null);
                if (object == null || object.data == null) throw new IllegalStateException("无法读取相机原始文件");
                Bitmap bitmap = decodePreviewBitmap(entry, object.data, target);
                if (bitmap == null && entry.thumbnail != null) {
                    post(() -> callback.onLoaded(entry, entry.thumbnail, "RAW 文件无法直接解码，已显示内嵌预览"));
                    return;
                }
                if (bitmap == null) throw new IllegalStateException("无法解码图片预览");
                String note = entry.raw ? "RAW 文件显示的是相机内嵌预览；下载后可在专业应用查看原始数据" : "已加载高质量预览";
                post(() -> callback.onLoaded(entry, bitmap, note));
            } catch (Exception e) {
                post(() -> callback.onError(entry, e.getMessage() == null ? "加载原图失败" : e.getMessage()));
            }
        });
    }

    void downloadMedia(Uri treeUri, List<CameraMediaEntry> entries, DownloadCallback callback) {
        if (treeUri == null) {
            post(() -> callback.onError("请先选择下载目录"));
            return;
        }
        if (!connected() || camera == null) {
            post(() -> callback.onError("相机未连接"));
            return;
        }
        if (entries == null || entries.isEmpty()) {
            post(() -> callback.onError("请先选择要下载的照片"));
            return;
        }
        final int token = ++downloadGeneration;
        downloadActive = true;
        long totalBytes = 0L;
        for (CameraMediaEntry entry : entries) totalBytes += Math.max(0, entry.sizeBytes);
        final long finalTotalBytes = totalBytes;
        submitIo(PRIORITY_DOWNLOAD, () -> {
            updateDownloadNotification("准备下载", "共 " + entries.size() + " 个文件");
            post(() -> callback.onStarted(entries.size(), finalTotalBytes));
            int successCount = 0;
            long completedBytes = 0L;
            List<String> errors = new ArrayList<>();
            ContentResolver resolver = context.getContentResolver();
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            try {
                for (int i = 0; i < entries.size(); i++) {
                    if (token != downloadGeneration || !connected() || camera == null) return;
                    CameraMediaEntry entry = entries.get(i);
                    String displayName = displayName(entry);
                    final int index = i + 1;
                    post(() -> callback.onFileStarted(index, entries.size(), displayName, Math.max(0, entry.sizeBytes), finalTotalBytes));
                    long fileBytes = Math.max(0, entry.sizeBytes);
                    long completedBeforeFile = completedBytes;
                    long[] throughput = {System.nanoTime(), 0L};

                    try {
                        DownloadedObject object = retrieveObjectSync(entry.handle, (objectHandle, transferredBytes, totalObjectBytes) -> {
                            long bps = speed(throughput, transferredBytes);
                            long overall = completedBeforeFile + Math.min(transferredBytes, totalObjectBytes);
                            post(() -> callback.onFileProgress(index, entries.size(), displayName,
                                    transferredBytes, totalObjectBytes, overall, finalTotalBytes, bps, "从相机读取"));
                            updateDownloadNotification("下载中", displayName + " · 读取 " + percent(transferredBytes, totalObjectBytes));
                        });
                        if (object == null || object.data == null) throw new IllegalStateException("无法下载 " + displayName);

                        String fileName = sanitizeFilename(object.objectInfo != null ? object.objectInfo.filename : entry.filename, entry.handle, entry.extension);
                        Uri fileUri = DocumentsContract.createDocument(resolver, parent, mimeTypeFor(entry), fileName);
                        if (fileUri == null) throw new IllegalStateException("无法在下载目录中创建文件");

                        throughput[0] = System.nanoTime();
                        throughput[1] = 0L;
                        try (OutputStream stream = resolver.openOutputStream(fileUri, "w")) {
                            if (stream == null) throw new IllegalStateException("无法写入目标文件");
                            byte[] data = object.data;
                            int offset = 0;
                            int chunkSize = Math.max(64 * 1024, Math.min(512 * 1024, data.length / 16 == 0 ? 64 * 1024 : data.length / 16));
                            while (offset < data.length) {
                                int count = Math.min(chunkSize, data.length - offset);
                                stream.write(data, offset, count);
                                offset += count;
                                long bps = speed(throughput, offset);
                                long overall = completedBeforeFile + offset;
                                long finalOffset = offset;
                                post(() -> callback.onFileProgress(index, entries.size(), fileName,
                                        finalOffset, data.length, overall, finalTotalBytes, bps, "保存到手机"));
                                updateDownloadNotification("下载中", fileName + " · 保存 " + percent(finalOffset, data.length));
                            }
                            stream.flush();
                        }

                        completedBytes += object.data.length;
                        successCount++;
                    } catch (Exception e) {
                        errors.add(displayName + "： " + (e.getMessage() == null ? "下载失败" : e.getMessage()));
                        long failedOverall = completedBeforeFile;
                        post(() -> callback.onFileProgress(index, entries.size(), displayName,
                                0, fileBytes, failedOverall, finalTotalBytes, 0, "下载失败"));
                    }
                }
            } finally {
                downloadActive = false;
            }

            cancelDownloadNotification();
            int finalSuccess = successCount;
            long finalCompletedBytes = completedBytes;
            post(() -> callback.onCompleted(finalSuccess, entries.size(), Math.max(finalCompletedBytes, finalTotalBytes), errors));
        });
    }

    /** Stops the in-flight transfer; the queued loop exits on its next file boundary. */
    void cancelDownloads() {
        ++downloadGeneration;
        downloadActive = false;
        cancelDownloadNotification();
    }

    void disconnect() {
        ++generation;
        ++libraryGeneration;
        ++thumbnailGeneration;
        ++downloadGeneration;
        if (!downloadActive) main.removeCallbacksAndMessages(null);
        if (camera != null) {
            PtpCamera previous = camera;
            previous.setListener(null);
            previous.shutdown();
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
        thumbnailRequests.clear();
        status = Status.DISCONNECTED;
        message = "连接相机，开始创作";
        downloadActive = false;
        cancelDownloadNotification();
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

    private LinkedHashMap<Integer, String> retrieveStoragesSync(int token) throws Exception {
        PtpCamera active = requireCamera();
        CountDownLatch latch = new CountDownLatch(1);
        LinkedHashMap<Integer, String> storages = new LinkedHashMap<>();
        active.retrieveStorages(new Camera.StorageInfoListener() {
            @Override public void onStorageFound(int handle, String label) { storages.put(handle, label); }
            @Override public void onAllStoragesFound() { latch.countDown(); }
            @Override public void onImageHandlesRetrieved(int[] handles) { }
        });
        await(latch, "读取相机存储信息超时");
        if (!validLibraryToken(token)) throw new IllegalStateException("读取已取消");
        if (storages.isEmpty()) storages.put(0xffffffff, "全部存储");
        return storages;
    }

    private int[] retrieveHandlesSync(int token, int storageId) throws Exception {
        PtpCamera active = requireCamera();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<int[]> handles = new AtomicReference<>(new int[0]);
        active.retrieveImageHandles(new Camera.StorageInfoListener() {
            @Override public void onStorageFound(int handle, String label) { }
            @Override public void onAllStoragesFound() { }
            @Override public void onImageHandlesRetrieved(int[] values) {
                handles.set(values == null ? new int[0] : values);
                latch.countDown();
            }
        }, storageId, 0);
        await(latch, "读取照片列表超时");
        if (!validLibraryToken(token)) throw new IllegalStateException("读取已取消");
        return handles.get();
    }

    private CameraMediaEntry retrieveMediaInfoSync(int token, HandleRef handleRef) throws Exception {
        PtpCamera active = requireCamera();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ObjectInfo> objectInfo = new AtomicReference<>();
        active.retrieveObjectInfo((objectHandle, info) -> {
            objectInfo.set(info);
            latch.countDown();
        }, handleRef.handle);
        await(latch, "读取照片信息超时");
        if (!validLibraryToken(token)) throw new IllegalStateException("读取已取消");
        ObjectInfo info = objectInfo.get();
        if (info == null) return null;
        String filename = info.filename == null ? ("IMG_" + handleRef.handle) : info.filename;
        String extension = extension(filename);
        boolean raw = isRawFormat(info.objectFormat, extension);
        int width = info.imagePixWidth > 0 ? info.imagePixWidth : info.thumbPixWidth;
        int height = info.imagePixHeight > 0 ? info.imagePixHeight : info.thumbPixHeight;
        return new CameraMediaEntry(handleRef.handle, handleRef.storageId, info.objectFormat, handleRef.storageLabel,
                filename, info.captureDate, info.modificationDate, info.objectCompressedSize, width, height,
                raw, extension, null);
    }

    private Bitmap retrieveThumbnailSync(int handle) throws Exception {
        PtpCamera active = requireCamera();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> thumbnail = new AtomicReference<>();
        active.retrieveImageInfo((objectHandle, info, thumb) -> {
            thumbnail.set(thumb);
            latch.countDown();
        }, handle);
        await(latch, "读取缩略图超时");
        return thumbnail.get();
    }

    private DownloadedObject retrieveObjectSync(int handle, Camera.TransferProgressListener progressListener) throws Exception {
        PtpCamera active = requireCamera();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DownloadedObject> ref = new AtomicReference<>();
        active.retrieveObject((objectHandle, objectInfo, data) -> {
            if (objectHandle == 0 || objectInfo == null || data == null) ref.set(null);
            else ref.set(new DownloadedObject(objectHandle, objectInfo, data));
            latch.countDown();
        }, handle, progressListener);
        await(latch, "读取原图超时");
        DownloadedObject result = ref.get();
        if (result == null) throw new IllegalStateException("相机未返回原始文件");
        return result;
    }

    private void await(CountDownLatch latch, String message) throws Exception {
        if (!latch.await(CAMERA_WAIT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException(message);
    }

    private Bitmap decodePreviewBitmap(CameraMediaEntry entry, byte[] data, Size target) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int targetWidth = Math.max(1600, target.getWidth() * 2);
                int targetHeight = Math.max(1600, target.getHeight() * 2);
                int sample = Math.max(1, Math.min(
                        Math.max(info.getSize().getWidth() / Math.max(1, targetWidth), 1),
                        Math.max(info.getSize().getHeight() / Math.max(1, targetHeight), 1)));
                decoder.setTargetSampleSize(sample);
            });
        } catch (Exception ignored) {
            if (entry.thumbnail != null) return entry.thumbnail;
            try {
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (Exception decodeError) {
                return null;
            }
        }
    }

    private boolean validLibraryToken(int token) {
        return token == libraryGeneration && connected() && camera != null;
    }

    private PtpCamera requireCamera() {
        if (!connected() || camera == null) throw new IllegalStateException("相机未连接");
        return camera;
    }

    private Bitmap prepareThumbnail(Bitmap source) {
        if (source == null) return null;
        int maxSide = Math.max(source.getWidth(), source.getHeight());
        if (maxSide <= 512) return source;
        float scale = 512f / maxSide;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static boolean isImageObject(CameraMediaEntry item) {
        if (item == null) return false;
        if (item.raw) return true;
        if (item.objectFormat >= 0x3800 && item.objectFormat <= 0x3810) return true;
        return Set.of("jpg", "jpeg", "png", "tif", "tiff", "bmp").contains(item.extension);
    }

    private static boolean isRawFormat(int objectFormat, String extension) {
        if (objectFormat == PtpConstants.ObjectFormat.EosCRW || objectFormat == PtpConstants.ObjectFormat.EosCRW3) return true;
        return Set.of("cr2", "cr3", "crw", "nef", "nrw", "arw", "dng", "orf", "raf", "rw2", "pef", "srw")
                .contains(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
    }

    static String formatPtpDate(String value) {
        LocalDateTime time = CameraMediaEntry.parseDateTime(value);
        if (time == null) return value == null ? "" : value;
        return String.format(Locale.getDefault(), "%04d-%02d-%02d %02d:%02d:%02d",
                time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
                time.getHour(), time.getMinute(), time.getSecond());
    }

    private static String displayName(CameraMediaEntry entry) {
        return entry == null ? "未知文件" : entry.displayName();
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sanitizeFilename(String preferred, int handle, String extension) {
        String base = preferred == null || preferred.isBlank() ? ("IMG_" + handle) : preferred.trim();
        String cleaned = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (cleaned.indexOf('.') < 0 && extension != null && !extension.isEmpty()) cleaned += "." + extension;
        return cleaned;
    }

    private static String mimeTypeFor(CameraMediaEntry entry) {
        String ext = entry.extension == null ? "" : entry.extension;
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "tif", "tiff" -> "image/tiff";
            case "bmp" -> "image/bmp";
            case "dng" -> "image/x-adobe-dng";
            case "cr2" -> "image/x-canon-cr2";
            case "cr3" -> "image/x-canon-cr3";
            case "nef", "nrw" -> "image/x-nikon-nef";
            case "arw" -> "image/x-sony-arw";
            case "orf" -> "image/x-olympus-orf";
            case "raf" -> "image/x-fuji-raf";
            case "rw2" -> "image/x-panasonic-rw2";
            default -> "application/octet-stream";
        };
    }

    private static long speed(long[] state, long transferredBytes) {
        long now = System.nanoTime();
        long elapsedNanos = Math.max(1L, now - state[0]);
        long deltaBytes = Math.max(0L, transferredBytes - state[1]);
        state[0] = now;
        state[1] = transferredBytes;
        return (long) (deltaBytes * 1_000_000_000d / elapsedNanos);
    }

    private static String percent(long progress, long total) {
        if (total <= 0) return CameraMediaEntry.humanBytes(progress);
        int pct = (int) Math.min(100, Math.round(progress * 100f / total));
        return pct + "%";
    }

    private void fail(String message) {
        disconnect();
        status = Status.ERROR;
        this.message = message;
        notifyState();
    }

    private void notifyState() { if (observer != null) observer.onStateChanged(); }
    private void post(Runnable runnable) { main.post(runnable); }

    /** Serialises camera work on one thread while letting urgent tasks jump the queue. */
    private void submitIo(int priority, Runnable task) {
        io.execute(new PriorityTask(priority, ioSequence.getAndIncrement(), task));
    }

    private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
        private final int priority;
        private final long sequence;
        private final Runnable body;

        PriorityTask(int priority, long sequence, Runnable body) {
            this.priority = priority;
            this.sequence = sequence;
            this.body = body;
        }

        @Override public void run() { body.run(); }

        @Override public int compareTo(PriorityTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(DOWNLOAD_CHANNEL_ID, "相机下载", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示相机照片下载进度");
            notifications.createNotificationChannel(channel);
        }
    }

    private void updateDownloadNotification(String title, String text) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = launch == null ? null : PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(0, 0, true);
        if (pendingIntent != null) builder.setContentIntent(pendingIntent);
        notifications.notify(DOWNLOAD_NOTIFICATION_ID, builder.build());
    }

    private void cancelDownloadNotification() {
        notifications.cancel(DOWNLOAD_NOTIFICATION_ID);
    }

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

    private boolean shouldAutoConnect(UsbDevice device) {
        if (closed || device == null || !isCompatibleCamera(device)) return false;
        if (connected() || status == Status.CONNECTING || status == Status.PERMISSION) return false;
        return selected == null || sameDevice(device, selected);
    }

    private static boolean isCompatibleCamera(UsbDevice device) {
        return device != null
                && (device.getVendorId() == PtpConstants.CanonVendorId || device.getVendorId() == PtpConstants.NikonVendorId)
                && findInterface(device) != null;
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
        if (liveRequested && foreground && camera != null) camera.setLiveView(true);
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

    @Override public void onBulbStarted() {
        bulb = true;
        main.removeCallbacks(captureTimeout);
        message = "B 门曝光中，点击快门结束";
        notifyState();
    }

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

    private record HandleRef(int handle, int storageId, String storageLabel) { }
    private record DownloadedObject(int handle, ObjectInfo objectInfo, byte[] data) { }
}
