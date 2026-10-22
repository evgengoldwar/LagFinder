package lagfinder.client.gui.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/** The game's font as the mod's screens use it: text drawn at whole GUI pixels, measured and cut to widths. */
public final class FontRenderer {

    public static final FontRenderer INSTANCE = new FontRenderer();
    public final int FONT_HEIGHT = 9;

    private FontRenderer() {}

    public Font font() {
        return Minecraft.getInstance().font;
    }

    public int getStringWidth(String text) {
        return text == null ? 0 : font().width(text);
    }

    public int drawString(String text, int x, int y, int color) {
        return Gfx.g()
            .drawString(font(), text, x, y, color, false);
    }

    public int drawStringWithShadow(String text, int x, int y, int color) {
        return Gfx.g()
            .drawString(font(), text, x, y, color, true);
    }

    public String trimStringToWidth(String text, int width) {
        return font().plainSubstrByWidth(text, Math.max(0, width));
    }

    public String trimStringToWidth(String text, int width, boolean fromEnd) {
        if (!fromEnd) {
            return trimStringToWidth(text, width);
        }
        Font font = font();
        int start = text.length();
        while (start > 0 && font.width(text.substring(start - 1)) <= width) {
            start--;
        }
        return text.substring(start);
    }

    public List<String> listFormattedStringToWidth(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (FormattedText line : font().getSplitter()
            .splitLines(text, Math.max(1, width), Style.EMPTY)) {
            lines.add(line.getString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }
}
