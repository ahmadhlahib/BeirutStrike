import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Makes the Google Play screenshots (1920 x 1080, 16:9) from raw phone screenshots in store/raw/:
 * each gets a caption in the game's gunmetal-and-gold style. Gameplay shots (landscape) sit under
 * the caption; menu shots (portrait) stand on the right like a phone, the caption on the left.
 *
 *   java store/MakeScreenshots.java store/raw store/screenshots
 *
 * Change the SHOTS list to pick other raw files or captions.
 */
public class MakeScreenshots {
    /** Output name, raw file (in the raw folder), title, subtitle. */
    static final String[][] SHOTS = {
        {"01_beirut_streets", "20261005_3d_person_street.jpeg", "FIGHT IN A LIVING BEIRUT", "Real streets with traffic and people, built from real city maps"},
        {"02_real_guns", "20261005_gun_view_taxi.jpeg", "12 REAL GUNS", "Pistols, rifles, machine guns and sniper rifles, each with its own feel"},
        {"03_scope", "20261005_scope_street.jpeg", "SCOPE IN AND SNIPE", "Zoom in up to 12x and hit targets from across the city"},
        {"04_team_battles", "20261005_scope_enemy.jpeg", "ONLINE TEAM BATTLES", "Timed matches with friends, with team voice chat"},
        {"05_ranks", "Screenshot_20261002_101157_Beirut Strike.jpg", "RISE THROUGH 20 MILITARY RANKS", "Earn XP with every kill, headshot and win, from Private to Beirut Legend"},
        {"06_loadout", "20261005_loadout.jpeg", "BUILD YOUR LOADOUT", "Choose a pistol, a primary and a sniper rifle before every match"},
        {"07_characters", "20261005_character_ali.jpeg", "PICK YOUR FIGHTER", "In army camouflage, with a victory dance for when you win"},
        {"08_rooms", "Screenshot_20261002_101229_Beirut Strike.jpg", "CREATE YOUR OWN MATCH", "Pick the map, size and length, add a password or a minimum rank"},
        {"09_living_city", "20261005_people_corniche.jpeg", "A CITY THAT'S ALIVE", "Cars drive the streets, people walk the sidewalks and run when the shooting starts"},
        {"10_rooftops", "20261005_rooftop.jpeg", "TAKE THE ROOFTOPS", "Climb the ladders and cover the streets from above"},
        {"11_solo", "20261006_solo_setup.jpg", "PLAY SOLO AGAINST BOTS", "Pick the map, the length and 1 to 8 bots, Easy, Medium or Hard"},
        {"12_store", "20261006_store_shop.jpg", "ARMS STORES IN EVERY MAP", "Magazines, scopes, grenades and medkits at the counter"},
        {"13_enemy_areas", "20261006_map_enemy_areas.jpg", "KNOW WHERE THE ENEMY IS, ROUGHLY", "Red circles on the map, never their exact spot; green $ for the stores"},
    };

    static final int W = 1920, H = 1080;
    static final Color GOLD = new Color(0xE8B64A), GOLD_LIGHT = new Color(0xFFE08A), SILVER = new Color(0xC9D1D9);
    /** Phone status bar and navigation bar, as shares of a 2340 px screen side, cut off the raw shots. */
    static final double STATUS_BAR = 0.045, NAV_BAR = 0.057;

    public static void main(String[] args) throws Exception {
        File raw = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        for (String[] s : SHOTS) {
            BufferedImage shot = ImageIO.read(new File(raw, s[1]));
            BufferedImage img = shot.getWidth() > shot.getHeight() ? landscape(shot, s[2], s[3]) : portrait(shot, s[2], s[3]);
            File f = new File(out, s[0] + ".png");
            ImageIO.write(img, "png", f);
            System.out.println(f.getPath() + "  " + img.getWidth() + "x" + img.getHeight());
        }
    }

    /** Gunmetal background with a soft gold glow, and the small game name. */
    static Graphics2D background(BufferedImage img, float glowX, float glowY) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1E2830), 0, H, new Color(0x07090C)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(glowX, glowY, 900f, new float[]{0f, 1f},
            new Color[]{new Color(232, 182, 74, 60), new Color(232, 182, 74, 0)}));
        g.fillRect(0, 0, W, H);
        // Thin gold line along the bottom.
        g.setPaint(new GradientPaint(0, 0, new Color(232, 182, 74, 0), W / 2f, 0, GOLD, true));
        g.fillRect(0, H - 6, W, 6);
        return g;
    }

    /** The screenshot with rounded corners, a gold edge and a drop shadow. */
    static void framed(Graphics2D g, BufferedImage shot, int x, int y, int w, int h, int radius) {
        for (int i = 18; i > 0; i -= 3) {
            g.setColor(new Color(0, 0, 0, 14));
            g.fill(new RoundRectangle2D.Float(x - i, y - i + 10, w + 2 * i, h + 2 * i, radius + i, radius + i));
        }
        Shape clip = new RoundRectangle2D.Float(x, y, w, h, radius, radius);
        Shape old = g.getClip();
        g.setClip(clip);
        g.drawImage(shot, x, y, w, h, null);
        g.setClip(old);
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(4f));
        g.draw(clip);
    }

    static Font title(float size) { return new Font("Bahnschrift", Font.BOLD, 1).deriveFont(size); }
    static Font body(float size) { return new Font("Segoe UI Semibold", Font.PLAIN, 1).deriveFont(size); }

    /** Gameplay: caption across the top, the shot below it (cropped to the frame's shape). */
    static BufferedImage landscape(BufferedImage shot, String t, String sub) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background(img, W / 2f, 0);
        g.setFont(title(78f));
        g.setColor(GOLD_LIGHT);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(t, (W - fm.stringWidth(t)) / 2, 108);
        g.setFont(body(32f));
        g.setColor(SILVER);
        fm = g.getFontMetrics();
        g.drawString(sub, (W - fm.stringWidth(sub)) / 2, 160);
        int fw = 1760, fh = 840, fx = (W - fw) / 2, fy = 196;
        // Crop the shot to the frame's 2.1:1 shape, keeping its middle.
        double want = (double) fw / fh;
        int sw = shot.getWidth(), sh = shot.getHeight();
        int cw = (int) Math.min(sw, Math.round(sh * want)), ch = (int) Math.min(sh, Math.round(sw / want));
        BufferedImage crop = shot.getSubimage((sw - cw) / 2, (sh - ch) / 2, cw, ch);
        framed(g, crop, fx, fy, fw, fh, 36);
        g.dispose();
        return img;
    }

    /** Menus: the shot stands on the right like a phone, the caption on the left. */
    static BufferedImage portrait(BufferedImage shot, String t, String sub) {
        int top = (int) Math.round(shot.getHeight() * STATUS_BAR), bottom = (int) Math.round(shot.getHeight() * NAV_BAR);
        BufferedImage crop = shot.getSubimage(0, top, shot.getWidth(), shot.getHeight() - top - bottom);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background(img, W * 0.72f, H / 2f);
        int fh = 960, fw = (int) Math.round(fh * crop.getWidth() / (double) crop.getHeight());
        int fx = W - 170 - fw, fy = (H - fh) / 2;
        framed(g, crop, fx, fy, fw, fh, 44);

        int left = 150, maxWidth = fx - left - 120;
        g.setFont(new Font("Bahnschrift", Font.BOLD, 1).deriveFont(26f));
        g.setColor(GOLD);
        g.drawString("BEIRUT STRIKE", left, 330);
        g.fillRect(left, 350, 90, 5);
        g.setFont(title(96f));
        g.setColor(GOLD_LIGHT);
        int y = drawWrapped(g, t, left, 460, maxWidth, 104);
        g.setFont(body(36f));
        g.setColor(SILVER);
        drawWrapped(g, sub, left, y + 40, maxWidth, 52);
        g.dispose();
        return img;
    }

    /** Draws [text] word-wrapped to [maxWidth]; returns the baseline below the last line. */
    static int drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(next) > maxWidth && line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) { g.drawString(line.toString(), x, y); y += lineHeight; }
        return y;
    }
}
