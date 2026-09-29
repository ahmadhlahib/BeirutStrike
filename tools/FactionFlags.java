import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the flags of the game's fictional factions into app/src/main/assets/flags/ (one PNG per
 * team id in Teams.kt). Simple, original designs: a coloured field with one white emblem.
 *
 *   java tools/FactionFlags.java        (run from the project root, Java 17+)
 */
public class FactionFlags {
    static final int W = 600, H = 400;
    static final Color WHITE = new Color(0xF5F5F5);
    static final Color DARK = new Color(0x1B1B1B);

    public static void main(String[] args) throws Exception {
        File dir = new File("app/src/main/assets/flags");
        dir.mkdirs();
        write(dir, "evergreen_squad", new Color(0x2E7D32), FactionFlags::pine);
        write(dir, "phoenix_legion", new Color(0xEF6C00), FactionFlags::phoenix);
        write(dir, "corniche_sharks", new Color(0x1565C0), FactionFlags::shark);
        write(dir, "raouche_eagles", new Color(0xC62828), FactionFlags::eagle);
        write(dir, "summit_rangers", new Color(0x00897B), FactionFlags::mountains);
        write(dir, "golden_lions", new Color(0xF9A825), FactionFlags::crown);
    }

    interface Emblem { void draw(Graphics2D g); }

    static void write(File dir, String id, Color field, Emblem emblem) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(field);
        g.fillRect(0, 0, W, H);
        // A darker band top and bottom, like a banner.
        g.setColor(field.darker());
        g.fillRect(0, 0, W, 34);
        g.fillRect(0, H - 34, W, 34);
        emblem.draw(g);
        g.dispose();
        ImageIO.write(img, "png", new File(dir, id + ".png"));
        System.out.println("wrote " + id + ".png");
    }

    static Polygon poly(int... xy) {
        Polygon p = new Polygon();
        for (int i = 0; i < xy.length; i += 2) p.addPoint(xy[i], xy[i + 1]);
        return p;
    }

    /** A pine tree: three layered tiers and a trunk. */
    static void pine(Graphics2D g) {
        g.setColor(WHITE);
        g.fillRect(288, 270, 24, 60);
        g.fill(poly(300, 70, 390, 150, 210, 150));
        g.fill(poly(300, 120, 430, 215, 170, 215));
        g.fill(poly(300, 175, 470, 285, 130, 285));
    }

    /** A phoenix rising: spread wings, a body and a flame tail. */
    static void phoenix(Graphics2D g) {
        g.setColor(WHITE);
        g.fill(poly(300, 150, 120, 90, 180, 170, 110, 200, 300, 230));  // left wing
        g.fill(poly(300, 150, 480, 90, 420, 170, 490, 200, 300, 230));  // right wing
        g.fill(new Ellipse2D.Double(280, 110, 40, 40));                  // head
        g.fill(poly(270, 210, 330, 210, 340, 330, 300, 290, 260, 330));  // tail
        g.setColor(new Color(0xFFD54F));
        g.fill(poly(285, 225, 315, 225, 318, 300, 300, 280, 282, 300)); // flame
    }

    /** A shark fin cutting through two waves. */
    static void shark(Graphics2D g) {
        g.setColor(WHITE);
        GeneralPath fin = new GeneralPath();
        fin.moveTo(230, 250);
        fin.curveTo(260, 180, 300, 110, 360, 80);
        fin.curveTo(345, 150, 350, 210, 390, 250);
        fin.closePath();
        g.fill(fin);
        g.setStroke(new BasicStroke(18, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int row = 0; row < 2; row++) {
            Path2D wave = new Path2D.Double();
            int y = 270 + row * 45;
            wave.moveTo(100, y);
            for (int x = 100; x < 500; x += 80) wave.quadTo(x + 40, y - 30, x + 80, y);
            g.draw(wave);
        }
    }

    /** Eagle wings above a chevron. */
    static void eagle(Graphics2D g) {
        g.setColor(WHITE);
        for (int side = -1; side <= 1; side += 2) {
            for (int f = 0; f < 4; f++) {
                int x0 = 300 + side * 30;
                int x1 = 300 + side * (200 - f * 30);
                int y1 = 90 + f * 30;
                g.fill(poly(x0, 190, x1, y1, x1 + side * -10, y1 + 45, x0, 215));
            }
        }
        g.fill(new Ellipse2D.Double(275, 150, 50, 70));                  // body
        g.fill(poly(180, 330, 300, 250, 420, 330, 420, 355, 300, 280, 180, 355)); // chevron
    }

    /** Two mountain peaks with white snow caps. */
    static void mountains(Graphics2D g) {
        g.setColor(new Color(0x004D40));
        g.fill(poly(90, 310, 230, 130, 370, 310));
        g.fill(poly(250, 310, 380, 100, 510, 310));
        g.setColor(WHITE);
        g.fill(poly(180, 195, 230, 130, 280, 195, 255, 180, 230, 200, 205, 180));
        g.fill(poly(330, 180, 380, 100, 430, 180, 405, 165, 380, 185, 355, 165));
    }

    /** A crown. */
    static void crown(Graphics2D g) {
        g.setColor(DARK);
        g.fill(poly(170, 290, 150, 140, 230, 210, 300, 110, 370, 210, 450, 140, 430, 290));
        g.fillRect(170, 300, 260, 30);
        for (int x : new int[] {150, 300, 450}) g.fill(new Ellipse2D.Double(x - 16, (x == 300 ? 110 : 140) - 32, 32, 32));
    }
}
