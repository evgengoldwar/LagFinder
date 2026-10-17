package lagfinder.client.gui.ui;

public final class ScrollbarDrag {

    private boolean dragging;
    private int trackTop;
    private int travel;
    private int grabOffset;
    private int maxScroll;

    public int press(int mouseX, int mouseY, int x, int y0, int y1, int visible, int total, int drawnScroll,
        int maximum) {
        if (total <= visible || maximum <= 0
            || y1 <= y0
            || mouseX < x - 5
            || mouseX >= x + 5
            || mouseY < y0
            || mouseY >= y1) {
            return -1;
        }
        int track = y1 - y0;
        int thumb = Math.min(track, Math.max(12, track * visible / Math.max(visible, total)));
        trackTop = y0;
        travel = track - thumb;
        maxScroll = maximum;
        int clamped = Math.max(0, Math.min(maxScroll, drawnScroll));
        int thumbTop = trackTop + (int) Math.round(travel * clamped / (double) maxScroll);
        grabOffset = mouseY >= thumbTop && mouseY < thumbTop + thumb ? mouseY - thumbTop : thumb / 2;
        dragging = true;
        return position(mouseY);
    }

    public int drag(int mouseY) {
        return dragging ? position(mouseY) : -1;
    }

    public void release() {
        dragging = false;
    }

    private int position(int mouseY) {
        if (travel <= 0) {
            return 0;
        }
        int raw = (int) Math.round((mouseY - trackTop - grabOffset) * maxScroll / (double) travel);
        return Math.max(0, Math.min(maxScroll, raw));
    }
}
