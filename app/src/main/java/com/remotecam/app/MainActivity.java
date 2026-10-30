package com.remotecam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Size;
import android.view.*;
import android.widget.*;

import com.remoteyourcam.usb.ptp.Camera;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Adaptive Android shooting workspace with USB-only remote library/download workflow. */
public final class MainActivity extends Activity implements CameraController.Observer {
    private static final int BG = 0xFF0D1117, PANEL = 0xFF171E28, TEXT = 0xFFE4EBF2;
    private static final int MUTED = 0xFF919DAF, ACCENT = 0xFF5DE0BC;
    private static final int EXPORT_PREVIEW = 42;
    private static final int PICK_DOWNLOAD_FOLDER = 43;

    private CameraController controller;
    private PreviewView preview;
    private TextView connectionState, deviceName, message, photoInfo, metadata;
    private Button connect, live, shutter, focus, review, gridButton, near, far, libraryButton;
    private final Map<Integer, Button> properties = new LinkedHashMap<>();
    private boolean reviewing;
    private boolean grid;
    private boolean resumed;
    private Bitmap exportBitmap;
    private final ExecutorService exports = Executors.newSingleThreadExecutor();

    private FrameLayout screenRoot;
    private LinearLayout libraryOverlay;
    private PreviewView libraryPreview;
    private TextView libraryStatus, librarySelection, libraryMeta, libraryFolder;
    private Button libraryRefresh, libraryClose, librarySelectAll, libraryClear, libraryDownloadSelected,
            libraryDownloadCurrent, libraryLoadOriginal, libraryChooseFolder;
    private ListView libraryList;
    private MediaLibraryAdapter libraryAdapter;
    private final List<CameraMediaEntry> libraryEntries = new ArrayList<>();
    private final List<CameraAlbumItem> libraryItems = new ArrayList<>();
    private final Set<String> selectedAlbumIds = new LinkedHashSet<>();
    private final Set<Integer> pendingThumbnailHandles = new HashSet<>();
    private CameraAlbumItem activeLibraryItem;
    private Bitmap libraryLargePreview;
    private int libraryLargePreviewHandle = -1;
    private Uri downloadFolderUri;
    private ArrayList<String> pendingDownloadAlbumIds;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        controller = retained instanceof CameraController ? (CameraController) retained : new CameraController(this);
        grid = getPreferences(MODE_PRIVATE).getBoolean("grid", false);
        String storedFolder = getPreferences(MODE_PRIVATE).getString("download_tree_uri", null);
        if (storedFolder != null) downloadFolderUri = Uri.parse(storedFolder);
        buildWorkspace();
        controller.observe(this);
        if (retained == null) handleUsbIntent(getIntent());
    }

    private void buildWorkspace() {
        LinearLayout workspaceRoot = column();
        workspaceRoot.setBackgroundColor(BG);
        workspaceRoot.setPadding(dp(20), dp(12), dp(20), dp(12));
        workspaceRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + dp(20), top + dp(12), right + dp(20), bottom + dp(12));
            return insets;
        });

        LinearLayout header = row();
        TextView brand = text("REMOTE / CAM", 23, TEXT);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(54), 1));
        header.addView(button("连接指南", false, v -> showGuide()));
        workspaceRoot.addView(header);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout workspace = wide ? row() : column();
        LinearLayout viewer = column();
        LinearLayout viewerHeader = row();
        TextView label = text("拍摄工作台", 16, TEXT);
        viewerHeader.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
        gridButton = button(grid ? "网格 开" : "网格 关", false, v -> {
            grid = !grid;
            getPreferences(MODE_PRIVATE).edit().putBoolean("grid", grid).apply();
            preview.setGrid(grid);
            gridButton.setText(grid ? "网格 开" : "网格 关");
        });
        viewerHeader.addView(gridButton);
        viewer.addView(viewerHeader);

        preview = new PreviewView(this);
        preview.setGrid(grid);
        preview.setFocusListener(controller::focusAt);
        preview.setBackground(rounded(0xFF080C11, 18));
        preview.setClipToOutline(true);
        int previewHeight = dp(Math.max(210, (getResources().getConfiguration().screenWidthDp - 40) * 2 / 3));
        viewer.addView(preview, wide ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, previewHeight));
        metadata = text("USB PTP · 远程控制", 12, MUTED);
        viewer.addView(metadata, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout photoRow = row();
        photoInfo = text("拍摄后的 JPEG 可在这里查看", 12, MUTED);
        photoRow.addView(photoInfo, new LinearLayout.LayoutParams(0, -2, 1));
        review = button("查看照片", false, v -> {
            reviewing = !reviewing;
            preview.setReview(reviewing);
            onStateChanged();
        });
        photoRow.addView(review);
        photoRow.addView(button("导出预览", false, v -> exportPreview()));
        libraryButton = button("照片库", false, v -> openLibrary());
        photoRow.addView(libraryButton);
        viewer.addView(photoRow);

        LinearLayout controls = column();
        controls.setPadding(dp(16), dp(16), dp(16), dp(16));
        controls.setBackground(rounded(PANEL, 18));
        connectionState = text("● 未连接", 12, ACCENT);
        deviceName = text("连接你的相机", 21, TEXT);
        deviceName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        controls.addView(connectionState);
        controls.addView(deviceName);
        message = text("", 13, MUTED);
        controls.addView(message, spaced(-1, -2, 10));
        connect = button("连接相机", true, v -> chooseDevice());
        controls.addView(connect, spaced(-1, dp(52), 12));
        controls.addView(text("曝光控制", 15, TEXT), spaced(-1, -2, 24));
        addPropertyRow(controls, Camera.Property.ShutterSpeed, "快门", Camera.Property.ApertureValue, "光圈");
        addPropertyRow(controls, Camera.Property.IsoSpeed, "ISO", Camera.Property.Whitebalance, "白平衡");
        addPropertyRow(controls, Camera.Property.ExposureCompensation, "曝光补偿", Camera.Property.ColorTemperature, "色温");
        controls.addView(text("可调参数由机身模式和相机能力决定", 11, MUTED), spaced(-1, -2, 8));

        LinearLayout liveRow = row();
        live = button("开启实时取景", false, v -> { reviewing = false; preview.setReview(false); controller.toggleLive(); });
        focus = button("自动对焦", false, v -> controller.focus());
        liveRow.addView(live, new LinearLayout.LayoutParams(0, dp(52), 1));
        liveRow.addView(focus, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(liveRow, spaced(-1, -2, 20));

        LinearLayout lensRow = row();
        near = button("焦点 −", false, v -> driveLens(Camera.DriveLens.Near));
        far = button("焦点 +", false, v -> driveLens(Camera.DriveLens.Far));
        lensRow.addView(near, new LinearLayout.LayoutParams(0, dp(48), 1));
        lensRow.addView(far, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(lensRow);

        shutter = button("◎  拍摄", true, v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            controller.capture();
        });
        controls.addView(shutter, spaced(-1, dp(72), 16));
        controls.addView(text("照片库支持按日期分组、JPEG+RAW 配对显示、后台批量下载与懒加载缩略图", 11, MUTED), spaced(-1, -2, 12));

        if (wide) {
            LinearLayout.LayoutParams viewerParams = new LinearLayout.LayoutParams(0, -1, 1);
            viewerParams.setMarginEnd(dp(20));
            workspace.addView(viewer, viewerParams);
            workspace.addView(scroll(controls), new LinearLayout.LayoutParams(dp(340), -1));
            workspaceRoot.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            workspace.addView(viewer);
            workspace.addView(controls, spaced(-1, -2, 18));
            workspaceRoot.addView(scroll(workspace), new LinearLayout.LayoutParams(-1, 0, 1));
        }

        screenRoot = new FrameLayout(this);
        screenRoot.addView(workspaceRoot, new FrameLayout.LayoutParams(-1, -1));
        libraryOverlay = buildLibraryOverlay();
        libraryOverlay.setVisibility(View.GONE);
        screenRoot.addView(libraryOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screenRoot);
        workspaceRoot.requestApplyInsets();
    }

    private LinearLayout buildLibraryOverlay() {
        LinearLayout overlay = column();
        overlay.setBackgroundColor(0xEE0B1118);
        overlay.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = row();
        TextView title = text("相机照片库", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        libraryRefresh = button("刷新", false, v -> refreshLibrary());
        libraryClose = button("关闭", false, v -> closeLibrary());
        header.addView(libraryRefresh);
        header.addView(libraryClose);
        overlay.addView(header);

        libraryStatus = text("从相机读取照片列表…", 12, MUTED);
        overlay.addView(libraryStatus, spaced(-1, -2, 10));
        librarySelection = text("未选择照片", 12, MUTED);
        overlay.addView(librarySelection, spaced(-1, -2, 4));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout body = wide ? row() : column();

        LinearLayout left = column();
        left.setBackground(rounded(PANEL, 18));
        left.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout selectRow = row();
        librarySelectAll = button("全选分组", false, v -> {
            selectedAlbumIds.clear();
            for (CameraAlbumItem item : libraryItems) selectedAlbumIds.add(item.id);
            updateLibrarySelectionUi();
        });
        libraryClear = button("清空选择", false, v -> {
            selectedAlbumIds.clear();
            updateLibrarySelectionUi();
        });
        selectRow.addView(librarySelectAll, new LinearLayout.LayoutParams(0, dp(46), 1));
        selectRow.addView(libraryClear, new LinearLayout.LayoutParams(0, dp(46), 1));
        left.addView(selectRow);

        libraryList = new ListView(this);
        libraryList.setDividerHeight(dp(2));
        libraryList.setCacheColorHint(0);
        libraryList.setSelector(new ColorDrawable(0x2225A18E));
        libraryAdapter = new MediaLibraryAdapter(this);
        libraryAdapter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Object tag = buttonView.getTag();
            if (!(tag instanceof String id)) return;
            if (isChecked) selectedAlbumIds.add(id); else selectedAlbumIds.remove(id);
            updateLibrarySelectionUi();
        });
        libraryAdapter.setThumbnailRequester(this::requestThumbnailForItem);
        libraryList.setAdapter(libraryAdapter);
        libraryList.setOnItemClickListener((parent, view, position, id) -> {
            LibraryListRow row = libraryAdapter.rowAt(position);
            if (row instanceof LibraryListRow.Item itemRow) setActiveLibraryItem(itemRow.albumItem, false);
        });
        libraryList.setOnItemLongClickListener((parent, view, position, id) -> {
            LibraryListRow row = libraryAdapter.rowAt(position);
            if (!(row instanceof LibraryListRow.Item itemRow)) return false;
            if (selectedAlbumIds.contains(itemRow.albumItem.id)) selectedAlbumIds.remove(itemRow.albumItem.id);
            else selectedAlbumIds.add(itemRow.albumItem.id);
            updateLibrarySelectionUi();
            return true;
        });
        libraryList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }
            @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                prefetchVisibleThumbnails(firstVisibleItem, visibleItemCount);
            }
        });
        LinearLayout.LayoutParams listParams = wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, dp(340));
        listParams.topMargin = dp(10);
        left.addView(libraryList, listParams);

        LinearLayout right = column();
        right.setBackground(rounded(PANEL, 18));
        right.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView previewLabel = text("预览 / 下载", 14, TEXT);
        previewLabel.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(previewLabel);

        libraryPreview = new PreviewView(this);
        libraryPreview.setReview(true);
        libraryPreview.setPlaceholder("选择一组照片", "支持按天分组、JPEG+RAW 成组显示、查看原图/RAW 预览");
        libraryPreview.setBackground(rounded(0xFF080C11, 18));
        libraryPreview.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, wide ? 0 : dp(260));
        if (wide) previewParams.weight = 1f;
        previewParams.topMargin = dp(10);
        right.addView(libraryPreview, previewParams);
        libraryMeta = text("尚未读取相机照片", 12, MUTED);
        right.addView(libraryMeta, spaced(-1, -2, 10));

        libraryFolder = text(downloadFolderUri == null ? "下载目录：未选择" : "下载目录：" + folderLabel(downloadFolderUri), 11, MUTED);
        right.addView(libraryFolder, spaced(-1, -2, 8));
        libraryChooseFolder = button("选择下载目录", false, v -> pickDownloadFolder(null));
        right.addView(libraryChooseFolder, spaced(-1, dp(48), 8));

        libraryLoadOriginal = button("查看原图 / RAW 预览", false, v -> promptPreviewChoice());
        right.addView(libraryLoadOriginal, spaced(-1, dp(48), 8));

        LinearLayout downloadRow = row();
        libraryDownloadCurrent = button("下载当前分组", true, v -> {
            if (activeLibraryItem != null) requestDownload(activeLibraryItem.assets);
        });
        libraryDownloadSelected = button("后台批量下载所选", true, v -> requestDownload(selectedEntries()));
        downloadRow.addView(libraryDownloadCurrent, new LinearLayout.LayoutParams(0, dp(52), 1));
        downloadRow.addView(libraryDownloadSelected, new LinearLayout.LayoutParams(0, dp(52), 1));
        right.addView(downloadRow, spaced(-1, -2, 10));

        if (wide) {
            body.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -1, 1);
            rightParams.setMarginStart(dp(12));
            body.addView(right, rightParams);
            overlay.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            body.addView(left, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(-1, -2);
            rightParams.topMargin = dp(12);
            body.addView(right, rightParams);
            overlay.addView(scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        return overlay;
    }

    private void addPropertyRow(LinearLayout parent, int first, String firstLabel, int second, String secondLabel) {
        LinearLayout row = row();
        for (int i = 0; i < 2; i++) {
            int property = i == 0 ? first : second;
            String label = i == 0 ? firstLabel : secondLabel;
            Button button = button(label + "\n—", false, v -> selectProperty(property, label));
            button.setTag(label);
            properties.put(property, button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1);
            if (i == 0) params.setMarginEnd(dp(8));
            row.addView(button, params);
        }
        parent.addView(row, spaced(-1, -2, 10));
    }

    @Override public void onStateChanged() {
        if (connectionState == null) return;
        Camera camera = controller.camera();
        boolean connected = controller.connected();
        String state = switch (controller.status) {
            case DISCONNECTED -> "未连接";
            case PERMISSION -> "等待 USB 授权";
            case CONNECTING -> "连接中";
            case CONNECTED -> "USB 已连接";
            case ERROR -> "连接异常";
        };
        connectionState.setText("● " + state);
        connectionState.setTextColor(controller.status == CameraController.Status.ERROR ? 0xFFFF9A8B : ACCENT);
        deviceName.setText(connected ? camera.getDeviceName() : "连接你的相机");
        message.setText(controller.message);
        connect.setText(connected ? "断开连接" : "连接相机");
        connect.setEnabled(controller.status != CameraController.Status.PERMISSION && controller.status != CameraController.Status.CONNECTING);
        live.setEnabled(connected && camera.isLiveViewSupported() && !controller.liveTransition && !controller.bulb);
        live.setText(controller.liveTransition ? "切换中…" : controller.live() ? "停止实时取景" : "开启实时取景");
        focus.setEnabled(connected && camera.isAutoFocusSupported() && !controller.focusPending && !controller.capturePending);
        focus.setText(controller.focusPending ? "对焦中…" : "自动对焦");
        near.setEnabled(connected && controller.live() && camera.isDriveLensSupported());
        far.setEnabled(near.isEnabled());
        shutter.setEnabled(connected && !controller.liveTransition && (!controller.capturePending || controller.bulb));
        shutter.setText(controller.bulb ? "■  结束曝光" : controller.capturePending ? "拍摄中…" : "◎  拍摄");
        review.setEnabled(controller.lastPhoto != null);
        review.setText(reviewing ? "返回取景" : "查看照片");
        libraryButton.setEnabled(connected);
        preview.setReview(reviewing);
        preview.setBitmap(reviewing ? controller.lastPhoto : controller.live() ? controller.frame : null);
        preview.setPlaceholder(connected ? "相机已就绪" : "你的下一张作品，从这里开始",
                connected ? "开启实时取景，或打开照片库浏览相机存储卡" : "USB 连接 · Canon / Nikon");
        photoInfo.setText(controller.lastPhoto == null ? "拍摄后的 JPEG 可在这里查看"
                : "已接收 " + controller.shotCount + " 张 · " + controller.lastFilename);
        metadata.setText(connected ? "模式 " + propertyText(camera, Camera.Property.ShootingMode)
                + "  ·  " + (controller.live() ? "LIVE VIEW" : "USB PTP") : "USB PTP · 远程控制");
        for (Map.Entry<Integer, Button> entry : properties.entrySet()) {
            int property = entry.getKey();
            Button button = entry.getValue();
            button.setText(button.getTag() + "\n" + (connected ? propertyText(camera, property) : "—"));
            button.setEnabled(connected && !controller.bulb && camera.isSettingPropertyPossible(property)
                    && camera.getPropertyDesc(property).length > 0);
        }
        if (resumed && controller.live()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateLibraryButtons();
    }

    @Override public void onFrame(Bitmap bitmap) { if (!reviewing) preview.setBitmap(bitmap); }

    private String propertyText(Camera camera, int property) {
        int value = camera.getProperty(property);
        if (value == Integer.MAX_VALUE) return "—";
        String formatted = camera.propertyToString(property, value);
        return formatted == null || formatted.isEmpty() || formatted.equals("?") ? "0x" + Integer.toHexString(value) : formatted;
    }

    private void chooseDevice() {
        if (controller.connected()) { controller.disconnect(); return; }
        List<UsbDevice> devices = controller.devices();
        if (devices.isEmpty()) { showGuide(); return; }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice d = devices.get(i);
            String name = d.getProductName();
            labels[i] = (name == null ? "Canon / Nikon 相机" : name) + "\nUSB " + d.getDeviceName();
        }
        new AlertDialog.Builder(this).setTitle("选择 USB 相机").setItems(labels,
                (dialog, which) -> controller.connect(devices.get(which))).setNegativeButton("取消", null).show();
    }

    private void selectProperty(int property, String title) {
        Camera camera = controller.camera();
        if (!controller.connected() || !camera.isSettingPropertyPossible(property)) return;
        int[] values = camera.getPropertyDesc(property).clone();
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = camera.propertyToString(property, values[i]);
            if (labels[i] == null || labels[i].isEmpty() || labels[i].equals("?")) labels[i] = "0x" + Integer.toHexString(values[i]);
            if (values[i] == camera.getProperty(property)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, checked, (dialog, which) -> {
            if (controller.connected() && controller.camera() == camera && camera.isSettingPropertyPossible(property))
                camera.setProperty(property, values[which]);
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private void driveLens(int direction) {
        Camera camera = controller.camera();
        if (controller.connected() && camera.isDriveLensSupported() && controller.live()) camera.driveLens(direction, Camera.DriveLens.Soft);
    }

    private void showGuide() {
        new AlertDialog.Builder(this).setTitle("让相机连接到手机")
                .setMessage("1. 使用支持数据传输的 USB 线和 OTG 转接头。\n\n2. 开启相机，将 USB 模式设置为 PTP / 电脑遥控，关闭相机 Wi‑Fi。\n\n3. 点击「连接相机」，选择设备并允许 USB 访问。\n\n4. 打开「照片库」，可以按日期浏览相机卡内容，JPEG+RAW 会自动配对显示成一组。\n\n5. 点击「查看原图 / RAW 预览」可查看 JPEG 原图或 RAW 内嵌预览，点击下载可保存原文件到手机。\n\n批量下载支持在后台继续进行；缩略图按需懒加载，适合大量照片卡。")
                .setPositiveButton("知道了", null).show();
    }

    private void openLibrary() {
        if (!controller.connected()) {
            Toast.makeText(this, "请先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        libraryOverlay.setVisibility(View.VISIBLE);
        if (libraryEntries.isEmpty()) refreshLibrary();
        else {
            updateLibraryButtons();
            prefetchVisibleThumbnails(libraryList.getFirstVisiblePosition(), libraryList.getChildCount());
        }
    }

    private void closeLibrary() {
        libraryOverlay.setVisibility(View.GONE);
    }

    private void refreshLibrary() {
        if (!controller.connected()) {
            libraryStatus.setText("请先连接相机");
            return;
        }
        libraryStatus.setText("正在从相机读取照片元数据…");
        libraryEntries.clear();
        libraryItems.clear();
        selectedAlbumIds.clear();
        activeLibraryItem = null;
        libraryLargePreview = null;
        libraryLargePreviewHandle = -1;
        pendingThumbnailHandles.clear();
        libraryAdapter.replace(Collections.emptyList());
        updateLibrarySelectionUi();

        controller.loadMediaLibrary(new CameraController.MediaLibraryCallback() {
            @Override public void onStarted() {
                libraryRefresh.setEnabled(false);
                libraryStatus.setText("正在从相机读取照片元数据…");
            }

            @Override public void onProgress(int loaded, int total) {
                libraryStatus.setText(total <= 0 ? "相机中没有可识别的图片" : "已读取元数据 " + loaded + " / " + total + "（缩略图将按需加载）");
            }

            @Override public void onLoaded(List<CameraMediaEntry> entries) {
                libraryRefresh.setEnabled(true);
                libraryEntries.clear();
                libraryEntries.addAll(entries);
                libraryItems.clear();
                libraryItems.addAll(CameraAlbumItem.mergePairs(entries));
                libraryAdapter.replace(buildLibraryRows(libraryItems));
                libraryStatus.setText(libraryItems.isEmpty() ? "未发现可浏览的 JPEG / RAW"
                        : "已按日期分组，共 " + libraryItems.size() + " 组 / " + libraryEntries.size() + " 个文件");
                if (!libraryItems.isEmpty()) {
                    setActiveLibraryItem(libraryItems.get(0), false);
                    prefetchVisibleThumbnails(0, Math.min(12, libraryAdapter.getCount()));
                } else {
                    libraryPreview.setBitmap(null);
                    libraryPreview.setPlaceholder("未找到相机照片", "请确认存储卡中存在 JPEG / RAW 图片");
                    libraryMeta.setText("当前照片库为空");
                }
                updateLibrarySelectionUi();
            }

            @Override public void onError(String error) {
                libraryRefresh.setEnabled(true);
                libraryStatus.setText(error);
                Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG).show();
                updateLibraryButtons();
            }
        });
    }

    private List<LibraryListRow> buildLibraryRows(List<CameraAlbumItem> items) {
        List<LibraryListRow> rows = new ArrayList<>();
        String currentHeader = null;
        int currentCount = 0;
        int headerIndex = -1;
        for (CameraAlbumItem item : items) {
            String header = item.dayTitle();
            if (!header.equals(currentHeader)) {
                rows.add(new LibraryListRow.Header(header, 0));
                headerIndex = rows.size() - 1;
                currentHeader = header;
                currentCount = 0;
            }
            rows.add(new LibraryListRow.Item(item));
            currentCount++;
            rows.set(headerIndex, new LibraryListRow.Header(header, currentCount));
        }
        return rows;
    }

    private void requestThumbnailForItem(CameraAlbumItem item) {
        if (item == null) return;
        CameraMediaEntry cover = item.cover();
        if (cover == null || cover.thumbnail != null || !pendingThumbnailHandles.add(cover.handle)) return;
        controller.loadThumbnail(cover, new CameraController.ThumbnailCallback() {
            @Override public void onLoaded(CameraMediaEntry entry, Bitmap bitmap) {
                pendingThumbnailHandles.remove(entry.handle);
                libraryAdapter.notifyDataSetChanged();
                if (activeLibraryItem != null && activeLibraryItem.cover() != null
                        && activeLibraryItem.cover().handle == entry.handle && libraryLargePreviewHandle != entry.handle) {
                    libraryPreview.setBitmap(bitmap);
                }
            }

            @Override public void onError(CameraMediaEntry entry, String message) {
                pendingThumbnailHandles.remove(entry.handle);
            }
        });
    }

    private void prefetchVisibleThumbnails(int firstVisibleItem, int visibleItemCount) {
        int end = Math.min(libraryAdapter.getCount(), firstVisibleItem + Math.max(visibleItemCount, 0) + 8);
        for (int position = Math.max(0, firstVisibleItem); position < end; position++) {
            LibraryListRow row = libraryAdapter.rowAt(position);
            if (row instanceof LibraryListRow.Item itemRow) requestThumbnailForItem(itemRow.albumItem);
        }
    }

    private void setActiveLibraryItem(CameraAlbumItem item, boolean preserveLargePreview) {
        activeLibraryItem = item;
        if (item == null) {
            libraryPreview.setBitmap(null);
            libraryMeta.setText("未选择照片");
            libraryAdapter.setActiveId(null);
            updateLibraryButtons();
            return;
        }
        libraryAdapter.setActiveId(item.id);
        CameraMediaEntry cover = item.cover();
        Bitmap previewBitmap = preserveLargePreview && cover != null && libraryLargePreviewHandle == cover.handle ? libraryLargePreview : null;
        if (previewBitmap == null && cover != null && libraryLargePreviewHandle == cover.handle) previewBitmap = libraryLargePreview;
        if (previewBitmap == null) previewBitmap = item.coverBitmap();
        libraryPreview.setBitmap(previewBitmap);
        updateLibraryMeta(item, item.hasRaw() && item.hasJpeg()
                ? "这是一组 JPEG + RAW。可分别查看 JPEG 原图或 RAW 内嵌预览，下载时会把两者都保存到手机。"
                : item.hasRaw() ? "这是一组 RAW 文件。应用内显示内嵌预览，下载后可在专业应用中查看原始数据。"
                : "这是一组 JPEG 文件。可查看更高质量原图并下载到手机。");
        if (cover != null && previewBitmap == null) requestThumbnailForItem(item);
        if (cover != null && libraryLargePreviewHandle != cover.handle && !preserveLargePreview) libraryStatus.setText("已选择 " + item.title());
        updateLibraryButtons();
    }

    private void updateLibraryMeta(CameraAlbumItem item, String note) {
        libraryMeta.setText(item.detailText() + (note == null || note.isEmpty() ? "" : "\n\n提示：" + note));
    }

    private void updateLibrarySelectionUi() {
        libraryAdapter.setSelectedIds(selectedAlbumIds);
        int groups = selectedAlbumIds.size();
        int files = selectedEntries().size();
        librarySelection.setText(groups == 0 ? "未选择照片"
                : "已选择 " + groups + " 组 / " + files + " 个文件");
        updateLibraryButtons();
    }

    private void updateLibraryButtons() {
        boolean visible = libraryOverlay != null && libraryOverlay.getVisibility() == View.VISIBLE;
        boolean connected = controller != null && controller.connected();
        boolean hasEntries = !libraryItems.isEmpty();
        boolean hasActive = activeLibraryItem != null;
        if (libraryRefresh != null) libraryRefresh.setEnabled(connected);
        if (librarySelectAll != null) librarySelectAll.setEnabled(hasEntries);
        if (libraryClear != null) libraryClear.setEnabled(!selectedAlbumIds.isEmpty());
        if (libraryDownloadCurrent != null) libraryDownloadCurrent.setEnabled(connected && hasActive);
        if (libraryDownloadSelected != null) libraryDownloadSelected.setEnabled(connected && !selectedAlbumIds.isEmpty());
        if (libraryLoadOriginal != null) libraryLoadOriginal.setEnabled(connected && hasActive);
        if (libraryChooseFolder != null) libraryChooseFolder.setEnabled(visible);
        if (libraryFolder != null) libraryFolder.setText(downloadFolderUri == null ? "下载目录：未选择" : "下载目录：" + folderLabel(downloadFolderUri));
    }

    private List<CameraMediaEntry> selectedEntries() {
        if (selectedAlbumIds.isEmpty()) return Collections.emptyList();
        List<CameraMediaEntry> entries = new ArrayList<>();
        for (CameraAlbumItem item : libraryItems) if (selectedAlbumIds.contains(item.id)) entries.addAll(item.assets);
        return entries;
    }

    private void promptPreviewChoice() {
        if (activeLibraryItem == null) return;
        List<CharSequence> labels = new ArrayList<>();
        List<CameraMediaEntry> choices = new ArrayList<>();
        CameraMediaEntry jpeg = activeLibraryItem.jpegAsset();
        CameraMediaEntry raw = activeLibraryItem.rawAsset();
        if (jpeg != null) {
            labels.add("查看 JPEG 原图");
            choices.add(jpeg);
        }
        if (raw != null) {
            labels.add("查看 RAW 预览");
            choices.add(raw);
        }
        if (choices.isEmpty()) return;
        if (choices.size() == 1) {
            loadOriginalPreview(choices.get(0));
            return;
        }
        new AlertDialog.Builder(this).setTitle("选择预览内容")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> loadOriginalPreview(choices.get(which)))
                .setNegativeButton("取消", null).show();
    }

    private void loadOriginalPreview(CameraMediaEntry entry) {
        Size target = new Size(Math.max(dp(320), libraryPreview.getWidth()), Math.max(dp(320), libraryPreview.getHeight()));
        controller.loadLargePreview(entry, target, new CameraController.MediaPreviewCallback() {
            @Override public void onStarted(CameraMediaEntry current) {
                libraryStatus.setText("正在读取 " + current.displayName() + " …");
                libraryLoadOriginal.setEnabled(false);
            }

            @Override public void onLoaded(CameraMediaEntry current, Bitmap bitmap, String note) {
                libraryLoadOriginal.setEnabled(true);
                if (activeLibraryItem == null) return;
                libraryLargePreview = bitmap;
                libraryLargePreviewHandle = current.handle;
                libraryPreview.setBitmap(bitmap);
                libraryStatus.setText(note);
                updateLibraryMeta(activeLibraryItem, note + "（当前显示：" + current.displayName() + "）");
            }

            @Override public void onError(CameraMediaEntry current, String error) {
                libraryLoadOriginal.setEnabled(true);
                libraryStatus.setText(error);
                Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestDownload(List<CameraMediaEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            Toast.makeText(this, "请先选择要下载的照片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloadFolderUri == null) {
            ArrayList<String> ids = new ArrayList<>();
            if (activeLibraryItem != null && entries.equals(activeLibraryItem.assets)) ids.add(activeLibraryItem.id);
            else ids.addAll(selectedAlbumIds);
            pickDownloadFolder(ids);
            return;
        }
        controller.downloadMedia(downloadFolderUri, entries, new CameraController.DownloadCallback() {
            @Override public void onStarted(int totalFiles, long totalBytes) {
                libraryStatus.setText("开始后台下载，共 " + totalFiles + " 个文件 · " + CameraMediaEntry.humanBytes(totalBytes));
                libraryDownloadCurrent.setEnabled(false);
                libraryDownloadSelected.setEnabled(false);
            }

            @Override public void onFileStarted(int index, int totalFiles, String filename, long fileBytes, long totalBytes) {
                libraryStatus.setText("[" + index + "/" + totalFiles + "] " + filename + " · " + CameraMediaEntry.humanBytes(fileBytes));
            }

            @Override public void onFileProgress(int index, int totalFiles, String filename, long fileProgressBytes, long fileBytes,
                                                 long completedBytes, long totalBytes, long bytesPerSecond, String stage) {
                String speed = bytesPerSecond <= 0 ? "" : " · " + CameraMediaEntry.humanBytes(bytesPerSecond) + "/s";
                libraryStatus.setText("[" + index + "/" + totalFiles + "] " + filename + " · " + stage + " "
                        + CameraMediaEntry.humanBytes(fileProgressBytes) + " / " + CameraMediaEntry.humanBytes(fileBytes)
                        + speed + " · 总计 " + CameraMediaEntry.humanBytes(completedBytes) + " / " + CameraMediaEntry.humanBytes(totalBytes));
            }

            @Override public void onCompleted(int successCount, int totalFiles, long totalBytes, List<String> errors) {
                libraryDownloadCurrent.setEnabled(true);
                libraryDownloadSelected.setEnabled(true);
                if (errors.isEmpty()) {
                    libraryStatus.setText("后台下载完成：成功 " + successCount + " / " + totalFiles + " · " + CameraMediaEntry.humanBytes(totalBytes));
                    Toast.makeText(getApplicationContext(), "下载完成", Toast.LENGTH_LONG).show();
                } else {
                    libraryStatus.setText("下载结束：成功 " + successCount + " / " + totalFiles + "，失败 " + errors.size());
                    new AlertDialog.Builder(MainActivity.this).setTitle("部分下载失败")
                            .setMessage(String.join("\n", errors))
                            .setPositiveButton("知道了", null).show();
                }
            }

            @Override public void onError(String msg) {
                libraryDownloadCurrent.setEnabled(true);
                libraryDownloadSelected.setEnabled(true);
                libraryStatus.setText(msg);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void pickDownloadFolder(ArrayList<String> albumIdsAfterPick) {
        pendingDownloadAlbumIds = albumIdsAfterPick;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DOWNLOAD_FOLDER);
    }

    private void exportPreview() {
        if (controller.lastPhoto == null) { Toast.makeText(this, "请先拍摄一张 JPEG 照片", Toast.LENGTH_SHORT).show(); return; }
        exportBitmap = controller.lastPhoto;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg").putExtra(Intent.EXTRA_TITLE, "RemoteCam-" + System.currentTimeMillis() + "-preview.jpg");
        startActivityForResult(intent, EXPORT_PREVIEW);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_PREVIEW) {
            Bitmap bitmap = exportBitmap;
            exportBitmap = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || bitmap == null) return;
            Uri uri = data.getData();
            android.content.ContentResolver resolver = getApplicationContext().getContentResolver();
            exports.execute(() -> {
                String result;
                try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
                    if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IllegalStateException("写入失败");
                    result = "JPEG 预览已导出";
                } catch (Exception e) { result = "导出失败：" + e.getMessage(); }
                String notification = result;
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), notification, Toast.LENGTH_LONG).show());
            });
            return;
        }
        if (requestCode == PICK_DOWNLOAD_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingDownloadAlbumIds = null;
                return;
            }
            downloadFolderUri = data.getData();
            final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(downloadFolderUri, flags); } catch (RuntimeException ignored) { }
            getPreferences(MODE_PRIVATE).edit().putString("download_tree_uri", downloadFolderUri.toString()).apply();
            updateLibraryButtons();
            if (pendingDownloadAlbumIds != null && !pendingDownloadAlbumIds.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>(pendingDownloadAlbumIds);
                pendingDownloadAlbumIds = null;
                List<CameraMediaEntry> entries = new ArrayList<>();
                for (CameraAlbumItem item : libraryItems) if (ids.contains(item.id)) entries.addAll(item.assets);
                requestDownload(entries);
            }
        }
    }

    private String folderLabel(Uri treeUri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            int slash = id.lastIndexOf(':');
            return slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
        } catch (RuntimeException e) {
            return treeUri.toString();
        }
    }

    private void handleUsbIntent(Intent intent) {
        UsbDevice device = CameraController.deviceExtra(intent);
        if (device != null && CameraController.findInterface(device) != null
                && controller.devices().stream().anyMatch(d -> d.getDeviceName().equals(device.getDeviceName()))) controller.connect(device);
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleUsbIntent(intent); }
    @Override protected void onResume() { super.onResume(); resumed = true; controller.setForeground(true); controller.observe(this); }

    @Override protected void onPause() {
        resumed = false;
        if (!isChangingConfigurations()) controller.setForeground(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
    }

    @Override public Object onRetainNonConfigurationInstance() { controller.observe(null); return controller; }

    @Override protected void onDestroy() {
        if (!isChangingConfigurations() && !controller.hasBackgroundWork()) controller.close();
        exports.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private ScrollView scroll(View child) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(child); return scroll; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private Button button(String value, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(primary ? BG : TEXT); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        android.content.res.ColorStateList colors = new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? 0xFF28433F : 0xFF222A35, primary ? ACCENT : 0xFF222D3C});
        button.setBackgroundTintList(colors); button.setOnClickListener(listener);
        return button;
    }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.topMargin = dp(top); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
