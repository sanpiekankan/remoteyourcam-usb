package com.remotecam.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MediaLibraryAdapter extends BaseAdapter {
    interface ThumbnailRequester {
        void request(CameraAlbumItem item);
    }

    private final Context context;
    private final List<LibraryListRow> rows = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private String activeId;
    private CompoundButton.OnCheckedChangeListener checkedChangeListener;
    private ThumbnailRequester thumbnailRequester;

    MediaLibraryAdapter(Context context) {
        this.context = context;
    }

    void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener listener) {
        this.checkedChangeListener = listener;
    }

    void setThumbnailRequester(ThumbnailRequester requester) {
        this.thumbnailRequester = requester;
    }

    void replace(List<LibraryListRow> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    void setActiveId(String activeId) {
        this.activeId = activeId;
        notifyDataSetChanged();
    }

    void setSelectedIds(Set<String> ids) {
        selectedIds.clear();
        selectedIds.addAll(ids);
        notifyDataSetChanged();
    }

    LibraryListRow rowAt(int position) {
        return rows.get(position);
    }

    @Override public int getCount() { return rows.size(); }
    @Override public LibraryListRow getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) {
        LibraryListRow row = getItem(position);
        return row.type() == LibraryListRow.TYPE_ITEM ? ((LibraryListRow.Item) row).albumItem.id.hashCode() : -position - 1L;
    }

    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return getItem(position).type(); }
    @Override public boolean isEnabled(int position) { return getItemViewType(position) == LibraryListRow.TYPE_ITEM; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return getItemViewType(position) == LibraryListRow.TYPE_HEADER
                ? headerView((LibraryListRow.Header) getItem(position), convertView)
                : itemView((LibraryListRow.Item) getItem(position), convertView);
    }

    private View headerView(LibraryListRow.Header row, View convertView) {
        HeaderHolder holder;
        if (convertView == null) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(6), dp(12), dp(6), dp(8));

            TextView title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(15);
            root.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView count = new TextView(context);
            count.setTextColor(0xFF9AA5B1);
            count.setTextSize(11);
            root.addView(count);
            holder = new HeaderHolder(title, count);
            convertView = root;
            convertView.setTag(holder);
        } else holder = (HeaderHolder) convertView.getTag();

        holder.title.setText(row.title);
        holder.count.setText(row.count + " 组");
        return convertView;
    }

    private View itemView(LibraryListRow.Item row, View convertView) {
        ItemHolder holder;
        if (convertView == null) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(10);
            root.setPadding(pad, pad, pad, pad);

            CheckBox checkBox = new CheckBox(context);
            root.addView(checkBox);

            FrameLayout thumbWrap = new FrameLayout(context);
            LinearLayout.LayoutParams thumbWrapParams = new LinearLayout.LayoutParams(dp(72), dp(72));
            thumbWrapParams.setMarginStart(dp(4));
            ImageView thumb = new ImageView(context);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbWrap.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            TextView thumbPlaceholder = new TextView(context);
            thumbPlaceholder.setTextColor(0xFF9AA5B1);
            thumbPlaceholder.setTextSize(10);
            thumbPlaceholder.setGravity(Gravity.CENTER);
            thumbWrap.addView(thumbPlaceholder, new FrameLayout.LayoutParams(-1, -1));
            root.addView(thumbWrap, thumbWrapParams);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoParams.setMarginStart(dp(12));

            TextView title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(title);

            TextView subtitle = new TextView(context);
            subtitle.setTextColor(0xFF9AA5B1);
            subtitle.setTextSize(11);
            info.addView(subtitle);

            TextView detail = new TextView(context);
            detail.setTextColor(0xFF74808E);
            detail.setTextSize(10);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2, -2);
            detailParams.topMargin = dp(4);
            info.addView(detail, detailParams);

            TextView badge = new TextView(context);
            badge.setTextColor(0xFF0D1117);
            badge.setTextSize(10);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.topMargin = dp(6);
            info.addView(badge, badgeParams);

            root.addView(info, infoParams);
            holder = new ItemHolder(root, checkBox, thumbWrap, thumb, thumbPlaceholder, title, subtitle, detail, badge);
            convertView = root;
            convertView.setTag(holder);
        } else holder = (ItemHolder) convertView.getTag();

        CameraAlbumItem item = row.albumItem;
        holder.root.setBackgroundColor(item.id.equals(activeId) ? 0x3325A18E : Color.TRANSPARENT);
        holder.title.setText(item.title());
        holder.subtitle.setText(item.subtitle());
        holder.detail.setText(item.hasJpeg() && item.hasRaw() ? "已合并显示为一组，可分别查看 JPEG 原图和 RAW 预览" :
                (item.hasRaw() ? "RAW 组，可下载原始文件到手机" : "JPEG 组，可查看更高质量原图"));
        holder.badge.setText(item.badge());
        holder.badge.setBackgroundColor(item.hasRaw() && item.hasJpeg() ? 0xFF5D9DFF : item.hasRaw() ? 0xFFE7A65A : 0xFF5DE0BC);

        Bitmap thumb = item.coverBitmap();
        if (thumb != null && !thumb.isRecycled()) {
            holder.thumb.setImageBitmap(thumb);
            holder.thumbPlaceholder.setText("");
            holder.thumbWrap.setBackgroundColor(Color.TRANSPARENT);
        } else {
            holder.thumb.setImageDrawable(null);
            holder.thumbPlaceholder.setText(item.badge());
            holder.thumbWrap.setBackgroundColor(0xFF202833);
            if (thumbnailRequester != null) thumbnailRequester.request(item);
        }

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(selectedIds.contains(item.id));
        holder.checkBox.setTag(item.id);
        holder.checkBox.setOnCheckedChangeListener(checkedChangeListener);
        return convertView;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private record HeaderHolder(TextView title, TextView count) { }
    private record ItemHolder(LinearLayout root, CheckBox checkBox, FrameLayout thumbWrap, ImageView thumb,
                              TextView thumbPlaceholder, TextView title, TextView subtitle,
                              TextView detail, TextView badge) { }
}
