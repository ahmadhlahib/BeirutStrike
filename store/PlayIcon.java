import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Makes the 512x512 Google Play icon from the launcher art: crops inside its rounded frame
 * (Play applies its own rounded mask) and scales it down in steps for a sharp result.
 * Usage: java PlayIcon.java in out x y size
 */
public class PlayIcon {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), s = Integer.parseInt(a[4]);
        img = img.getSubimage(x, y, s, s);
        while (s > 512) {
            s = Math.max(512, s / 2);
            img = scale(img, s);
        }
        ImageIO.write(img, "png", new File(a[1]));
    }

    static BufferedImage scale(BufferedImage src, int s) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, s, s, null);
        g.dispose();
        return out;
    }
}
