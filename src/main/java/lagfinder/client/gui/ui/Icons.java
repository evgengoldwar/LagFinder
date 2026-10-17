package lagfinder.client.gui.ui;

/** Minimal pixel icons, drawn with rectangles in the theme's colors (the same style as Wayfarer's Map). */
public final class Icons {

    private Icons() {}

    /** The mod's mark: a pulse line through a magnifier. */
    public static final String[] LOGO = { "....#####....", "..##.....##..", ".#.........#.", "#...........#",
        "#....#......#", "##..#.#..####", "#.##...##...#", "#...........#", ".#.........#.", "..##.....###.",
        "....#####..###", ".............##" };

    public static final String[] STATS = { // a bar chart
        "........##.", "........##.", "....##..##.", "....##..##.", "##..##..##.", "##..##..##.", "##..##..##.",
        "##..##..##.", "###########" };

    /** The server: a rack of two units with their lights. */
    public static final String[] SERVER = { "###########", "#.........#", "#.##....#.#", "#.........#", "###########",
        "#.........#", "#.##....#.#", "#.........#", "###########" };

    /** The client: a monitor on a stand. */
    public static final String[] MONITOR = { "###########", "#.........#", "#.........#", "#.........#", "#.........#",
        "###########", "....###....", "..#######.." };

    /** Methods: a call tree, a line with calls branching off it. */
    public static final String[] METHODS = { "####.......", ".#.........", ".#####.....", ".#...#.....", ".#...#####.",
        ".#.........", ".#####.....", ".....#.....", ".....#####." };

    /** Objects: a memory chip with its pins. */
    public static final String[] OBJECTS = { "#.#.#.#.#.#", "###########", "#.........#", "#.##...##.#", "#.##...##.#",
        "#.........#", "###########", "#.#.#.#.#.#" };

    /** Allocations: objects bursting into being. */
    public static final String[] ALLOCATIONS = { ".....#.....", "..#..#..#..", "...#.#.#...", "....###....",
        "#####.#####", "....###....", "...#.#.#...", "..#..#..#..", ".....#....." };

    /** A bin, for deleting. */
    public static final String[] SMALL_TRASH = { "..###..", "#######", ".#####.", ".#.#.#.", ".#.#.#.", ".#.#.#.",
        ".#####." };
    /** A folder, for opening it. */
    public static final String[] SMALL_FOLDER = { "###....", "#######", "#.....#", "#.....#", "#.....#", "#.....#",
        "#######" };

    public static final String[] CLOCK = { "...#####...", "..#.....#..", ".#...#...#.", "#....#....#", "#....#....#",
        "#....###..#", "#.........#", ".#.......#.", "..#.....#..", "...#####..." };

    public static final String[] CLOSE = { // a cross
        "##.....##", ".##...##.", "..##.##..", "...###...", "...###...", "..##.##..", ".##...##.", "##.....##" };

    public static final String[] LOGS = { // a page of text
        "#######..", "#.....##.", "#.###..##", "#.......#", "#.#####.#", "#.......#", "#.####..#", "#.......#",
        "#.#####.#", "#.......#", "#########" };

    public static final String[] PLAY = { "##.....", "####...", "######.", "#######", "######.", "####...", "##....." };

    public static final String[] STOP = { "#######", "#######", "#######", "#######", "#######", "#######", "#######" };

    /** An open section (pointing down) and a closed one (pointing right), before a line that opens and closes. */
    public static final String[] SECTION_OPEN = { "#####", ".###.", "..#.." };
    public static final String[] SECTION_CLOSED = { "#..", "##.", "###", "##.", "#.." };
    /** Pointing left: back to the window before. */
    public static final String[] BACK = { "..#", ".##", "###", ".##", "..#" };

    // Small icons (7x7).
    public static final String[] SMALL_TELEPORT = { "..###..", ".#...#.", "#..#..#", "#.###.#", "#..#..#", ".#...#.",
        "..###.." };
    public static final String[] SMALL_EYE = { ".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..",
        "......." };
    public static final String[] SMALL_COPY = { "####...", "#..#...", "#.#####", "#.#...#", "###...#", "..#...#",
        "..#####" };
    public static final String[] SMALL_SORT = { ".#.....", "###....", ".#..###", ".#.....", ".#..##.", ".#.....",
        ".#..#.." };
    public static final String[] SMALL_SEARCH = { ".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.",
        ".....##" };
    public static final String[] SMALL_CUBE = { "..###..", "##...##", "#.###.#", "#..#..#", "#..#..#", ".#.#.#.",
        "..###.." };
    public static final String[] SMALL_CREEPER = { "#######", "#..#..#", "#..#..#", "###.###", "##...##", "##.#.##",
        "#######" };
    public static final String[] SMALL_CLOCK = { "..###..", ".#.#.#.", "#..#..#", "#..##.#", "#.....#", ".#...#.",
        "..###.." };
    public static final String[] SMALL_BOLT = { "...###", "..###.", ".####.", "######", ".####.", ".###..", "###..." };
    public static final String[] SMALL_GLOBE = { "..###..", ".#.#.#.", "#..#..#", "#######", "#..#..#", ".#.#.#.",
        "..###.." };
    public static final String[] SMALL_FRAME = { "#######", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#",
        "#######" };
    public static final String[] SMALL_GRID = { "#######", "#..#..#", "#..#..#", "#######", "#..#..#", "#..#..#",
        "#######" };
    public static final String[] SMALL_NETWORK = { // nodes joined by cables
        "##...##", "##...##", "..#.#..", "...#...", "..#.#..", "##...##", "##...##" };
    public static final String[] SMALL_PERSON = { "..###..", "..###..", "...#...", ".#####.", "...#...", "..#.#..",
        ".#...#." };
    public static final String[] SMALL_SPIKE = { "...#...", "...#...", "..###..", "..#.#..", ".##.##.", ".#...#.",
        "##...##" };
    public static final String[] SMALL_WARN = { "...#...", "..###..", "..#.#..", ".##.##.", ".##.##.", "#######",
        "###.###" };
    public static final String[] SMALL_CHECK = { "......#", ".....##", "#...##.", "##.##..", ".###...", "..#....",
        "......." };
    public static final String[] SMALL_EXPAND = { "#######", "#.....#", "#..#..#", "#.###.#", "#..#..#", "#.....#",
        "#######" };
    public static final String[] SMALL_COLLAPSE = { "#######", "#.....#", "#.....#", "#.###.#", "#.....#", "#.....#",
        "#######" };
    public static final String[] SMALL_CLEAR = { "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.",
        "#.....#" };

    /** A call tree: a line with lines branching off it. */
    public static final String[] SMALL_TREE = { "###....", ".#.....", ".#####.", ".#.....", ".#####.", ".#.....",
        ".#####." };
    /** Hot methods: a flame. */
    public static final String[] SMALL_FLAME = { "...#...", "..##...", "..###..", ".####..", ".#####.", "##.####",
        ".#####." };

    /** Memory: a chip with its pins. */
    public static final String[] SMALL_MEMORY = { "#.#.#.#", "#######", "#.....#", "#.###.#", "#.....#", "#######",
        "#.#.#.#" };

    /** Allocations: a small burst, objects being created. */
    public static final String[] SMALL_ALLOC = { "...#...", ".#.#.#.", "..###..", "###.###", "..###..", ".#.#.#.",
        "...#..." };
    /** A place in the code: a pin. */
    public static final String[] SMALL_METHOD = { ".###.", "#...#", "#.#.#", "#...#", ".###.", "..#..", "..#.." };

    /** Settings: a cog. */
    public static final String[] GEAR = { "....#....", ".#.###.#.", "..#####..", ".##...##.", "###...###", ".##...##.",
        "..#####..", ".#.###.#.", "....#...." };

    /** About: an "i" in a circle. */
    public static final String[] INFO = { "..#####..", ".#.....#.", "#...#...#", "#.......#", "#...#...#", "#...#...#",
        "#...#...#", ".#.....#.", "..#####.." };

    /** Help: a "?" in a circle. */
    public static final String[] HELP = { "..#####..", ".#.....#.", "#..###..#", "#.....#.#", "#....#..#", "#...#...#",
        "#.......#", ".#..#..#.", "..#####.." };

    /** Both sides: a crosshair. */
    public static final String[] TARGET = { "....#....", "..#####..", ".#..#..#.", ".#.....#.", "###.#.###",
        ".#.....#.", ".#..#..#.", "..#####..", "....#...." };

    /** A pin on the map: where. */
    public static final String[] PIN = { ".###.", "#...#", "#.#.#", "#...#", ".#.#.", ".#.#.", "..#.." };

    /** A link leaving the game. */
    public static final String[] SMALL_LINK = { "...####", ".....##", "....#.#", "#..#..#", "#.#....", "#......",
        "#####.." };

    public static final String[] SCAN = { "###...###", "#.......#", "#..###..#", "..#...#..", "..#.#.#..", "..#...#..",
        "#..###..#", "#.......#", "###...###" };

    public static final String[] FUNNEL = { "#########", ".#.....#.", "..#...#..", "...#.#...", "...#.#...",
        "...#.#...", "...#.#...", "...###..." };

    public static final String[] WRENCH = { "......###..", ".....##....", ".....#....#", ".....##..##", "....#.####.",
        "...###.....", "..###......", ".###.......", "###........", ".#........." };

    public static final String[] CUBE = { ".....#.....", "...##.##...", ".##.....##.", "#.##...##.#", "#...###...#",
        "#....#....#", "#....#....#", "#....#....#", ".##..#..##.", "...##.##...", ".....#....." };

    public static final String[] SMALL_SUN = { "...#...", ".#...#.", "..###..", "#.###.#", "..###..", ".#...#.",
        "...#..." };
    public static final String[] SMALL_NEIGHBORS = { "...#...", "..###..", ".#.#.#.", "###.###", ".#.#.#.", "..###..",
        "...#..." };
    public static final String[] SMALL_MOUNTAIN = { ".......", "...#...", "..###..", ".##.##.", ".#...##", "##....#",
        "#######" };
    public static final String[] SMALL_PATH = { "##.....", "##.....", ".......", "...##..", "...##..", ".......",
        ".....##" };
    public static final String[] SMALL_TASKS = { "#.####.", ".......", "#.####.", ".......", "#.####.", ".......",
        "#.###.." };

    public static final String[] SMALL_PIPE = { ".......", "##...##", "#######", "##...##", "...#...", "..###..",
        "...#..." };

    public static int width(String[] icon) {
        int width = 0;
        for (String row : icon) {
            width = Math.max(width, row.length());
        }
        return width;
    }

    /** Draws the icon with its top left corner at (x, y), {@code scale} GUI pixels a side per '#'. */
    public static void draw(String[] icon, int x, int y, int color, int scale) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    Theme.fill(x + start * scale, y + row * scale, x + col * scale, y + (row + 1) * scale, color);
                    start = -1;
                }
            }
        }
    }

    /** Draws the icon with its top left corner at (x, y), one GUI pixel per '#', merging runs into rectangles. */
    public static void draw(String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    Theme.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }
}
