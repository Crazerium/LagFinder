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
    public static final String[] SERVER = { "###########", "#.........#", "#.##....#.#", "#.........#",
        "###########", "#.........#", "#.##....#.#", "#.........#", "###########" };

    /** The client: a monitor on a stand. */
    public static final String[] MONITOR = { "###########", "#.........#", "#.........#", "#.........#",
        "#.........#", "###########", "....###....", "..#######.." };

    public static final String[] CLOCK = { "...#####...", "..#.....#..", ".#...#...#.", "#....#....#",
        "#....#....#", "#....###..#", "#.........#", ".#.......#.", "..#.....#..", "...#####..." };

    public static final String[] CLOSE = { // a cross
        "##.....##", ".##...##.", "..##.##..", "...###...", "...###...", "..##.##..", ".##...##.", "##.....##" };

    public static final String[] LOGS = { // a page of text
        "#######..", "#.....##.", "#.###..##", "#.......#", "#.#####.#", "#.......#", "#.####..#", "#.......#",
        "#.#####.#", "#.......#", "#########" };

    public static final String[] PLAY = { "##.....", "####...", "######.", "#######", "######.", "####...",
        "##....." };

    public static final String[] STOP = { "#######", "#######", "#######", "#######", "#######", "#######",
        "#######" };

    /** An open section (pointing down) and a closed one (pointing right), before a line that opens and closes. */
    public static final String[] SECTION_OPEN = { "#####", ".###.", "..#.." };
    public static final String[] SECTION_CLOSED = { "#..", "##.", "###", "##.", "#.." };

    // Small icons (7x7).
    public static final String[] SMALL_TELEPORT = { "..###..", ".#...#.", "#..#..#", "#.###.#", "#..#..#",
        ".#...#.", "..###.." };
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
    public static final String[] SMALL_CREEPER = { "#######", "#..#..#", "#..#..#", "###.###", "##...##",
        "##.#.##", "#######" };
    public static final String[] SMALL_CLOCK = { "..###..", ".#.#.#.", "#..#..#", "#..##.#", "#.....#", ".#...#.",
        "..###.." };
    public static final String[] SMALL_BOLT = { "...###", "..###.", ".####.", "######", ".####.", ".###..", "###..." };
    public static final String[] SMALL_GLOBE = { "..###..", ".#.#.#.", "#..#..#", "#######", "#..#..#", ".#.#.#.",
        "..###.." };
    public static final String[] SMALL_FRAME = { "#######", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#",
        "#######" };
    public static final String[] SMALL_GRID = { "#######", "#..#..#", "#..#..#", "#######", "#..#..#", "#..#..#",
        "#######" };
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
    public static final String[] SMALL_COLLAPSE = { "#######", "#.....#", "#.....#", "#.###.#", "#.....#",
        "#.....#", "#######" };
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

    public static int width(String[] icon) {
        int width = 0;
        for (String row : icon) {
            width = Math.max(width, row.length());
        }
        return width;
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
