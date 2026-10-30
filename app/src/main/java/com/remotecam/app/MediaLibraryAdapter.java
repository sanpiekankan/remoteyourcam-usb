package com.remotecam.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Square-cell grid adapter for the camera gallery (喔图-style live feed). */
final class MediaLibraryAdapter extends BaseAdapter {
    interface ThumbnailRequester {
        void request(CameraAlbumItem item);
    }

    private final Context context;
    private final List<CameraAlbumItem> items = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private ThumbnailRequester thumbnailRequester;

    MediaLibraryAdapter(Context context) {
        this.context = context;
    }

    void setThumbnailRequester(ThumbnailRequester requester) {
        this.thumbnailRequester = requester;
    }

    void replace(List<CameraAlbumItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    void setSelectedIds(Set<String> ids) {
        selectedIds.clear();
        selectedIds.addAll(ids);
        notifyDataSetChanged();
    }

    CameraAlbumItem itemAt(int position) {
        return items.get(position);
    }

    @Override public int getCount() { return items.size(); }
    @Override public CameraAlbumItem getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return items.get(position).id.hashCode(); }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        CellHolder holder;
        if (convertView == null) {
            SquareCell root = new SquareCell(context);
            root.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            root.setBackgroundColor(0xFF1B2430);

            ImageView thumb = new ImageView(context);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

            TextView placeholder = new TextView(context);
            placeholder.setTextColor(0xFF6E7B8C);
            placeholder.setTextSize(11);
            placeholder.setGravity(Gravity.CENTER);
            root.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

            TextView badge = new TextView(context);
            badge.setTextSize(9);
            badge.setTextColor(0xFF0D1117);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            badgeParams.setMargins(dp(4), dp(4), 0, 0);
            root.addView(badge, badgeParams);

            TextView check = new TextView(context);
            check.setTextSize(11);
            check.setGravity(Gravity.CENTER);
            check.setTextColor(0xFF0D1117);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            int checkSize = dp(20);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(checkSize, checkSize, Gravity.TOP | Gravity.END);
            checkParams.setMargins(0, dp(4), dp(4), 0);
            root.addView(check, checkParams);

            TextView name = new TextView(context);
            name.setTextSize(9);
            name.setTextColor(0xFFE4EBF2);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setPadding(dp(6), dp(3), dp(6), dp(3));
            name.setBackgroundColor(0x77000000);
            root.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            holder = new CellHolder(root, thumb, placeholder, badge, check, name);
            root.setTag(holder);
            convertView = root;
        } else holder = (CellHolder) convertView.getTag();

        CameraAlbumItem item = getItem(position);
        Bitmap bitmap = item.coverBitmap();
        if (bitmap != null && !bitmap.isRecycled()) {
            holder.thumb.setImageBitmap(bitmap);
            holder.placeholder.setText("");
        } else {
            holder.thumb.setImageDrawable(null);
            holder.placeholder.setText(item.badge());
            if (thumbnailRequester != null) thumbnailRequester.request(item);
        }

        holder.badge.setText(item.badge());
        holder.badge.setBackgroundColor(item.hasRaw() && item.hasJpeg() ? 0xFF5D9DFF : item.hasRaw() ? 0xFFE7A65A : 0xFF5DE0BC);

        boolean selected = selectedIds.contains(item.id);
        holder.check.setText(selected ? "✓" : "");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(selected ? 0xFF5DE0BC : 0x55000000);
        if (!selected) circle.setStroke(dp(1), 0x88FFFFFF);
        holder.check.setBackground(circle);
        holder.root.setForeground(selected ? new ColorDrawable(0x2E5DE0BC) : null);

        CameraMediaEntry cover = item.cover();
        String time = cover == null ? "" : cover.timeLabel();
        holder.name.setText(item.title() + (time.isEmpty() ? "" : "  " + time));
        return convertView;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Cell that measures itself as a square based on the grid column width. */
    private static final class SquareCell extends FrameLayout {
        SquareCell(Context context) {
            super(context);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    private record CellHolder(FrameLayout root, ImageView thumb, TextView placeholder,
                              TextView badge, TextView check, TextView name) { }
}
