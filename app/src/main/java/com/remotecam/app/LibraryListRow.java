package com.remotecam.app;

abstract class LibraryListRow {
    static final int TYPE_HEADER = 0;
    static final int TYPE_ITEM = 1;

    abstract int type();

    static final class Header extends LibraryListRow {
        final String title;
        final int count;

        Header(String title, int count) {
            this.title = title;
            this.count = count;
        }

        @Override int type() { return TYPE_HEADER; }
    }

    static final class Item extends LibraryListRow {
        final CameraAlbumItem albumItem;

        Item(CameraAlbumItem albumItem) {
            this.albumItem = albumItem;
        }

        @Override int type() { return TYPE_ITEM; }
    }
}
