import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Fits a phone screenshot to Google Play's 2:1 maximum aspect ratio.
 * Portrait: crops the status bar and navigation bar. Landscape: adds a caption bar on top.
 * Usage: java PrepScreenshot.java in out [caption]
 */
public class PrepScreenshot {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out;
        if (h > w) {
            // Remove the status bar (top 4.5%) and navigation bar (bottom 5.7%) of a 20:9 phone.
            int top = Math.round(h * 0.045f), bottom = Math.round(h * 0.057f);
            out = new BufferedImage(w, h - top - bottom, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(src, 0, -top, null);
            g.dispose();
        } else {
            int newH = (w + 1) / 2, bar = newH - h;
            out = new BufferedImage(w, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x0E0F1A));
            g.fillRect(0, 0, w, bar);
            g.setColor(new Color(0xFF7A1A));
            g.fillRect(0, bar - 4, w, 4);
            if (a.length > 2) {
                g.setFont(new Font("Segoe UI Semibold", Font.PLAIN, (int) (bar * 0.55)));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Color.WHITE);
                g.drawString(a[2], (w - fm.stringWidth(a[2])) / 2, (bar - 4 - fm.getHeight()) / 2 + fm.getAscent());
            }
            g.drawImage(src, 0, bar, null);
            g.dispose();
        }
        ImageIO.write(out, "png", new File(a[1]));
    }
}
