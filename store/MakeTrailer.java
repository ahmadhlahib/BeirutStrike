import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the Google Play / YouTube trailer (1920 x 1080, 30 fps) from a phone screen recording in
 * the store screenshots' style: each scene gets a gold caption, gameplay sits in a gold frame
 * under it and menus stand on the right like a phone. A title card opens it and an end card
 * closes it; each scene starts with a low "impact" hit over the game's own sound.
 *
 *   java store/MakeTrailer.java <ffmpeg.exe> <recording.mp4> <work folder> <out.mp4>
 *
 * Edit SCENES to re-cut it (times are seconds into the recording). GAME_STRIP is where the
 * landscape game sits in a portrait recording; for a recording made in landscape, set it to the
 * whole frame.
 */
public class MakeTrailer {
    /** Kind ("card", "game" or "menu"), start, length, title, subtitle. */
    static final Object[][] SCENES = {
        {"card", 0.0, 3.2, "BEIRUT STRIKE", "Multiplayer shooter in real Beirut streets"},
        {"game", 145.0, 5.5, "FIGHT IN REAL BEIRUT STREETS", "Downtown, Hamra, the Corniche and more, built from real city maps"},
        {"game", 161.8, 5.2, "SCOPE IN AND SNIPE", "Find a scope in the streets and hit targets from across the city"},
        {"menu", 64.0, 6.0, "12 REAL GUNS", "Pistols, rifles, machine guns and sniper rifles"},
        {"game", 156.0, 5.5, "ONLINE TEAM BATTLES", "Timed matches in first or third person"},
        {"menu", 57.0, 6.5, "PICK YOUR CHARACTER", "Each with a victory dance for when you win"},
        {"menu", 1.5, 6.0, "RISE THROUGH 20 MILITARY RANKS", "Earn XP from Private all the way to Beirut Legend"},
        {"menu", 18.0, 5.0, "CREATE YOUR OWN MATCH", "Pick the map, size and length, add a password or a minimum rank"},
        {"game", 80.0, 5.5, "PLAY WITH FRIENDS", "Create a room and invite them"},
        {"card", 0.0, 4.5, "BEIRUT STRIKE", "FREE ON GOOGLE PLAY"},
    };

    static final int W = 1920, H = 1080, FPS = 30;
    /** The landscape game inside the portrait recording: width, height, x, y. */
    static final int[] GAME_STRIP = {1080, 500, 0, 920};
    /** Status bar and navigation bar cut off menu shots, in pixels of the recording. */
    static final int STATUS_BAR = 105, NAV_BAR = 133;
    static final Color GOLD = new Color(0xE8B64A), GOLD_LIGHT = new Color(0xFFE08A), SILVER = new Color(0xC9D1D9);
    static final double FADE = 0.3;

    public static void main(String[] args) throws Exception {
        String ffmpeg = args[0];
        File recording = new File(args[1]), work = new File(args[2]), out = new File(args[3]);
        work.mkdirs();
        BufferedImage icon = ImageIO.read(new File("store/play_icon.png"));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < SCENES.length; i++) {
            String kind = (String) SCENES[i][0];
            double start = (Double) SCENES[i][1], length = (Double) SCENES[i][2];
            String title = (String) SCENES[i][3], sub = (String) SCENES[i][4];
            File overlay = new File(work, "scene" + i + ".png"), part = new File(work, "scene" + i + ".mp4");
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y"));
            String video;
            if (kind.equals("card")) {
                ImageIO.write(card(icon, title, sub, i == SCENES.length - 1), "png", overlay);
                cmd.addAll(List.of("-loop", "1", "-framerate", "" + FPS, "-t", "" + length, "-i", overlay.getPath(),
                    "-f", "lavfi", "-t", "" + length, "-i", "anullsrc=r=48000:cl=stereo"));
                video = "[0:v]format=yuv420p,setsar=1[v0]";
                cmd.addAll(List.of("-f", "lavfi", "-i", impact(length)));
                cmd.addAll(List.of("-filter_complex", video + ";" + finish(length, "[1:a]")));
            } else {
                int[] hole;
                String crop;
                if (kind.equals("game")) {
                    int fw = 1760, fh = (int) Math.round(fw * GAME_STRIP[1] / (double) GAME_STRIP[0]);
                    hole = new int[]{(W - fw) / 2, 200, fw, fh};
                    crop = "crop=" + GAME_STRIP[0] + ":" + GAME_STRIP[1] + ":" + GAME_STRIP[2] + ":" + GAME_STRIP[3];
                } else {
                    int cropH = 2340 - STATUS_BAR - NAV_BAR, fh = 960, fw = (int) Math.round(fh * 1080 / (double) cropH);
                    hole = new int[]{W - 170 - fw, (H - fh) / 2, fw, fh};
                    crop = "crop=1080:" + cropH + ":0:" + STATUS_BAR;
                }
                ImageIO.write(frame(kind, hole, title, sub), "png", overlay);
                cmd.addAll(List.of("-ss", "" + start, "-t", "" + length, "-i", recording.getPath(),
                    "-loop", "1", "-framerate", "" + FPS, "-t", "" + length, "-i", overlay.getPath(),
                    "-f", "lavfi", "-i", impact(length)));
                video = "[0:v]" + crop + ",scale=" + hole[2] + ":" + hole[3] + ":flags=lanczos,setsar=1,fps=" + FPS + "[shot];"
                    + "color=c=black:s=" + W + "x" + H + ":r=" + FPS + ":d=" + length + "[base];"
                    + "[base][shot]overlay=" + hole[0] + ":" + hole[1] + ":shortest=1[b1];"
                    + "[b1][1:v]overlay=0:0:shortest=1,format=yuv420p[v0]";
                // The game's own sound under gameplay; menus stay quiet.
                String gameAudio = kind.equals("game") ? "[0:a]volume=0.9[ga]" : "[0:a]volume=0.15[ga]";
                cmd.addAll(List.of("-filter_complex", video + ";" + gameAudio + ";" + finish(length, "[ga]")));
            }
            cmd.addAll(List.of("-map", "[v]", "-map", "[a]", "-r", "" + FPS,
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-t", "" + length, part.getPath()));
            run(cmd);
            parts.add(part.getAbsolutePath());
            System.out.println("scene " + i + ": " + title);
        }
        File list = new File(work, "parts.txt");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append("file '").append(p.replace("\\", "/")).append("'\n");
        Files.writeString(list.toPath(), sb.toString());
        run(List.of(ffmpeg, "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list.getPath(),
            "-c", "copy", "-movflags", "+faststart", out.getPath()));
        System.out.println("wrote " + out.getPath());
    }

    /** Fades the picture in and out, and mixes [sound] with the scene's impact hit (input 2). */
    static String finish(double length, String sound) {
        double outAt = length - FADE;
        return "[v0]fade=t=in:st=0:d=" + FADE + ",fade=t=out:st=" + outAt + ":d=" + FADE + "[v];"
            + sound + "aformat=sample_rates=48000:channel_layouts=stereo[s1];"
            + "[2:a]aformat=sample_rates=48000:channel_layouts=stereo[s2];"
            + "[s1][s2]amix=inputs=2:duration=first:normalize=0,afade=t=in:st=0:d=0.05,afade=t=out:st=" + outAt + ":d=" + FADE + "[a]";
    }

    /** A low cinematic hit: a decaying 50 Hz boom with a short burst of noise. */
    static String impact(double length) {
        String e = "0.55*sin(2*PI*48*t)*exp(-4.5*t)+0.25*sin(2*PI*96*t)*exp(-7*t)+0.18*(2*random(0)-1)*exp(-30*t)";
        return "aevalsrc=" + e + "|" + e + ":s=48000:d=" + length;
    }

    static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("ffmpeg failed: " + String.join(" ", cmd));
    }

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
        g.setPaint(new GradientPaint(0, 0, new Color(232, 182, 74, 0), W / 2f, 0, GOLD, true));
        g.fillRect(0, H - 6, W, 6);
        return g;
    }

    static Font title(float size) { return new Font("Bahnschrift", Font.BOLD, 1).deriveFont(size); }
    static Font body(float size) { return new Font("Segoe UI Semibold", Font.PLAIN, 1).deriveFont(size); }

    /** The scene's caption and background, with a see-through rounded hole (gold-edged) for the video. */
    static BufferedImage frame(String kind, int[] hole, String t, String sub) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = background(img, kind.equals("game") ? W / 2f : W * 0.72f, kind.equals("game") ? 0 : H / 2f);
        int radius = kind.equals("game") ? 36 : 44;
        RoundRectangle2D.Float shape = new RoundRectangle2D.Float(hole[0], hole[1], hole[2], hole[3], radius, radius);
        if (kind.equals("game")) {
            g.setFont(title(78f));
            g.setColor(GOLD_LIGHT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(t, (W - fm.stringWidth(t)) / 2, 108);
            g.setFont(body(32f));
            g.setColor(SILVER);
            fm = g.getFontMetrics();
            g.drawString(sub, (W - fm.stringWidth(sub)) / 2, 160);
        } else {
            int left = 150, maxWidth = hole[0] - left - 120;
            g.setFont(title(26f));
            g.setColor(GOLD);
            g.drawString("BEIRUT STRIKE", left, 330);
            g.fillRect(left, 350, 90, 5);
            g.setFont(title(96f));
            g.setColor(GOLD_LIGHT);
            int y = drawWrapped(g, t, left, 460, maxWidth, 104);
            g.setFont(body(36f));
            g.setColor(SILVER);
            drawWrapped(g, sub, left, y + 40, maxWidth, 52);
        }
        // Shadow around the hole, then punch the hole out and edge it in gold.
        for (int i = 18; i > 0; i -= 3) {
            g.setColor(new Color(0, 0, 0, 14));
            g.fill(new RoundRectangle2D.Float(hole[0] - i, hole[1] - i + 10, hole[2] + 2 * i, hole[3] + 2 * i, radius + i, radius + i));
        }
        g.setComposite(AlphaComposite.Clear);
        g.fill(shape);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(4f));
        g.draw(shape);
        g.dispose();
        return img;
    }

    /** Title or end card: the icon, the name and a line under it (gold for the end card). */
    static BufferedImage card(BufferedImage icon, String t, String sub, boolean end) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background(img, W / 2f, H / 2f);
        int s = 300, ix = (W - s) / 2, iy = end ? 170 : 210;
        Shape clip = new RoundRectangle2D.Float(ix, iy, s, s, 64, 64);
        g.setClip(clip);
        g.drawImage(icon, ix, iy, s, s, null);
        g.setClip(null);
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(4f));
        g.draw(clip);
        g.setFont(title(130f));
        g.setColor(GOLD_LIGHT);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(t, (W - fm.stringWidth(t)) / 2, iy + s + 150);
        g.setFont(end ? title(56f) : body(40f));
        g.setColor(end ? GOLD : SILVER);
        fm = g.getFontMetrics();
        g.drawString(sub, (W - fm.stringWidth(sub)) / 2, iy + s + 230);
        if (end) {
            // The middle dot is built from its code so this file stays plain ASCII.
            String dot = "  " + (char) 0xB7 + "  ";
            String small = "No ads" + dot + "No sign-up" + dot + "Play online with friends";
            g.setFont(body(34f));
            g.setColor(SILVER);
            fm = g.getFontMetrics();
            g.drawString(small, (W - fm.stringWidth(small)) / 2, iy + s + 300);
        }
        g.dispose();
        return img;
    }

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
