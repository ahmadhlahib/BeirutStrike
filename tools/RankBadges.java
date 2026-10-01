import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Writes the placeholder rank badges (48x48 vector shields, rank_*.xml) into a res/drawable folder,
 * and badges_preview.png to check them by eye:
 *   java tools/RankBadges.java app/src/main/res/drawable
 * Final artwork can simply replace the drawables under the same names.
 */
public class RankBadges {
    static final String SHIELD = "M9,5 L39,5 Q42,5 42,8 L42,25 C42,35.5 34,41.5 24,45.5 C14,41.5 6,35.5 6,25 L6,8 Q6,5 9,5 Z";
    static final String SHIELD_INNER = "M10.5,7.5 L37.5,7.5 Q39.5,7.5 39.5,9.5 L39.5,25 C39.5,34 32.5,39.3 24,42.8 C15.5,39.3 8.5,34 8.5,25 L8.5,9.5 Q8.5,7.5 10.5,7.5 Z";
    static final String SHIELD_SHINE = "M10.5,7.5 L37.5,7.5 Q39.5,7.5 39.5,9.5 L39.5,18 C30,15 18,15 8.5,18 L8.5,9.5 Q8.5,7.5 10.5,7.5 Z";

    // Metal colour pairs (top, bottom).
    static final String[] SILVER = {"#FFFFFFFF", "#FF9AA6B2"};
    static final String[] GOLD = {"#FFFFF3B0", "#FFD19A12"};
    static final String[] SILVER_RIM = {"#FFE6EBF0", "#FF6E7883"};
    static final String[] GOLD_RIM = {"#FFFFE9A0", "#FF9C6B00"};

    static class Style {
        String[] plate, rim, metal;
        Style(String[] plate, String[] rim, String[] metal) { this.plate = plate; this.rim = rim; this.metal = metal; }
    }
    static final Style ENLISTED = new Style(new String[]{"#FF3E4955", "#FF151B21"}, SILVER_RIM, SILVER);
    static final Style WARRANT = new Style(new String[]{"#FF3F4733", "#FF171C11"}, SILVER_RIM, GOLD);
    static final Style OFFICER = new Style(new String[]{"#FF4B5536", "#FF1C2112"}, GOLD_RIM, GOLD);
    static final Style GENERAL = new Style(new String[]{"#FF8A2323", "#FF2E0808"}, GOLD_RIM, GOLD);
    static final Style ELITE = new Style(new String[]{"#FF2E2E2E", "#FF050505"}, GOLD_RIM, GOLD);
    static final Style LEGEND = new Style(new String[]{"#FF3A2A08", "#FF0A0600"}, GOLD_RIM, GOLD);

    public static void main(String[] a) throws IOException {
        Path out = Paths.get(a[0]);
        write(out, "rank_private", ENLISTED, false, chevrons(1, 18, 22, 7, 4));
        write(out, "rank_private_first_class", ENLISTED, false, chevrons(1, 12, 22, 7, 4) + rocker(28, 11));
        write(out, "rank_corporal", ENLISTED, false, chevrons(2, 12, 22, 7, 4));
        write(out, "rank_sergeant", ENLISTED, false, chevrons(3, 10, 22, 7, 4));
        write(out, "rank_staff_sergeant", ENLISTED, false, chevrons(3, 8, 20, 6, 3.4) + rocker(30, 10));
        write(out, "rank_warrant_officer", WARRANT, false, bar(21, 1));
        write(out, "rank_chief_warrant_officer", WARRANT, false, bar(21, 2));
        write(out, "rank_second_lieutenant", OFFICER, false, star(24, 24, 9));
        write(out, "rank_first_lieutenant", OFFICER, false, star(16.5, 24, 6.5) + star(31.5, 24, 6.5));
        write(out, "rank_captain", OFFICER, false, star(15, 23, 4.4) + star(24, 23, 4.4) + star(33, 23, 4.4));
        write(out, "rank_major", OFFICER, false, cedar(24, 25, 1.25));
        write(out, "rank_lieutenant_colonel", OFFICER, false, cedar(24, 28, 1.0) + star(24, 12.5, 3.6));
        write(out, "rank_colonel", OFFICER, false, cedar(24, 28, 1.0) + star(18, 12.5, 3.4) + star(30, 12.5, 3.4));
        write(out, "rank_brigadier_general", GENERAL, false, cedar(24, 28, 1.0) + stars3(12.5, 3.2));
        write(out, "rank_major_general", GENERAL, false, swords(24, 27, 0.72) + star(24, 12.5, 3.8));
        write(out, "rank_general", GENERAL, false, swords(24, 31, 0.6) + cedar(24, 17.5, 0.62));
        write(out, "rank_field_commander", ELITE, false, swords(24, 28, 0.72) + stars3(12.5, 3.2));
        write(out, "rank_special_forces_commander", ELITE, false, wings(24, 21, 0.82) + dagger(24, 24, 0.8));
        write(out, "rank_supreme_commander", ELITE, false, wreath(24, 25, 12) + cedar(24, 25, 0.72) + star(24, 11, 3.4));
        write(out, "rank_beirut_legend", LEGEND, true, wreath(24, 26, 12) + cedar(24, 26, 0.72) + stars3(11, 2.8));
    }

    static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    static String poly(double[][] pts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pts.length; i++) b.append(i == 0 ? "M" : "L").append(fmt(pts[i][0])).append(',').append(fmt(pts[i][1])).append(' ');
        return b.append("Z ").toString();
    }

    static double[][] transform(double[][] pts, double cx, double cy, double scale, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        double[][] o = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            double x = pts[i][0] * scale, y = pts[i][1] * scale;
            o[i] = new double[]{cx + x * c - y * s, cy + x * s + y * c};
        }
        return o;
    }

    static String chevrons(int n, double top, double w, double h, double t) {
        StringBuilder b = new StringBuilder();
        double gap = t + 3;
        for (int i = 0; i < n; i++) {
            double y = top + i * gap;
            b.append(poly(new double[][]{
                {24 - w / 2, y + h}, {24, y}, {24 + w / 2, y + h}, {24 + w / 2, y + h + t}, {24, y + t}, {24 - w / 2, y + h + t}}));
        }
        return b.toString();
    }

    static String rocker(double y, double half) {
        double l = 24 - half, r = 24 + half;
        return "M" + fmt(l) + "," + fmt(y) + " C" + fmt(l + 4) + "," + fmt(y + 5) + " " + fmt(r - 4) + "," + fmt(y + 5) + " " + fmt(r) + "," + fmt(y)
            + " L" + fmt(r) + "," + fmt(y + 3.5) + " C" + fmt(r - 4) + "," + fmt(y + 8.5) + " " + fmt(l + 4) + "," + fmt(y + 8.5) + " " + fmt(l) + "," + fmt(y + 3.5) + " Z ";
    }

    /** A rounded bar with [dots] holes (drawn with even-odd fill). */
    static String bar(double y, int dots) {
        StringBuilder b = new StringBuilder("M14,Y L34,Y Q36,Y 36,YA L36,YB Q36,YC 34,YC L14,YC Q12,YC 12,YB L12,YA Q12,Y 14,Y Z "
            .replace("YA", fmt(y + 2)).replace("YB", fmt(y + 5)).replace("YC", fmt(y + 7)).replace("Y", fmt(y)));
        for (int i = 0; i < dots; i++) {
            double cx = dots == 1 ? 24 : 19.5 + i * 9;
            b.append(circle(cx, y + 3.5, 1.9));
        }
        return b.toString();
    }

    static String circle(double cx, double cy, double r) {
        return "M" + fmt(cx - r) + "," + fmt(cy) + " A" + fmt(r) + "," + fmt(r) + " 0 1,0 " + fmt(cx + r) + "," + fmt(cy)
            + " A" + fmt(r) + "," + fmt(r) + " 0 1,0 " + fmt(cx - r) + "," + fmt(cy) + " Z ";
    }

    static String star(double cx, double cy, double r) {
        double[][] p = new double[10][];
        for (int i = 0; i < 10; i++) {
            double ang = Math.toRadians(-90 + i * 36);
            double rr = i % 2 == 0 ? r : r * 0.42;
            p[i] = new double[]{cx + Math.cos(ang) * rr, cy + Math.sin(ang) * rr};
        }
        return poly(p);
    }

    static String stars3(double y, double r) {
        return star(24 - r * 3.1, y + 1, r) + star(24, y, r) + star(24 + r * 3.1, y + 1, r);
    }

    /** A Lebanese cedar: three layered tiers of branches over a short trunk, about 22 x 22 at scale 1. */
    static String cedar(double cx, double cy, double s) {
        double[][][] parts = {
            {{0, -11}, {4, -6.5}, {1.5, -7}, {0, -6}, {-1.5, -7}, {-4, -6.5}},
            {{0, -8}, {7.5, -1.5}, {4, -2.5}, {0, -1.5}, {-4, -2.5}, {-7.5, -1.5}},
            {{0, -4}, {11, 4.5}, {6, 3}, {0, 4}, {-6, 3}, {-11, 4.5}},
            {{-1.6, 3.2}, {1.6, 3.2}, {2.2, 10}, {-2.2, 10}},
        };
        StringBuilder b = new StringBuilder();
        for (double[][] p : parts) b.append(poly(transform(p, cx, cy, s, 0)));
        return b.toString();
    }

    static String swords(double cx, double cy, double s) {
        double[][][] sword = {
            {{0, -15}, {1.4, -12.5}, {1.4, 7}, {-1.4, 7}, {-1.4, -12.5}},
            {{-5, 7}, {5, 7}, {5, 9.2}, {-5, 9.2}},
            {{-1, 9.2}, {1, 9.2}, {1, 13.5}, {-1, 13.5}},
        };
        StringBuilder b = new StringBuilder();
        for (double deg : new double[]{-38, 38}) {
            for (double[][] p : sword) b.append(poly(transform(p, cx, cy, s, deg)));
            double[][] pommel = transform(new double[][]{{0, 15}}, cx, cy, s, deg);
            b.append(circle(pommel[0][0], pommel[0][1], 1.5 * s));
        }
        return b.toString();
    }

    static String dagger(double cx, double cy, double s) {
        double[][][] parts = {
            {{0, -16}, {2.2, -12}, {2.2, 6}, {-2.2, 6}, {-2.2, -12}},
            {{-6, 6}, {6, 6}, {6, 8.4}, {-6, 8.4}},
            {{-1.3, 8.4}, {1.3, 8.4}, {1.3, 14}, {-1.3, 14}},
        };
        StringBuilder b = new StringBuilder();
        for (double[][] p : parts) b.append(poly(transform(p, cx, cy, s, 0)));
        return b.append(circle(cx, cy + 15.5 * s, 1.8 * s)).toString();
    }

    /** Two spread wings, feathers stepping down towards the tips. */
    static String wings(double cx, double cy, double s) {
        double[][] right = {{3, -3}, {16, -8}, {15, -5}, {17, -4.5}, {14.5, -1.5}, {16, -0.5}, {12.5, 2}, {13.5, 3}, {9, 4.5}, {3, 3}};
        double[][] left = new double[right.length][];
        for (int i = 0; i < right.length; i++) left[i] = new double[]{-right[right.length - 1 - i][0], right[right.length - 1 - i][1]};
        return poly(transform(right, cx, cy, s, 0)) + poly(transform(left, cx, cy, s, 0));
    }

    /** A laurel wreath: leaves along two arcs open at the top. */
    static String wreath(double cx, double cy, double r) {
        StringBuilder b = new StringBuilder();
        double[][] leaf = {{0, -2.6}, {1.2, 0}, {0, 2.6}, {-1.2, 0}};
        for (int i = 0; i < 7; i++) {
            for (double phi : new double[]{100 + i * 18, 80 - i * 18}) {
                double t = Math.toRadians(phi);
                b.append(poly(transform(leaf, cx + r * Math.cos(t), cy + r * Math.sin(t), 1, phi)));
            }
        }
        return b.toString();
    }

    static String gradient(String[] c, double y0, double y1) {
        return "            <gradient android:type=\"linear\" android:startX=\"24\" android:startY=\"" + fmt(y0) + "\" android:endX=\"24\" android:endY=\"" + fmt(y1) + "\">\n"
            + "                <item android:offset=\"0\" android:color=\"" + c[0] + "\" />\n"
            + "                <item android:offset=\"1\" android:color=\"" + c[1] + "\" />\n"
            + "            </gradient>\n";
    }

    static String gradientPath(String data, String[] c, double y0, double y1, String extra) {
        return "    <path android:pathData=\"" + data.trim() + "\"" + extra + ">\n"
            + "        <aapt:attr name=\"android:fillColor\">\n" + gradient(c, y0, y1) + "        </aapt:attr>\n    </path>\n";
    }

    static void write(Path dir, String name, Style st, boolean glow, String insignia) throws IOException {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<!-- Placeholder badge for this rank (see progression/Rank.kt); replace with final art under the same name. -->\n");
        x.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    xmlns:aapt=\"http://schemas.android.com/aapt\"\n");
        x.append("    android:width=\"48dp\"\n    android:height=\"48dp\"\n    android:viewportWidth=\"48\"\n    android:viewportHeight=\"48\">\n");
        if (glow) {
            x.append("    <path android:pathData=\"" + circle(24, 25, 24).trim() + "\">\n        <aapt:attr name=\"android:fillColor\">\n"
                + "            <gradient android:type=\"radial\" android:centerX=\"24\" android:centerY=\"25\" android:gradientRadius=\"24\">\n"
                + "                <item android:offset=\"0.55\" android:color=\"#99FFC940\" />\n"
                + "                <item android:offset=\"1\" android:color=\"#00FFC940\" />\n"
                + "            </gradient>\n        </aapt:attr>\n    </path>\n");
        }
        // Metal rim, then the plate inside it, then a soft shine across the top.
        x.append(gradientPath(SHIELD, st.rim, 5, 45.5, ""));
        x.append(gradientPath(SHIELD_INNER, st.plate, 7.5, 42.8, ""));
        x.append("    <path android:fillColor=\"#14FFFFFF\" android:pathData=\"" + SHIELD_SHINE + "\" />\n");
        // The insignia: a dark drop shadow, then the metal on top.
        x.append("    <group android:translateX=\"0.5\" android:translateY=\"0.8\">\n");
        x.append("        <path android:fillColor=\"#80000000\" android:pathData=\"" + insignia.trim() + "\" />\n");
        x.append("    </group>\n");
        x.append(gradientPath(insignia, st.metal, 8, 40, ""));
        x.append("</vector>\n");
        Files.write(dir.resolve(name + ".xml"), x.toString().getBytes(StandardCharsets.UTF_8));
        preview(name, st, glow, insignia);
    }

    // ---- Preview sheet (Java2D), to check the badges by eye --------------------------------

    static final List<java.awt.image.BufferedImage> previews = new ArrayList<>();

    static java.awt.Color color(String hex) {
        long v = Long.parseLong(hex.substring(1), 16);
        return new java.awt.Color((int) (v >> 16 & 255), (int) (v >> 8 & 255), (int) (v & 255), (int) (v >> 24 & 255));
    }

    /** Parses the subset of path syntax written here: M L C Q Z and half-circle A. */
    static java.awt.geom.Path2D parse(String d) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_NON_ZERO);
        Scanner s = new Scanner(d.replace(",", " ").replaceAll("([MLCQAZ])", " $1 ")).useLocale(Locale.ROOT);
        double cx = 0, cy = 0;
        while (s.hasNext()) {
            String c = s.next();
            switch (c) {
                case "M": p.moveTo(cx = s.nextDouble(), cy = s.nextDouble()); break;
                case "L": p.lineTo(cx = s.nextDouble(), cy = s.nextDouble()); break;
                case "C": p.curveTo(s.nextDouble(), s.nextDouble(), s.nextDouble(), s.nextDouble(), cx = s.nextDouble(), cy = s.nextDouble()); break;
                case "Q": p.quadTo(s.nextDouble(), s.nextDouble(), cx = s.nextDouble(), cy = s.nextDouble()); break;
                case "A": {
                    double r = s.nextDouble(); s.nextDouble(); s.nextDouble(); s.nextDouble(); int sweep = (int) s.nextDouble();
                    double x = s.nextDouble(), y = s.nextDouble();
                    double mx = (cx + x) / 2, my = (cy + y) / 2;
                    double start = Math.toDegrees(Math.atan2(-(cy - my), cx - mx));
                    p.append(new java.awt.geom.Arc2D.Double(mx - r, my - r, 2 * r, 2 * r, start, sweep == 0 ? 180 : -180, java.awt.geom.Arc2D.OPEN), true);
                    cx = x; cy = y;
                    break;
                }
                case "Z": p.closePath(); break;
                default: throw new IllegalStateException(c);
            }
        }
        return p;
    }

    static void fill(java.awt.Graphics2D g, String d, String[] c, double y0, double y1) {
        g.setPaint(new java.awt.GradientPaint(24, (float) y0, color(c[0]), 24, (float) y1, color(c[1])));
        g.fill(parse(d));
    }

    static void preview(String name, Style st, boolean glow, String insignia) {
        int scale = 5;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(48 * scale, 48 * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color("#FF101418"));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(scale, scale);
        if (glow) {
            g.setPaint(new java.awt.RadialGradientPaint(24f, 25f, 24f, new float[]{0.55f, 1f}, new java.awt.Color[]{color("#99FFC940"), color("#00FFC940")}));
            g.fill(parse(circle(24, 25, 24)));
        }
        fill(g, SHIELD, st.rim, 5, 45.5);
        fill(g, SHIELD_INNER, st.plate, 7.5, 42.8);
        g.setColor(color("#14FFFFFF"));
        g.fill(parse(SHIELD_SHINE));
        g.translate(0.5, 0.8);
        g.setColor(color("#80000000"));
        g.fill(parse(insignia));
        g.translate(-0.5, -0.8);
        fill(g, insignia, st.metal, 8, 40);
        g.dispose();
        previews.add(img);
        if (previews.size() == 20) {
            java.awt.image.BufferedImage sheet = new java.awt.image.BufferedImage(5 * 240, 4 * 240, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D sg = sheet.createGraphics();
            for (int i = 0; i < 20; i++) sg.drawImage(previews.get(i), (i % 5) * 240, (i / 5) * 240, null);
            sg.dispose();
            try { javax.imageio.ImageIO.write(sheet, "png", new File("badges_preview.png")); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }
}
