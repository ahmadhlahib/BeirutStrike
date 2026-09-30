import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Turns the Sketchfab gun models (glTF, see README "Gun models") into the game's small .gun files.
 *
 *   java -cp gson.jar tools/GunModelTool.java preview <models dir> <out.png>
 *   java -cp gson.jar tools/GunModelTool.java pack <models dir> <assets/guns3d dir> [preview.png]
 *
 * <models dir> holds one folder per gun, each the unzipped Sketchfab download (scene.gltf, its
 * .bin and textures). "preview" draws every model from three sides as downloaded, to see which
 * way it points; "pack" turns each one to point the game's way (along -z, y up), scales it to the
 * real gun's length, places it so its grip is where the game's hands hold it (see GUNS), and
 * writes <gun>.gun plus a small base-colour texture if it has one.
 *
 * .gun format (little-endian): "GUN1"; floats gripY, gripZ, supportZ, muzzleY, muzzleZ (metres,
 * gun space); int texture count, then each texture's file name (UTF, short length); int part
 * count, then per part: int colour (ARGB), int texture (-1 = none), int vertex count, that many
 * × 8 floats (x y z, normal x y z, u v), int index count, that many unsigned shorts.
 */
public class GunModelTool {

  /**
   * How to place each model. fwd/up: the model's axes that point to the muzzle and up, as
   * downloaded (e.g. "+x"); length: the real gun, metres; grip/support: where along the gun
   * (0 = muzzle, 1 = back of the stock or grip) and how high (0 = bottom, 1 = top) the hands go;
   * muzzleH: the barrel's height at the muzzle (0..1).
   */
  record Placement(String fwd, String up, float length, float gripAlong, float gripHeight,
                   float supportAlong, float muzzleHeight) {}

  static final Map<String, Placement> GUNS = new LinkedHashMap<>();

  /**
   * Where the game's hands hold each gun (gripY, gripZ in gun space, as in city/GunModels.kt), so
   * a model sits in the hands exactly where the box-built gun did.
   */
  static final Map<String, float[]> HAND = new HashMap<>();

  static void gun(String name, String fwd, String up, float length, float gripAlong, float gripHeight,
                  float supportAlong, float muzzleHeight, float handY, float handZ) {
    GUNS.put(name, new Placement(fwd, up, length, gripAlong, gripHeight, supportAlong, muzzleHeight));
    HAND.put(name, new float[]{handY, handZ});
  }

  static {
    //  name       fwd   up    length grip along/height support muzzle  hand y, z
    gun("m9",      "+x", "+y", 0.217f, 0.85f, 0.35f,  0.85f, 0.85f,   -0.06f,  0.015f);
    gun("glock17", "+z", "+y", 0.204f, 0.80f, 0.40f,  0.80f, 0.85f,   -0.06f,  0.013f);
    gun("deagle",  "+x", "-z", 0.270f, 0.83f, 0.35f,  0.83f, 0.85f,   -0.065f, 0.018f);
    gun("ak47",    "-y", "-z", 0.943f, 0.72f, 0.42f,  0.34f, 0.82f,   -0.085f, 0.10f);
    gun("m4",      "-y", "-z", 0.838f, 0.65f, 0.40f,  0.35f, 0.72f,   -0.09f,  0.12f);
    gun("mp5",     "-x", "+y", 0.680f, 0.50f, 0.40f,  0.20f, 0.72f,   -0.085f, 0.09f);
    gun("rpk",     "-y", "-z", 1.040f, 0.72f, 0.45f,  0.36f, 0.85f,   -0.085f, 0.10f);
    gun("m249",    "-y", "-z", 1.040f, 0.68f, 0.42f,  0.40f, 0.70f,   -0.10f,  0.13f);
    gun("svd",     "-y", "-z", 1.225f, 0.81f, 0.38f,  0.42f, 0.80f,   -0.085f, 0.13f);
    gun("m24",     "-y", "-z", 1.092f, 0.66f, 0.45f,  0.38f, 0.85f,   -0.085f, 0.13f);
    gun("awm",     "+x", "-z", 1.200f, 0.77f, 0.30f,  0.45f, 0.66f,   -0.09f,  0.15f);
    gun("m82",     "+x", "+y", 1.448f, 0.78f, 0.33f,  0.45f, 0.52f,   -0.11f,  0.19f);
  }

  /** Largest texture side kept: plenty for a gun on a phone screen. */
  static final int TEXTURE_SIZE = 512;

  // ---- Reading glTF ---------------------------------------------------------------------------

  /** A colour or texture; [sleeve]: drawn in the team's uniform colour (written with alpha 0). */
  static final class Material { int color = 0xFFB0B0B0; BufferedImage texture; boolean sleeve; }
  static final class Tri { float[] v = new float[24]; Material m; String node = ""; } // 3 × (pos, normal, uv); the node it came from

  static final class Model {
    String name; List<Tri> tris = new ArrayList<>();
    float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
    float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    void bounds() {
      Arrays.fill(min, Float.MAX_VALUE); Arrays.fill(max, -Float.MAX_VALUE);
      for (Tri t : tris) for (int k = 0; k < 3; k++) for (int a = 0; a < 3; a++) {
        min[a] = Math.min(min[a], t.v[k * 8 + a]); max[a] = Math.max(max[a], t.v[k * 8 + a]);
      }
    }
  }

  static Model load(File dir) throws IOException {
    File gltf = new File(dir, "scene.gltf");
    JsonObject j = JsonParser.parseString(Files.readString(gltf.toPath())).getAsJsonObject();
    List<ByteBuffer> buffers = new ArrayList<>();
    for (JsonElement b : j.getAsJsonArray("buffers"))
      buffers.add(ByteBuffer.wrap(Files.readAllBytes(new File(dir, b.getAsJsonObject().get("uri").getAsString()).toPath())).order(ByteOrder.LITTLE_ENDIAN));
    List<Material> materials = new ArrayList<>();
    Map<Integer, BufferedImage> images = new HashMap<>();
    if (j.has("materials")) for (JsonElement e : j.getAsJsonArray("materials")) {
      Material m = new Material();
      JsonObject pbr = e.getAsJsonObject().has("pbrMetallicRoughness") ? e.getAsJsonObject().getAsJsonObject("pbrMetallicRoughness") : new JsonObject();
      if (pbr.has("baseColorFactor")) {
        JsonArray c = pbr.getAsJsonArray("baseColorFactor");
        // Linear → sRGB, as glTF colours are linear and the game's shader works in sRGB.
        int r = srgb(c.get(0).getAsFloat()), g = srgb(c.get(1).getAsFloat()), b = srgb(c.get(2).getAsFloat());
        m.color = 0xFF000000 | (r << 16) | (g << 8) | b;
      }
      // Older exports keep the colour in the specular-glossiness extension, as a "diffuse" colour.
      JsonObject ext = e.getAsJsonObject().has("extensions") ? e.getAsJsonObject().getAsJsonObject("extensions") : new JsonObject();
      if (ext.has("KHR_materials_pbrSpecularGlossiness")) {
        JsonObject sg = ext.getAsJsonObject("KHR_materials_pbrSpecularGlossiness");
        if (sg.has("diffuseFactor")) {
          JsonArray c = sg.getAsJsonArray("diffuseFactor");
          m.color = 0xFF000000 | (srgb(c.get(0).getAsFloat()) << 16) | (srgb(c.get(1).getAsFloat()) << 8) | srgb(c.get(2).getAsFloat());
        }
        if (sg.has("diffuseTexture") && !pbr.has("baseColorTexture")) pbr.add("baseColorTexture", sg.get("diffuseTexture"));
      }
      if (pbr.has("baseColorTexture")) {
        int tex = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
        int img = j.getAsJsonArray("textures").get(tex).getAsJsonObject().get("source").getAsInt();
        m.texture = images.computeIfAbsent(img, i -> {
          String uri = j.getAsJsonArray("images").get(i).getAsJsonObject().get("uri").getAsString();
          try { return ImageIO.read(new File(dir, java.net.URLDecoder.decode(uri, "UTF-8"))); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        });
      }
      materials.add(m);
    }
    Model model = new Model();
    model.name = dir.getName();
    JsonObject scene = j.getAsJsonArray("scenes").get(j.has("scene") ? j.get("scene").getAsInt() : 0).getAsJsonObject();
    for (JsonElement n : scene.getAsJsonArray("nodes")) walk(j, buffers, materials, n.getAsInt(), identity(), model);
    model.bounds();
    return model;
  }

  static int srgb(float linear) {
    double v = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
  }

  static void walk(JsonObject j, List<ByteBuffer> buffers, List<Material> materials, int index, double[] parent, Model out) {
    JsonObject node = j.getAsJsonArray("nodes").get(index).getAsJsonObject();
    double[] m = mul(parent, local(node));
    if (node.has("mesh")) {
      for (JsonElement pe : j.getAsJsonArray("meshes").get(node.get("mesh").getAsInt()).getAsJsonObject().getAsJsonArray("primitives")) {
        JsonObject p = pe.getAsJsonObject();
        if (p.has("mode") && p.get("mode").getAsInt() != 4) continue; // triangles only
        JsonObject at = p.getAsJsonObject("attributes");
        float[] pos = floats(j, buffers, at.get("POSITION").getAsInt());
        float[] nor = at.has("NORMAL") ? floats(j, buffers, at.get("NORMAL").getAsInt()) : null;
        float[] uv = at.has("TEXCOORD_0") ? floats(j, buffers, at.get("TEXCOORD_0").getAsInt()) : null;
        int vc = pos.length / 3;
        int[] idx;
        if (p.has("indices")) { float[] f = floats(j, buffers, p.get("indices").getAsInt()); idx = new int[f.length]; for (int i = 0; i < f.length; i++) idx[i] = (int) f[i]; }
        else { idx = new int[vc]; for (int i = 0; i < vc; i++) idx[i] = i; }
        Material mat = p.has("material") ? materials.get(p.get("material").getAsInt()) : new Material();
        double[] nm = normalMatrix(m);
        for (int t = 0; t + 2 < idx.length; t += 3) {
          Tri tri = new Tri(); tri.m = mat; tri.node = node.has("name") ? node.get("name").getAsString() : "";
          for (int k = 0; k < 3; k++) {
            int v = idx[t + k];
            double[] wp = apply(m, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], 1);
            double[] wn = nor != null ? apply(nm, nor[v * 3], nor[v * 3 + 1], nor[v * 3 + 2], 0) : new double[]{0, 1, 0};
            double len = Math.sqrt(wn[0] * wn[0] + wn[1] * wn[1] + wn[2] * wn[2]); if (len == 0) len = 1;
            tri.v[k * 8] = (float) wp[0]; tri.v[k * 8 + 1] = (float) wp[1]; tri.v[k * 8 + 2] = (float) wp[2];
            tri.v[k * 8 + 3] = (float) (wn[0] / len); tri.v[k * 8 + 4] = (float) (wn[1] / len); tri.v[k * 8 + 5] = (float) (wn[2] / len);
            tri.v[k * 8 + 6] = uv != null ? uv[v * 2] : 0; tri.v[k * 8 + 7] = uv != null ? uv[v * 2 + 1] : 0;
          }
          out.tris.add(tri);
        }
      }
    }
    if (node.has("children")) for (JsonElement c : node.getAsJsonArray("children")) walk(j, buffers, materials, c.getAsInt(), m, out);
  }

  /** An accessor's values as floats (normalised integers scaled to 0..1). */
  static float[] floats(JsonObject j, List<ByteBuffer> buffers, int accessor) {
    JsonObject a = j.getAsJsonArray("accessors").get(accessor).getAsJsonObject();
    JsonObject bv = j.getAsJsonArray("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
    ByteBuffer b = buffers.get(bv.get("buffer").getAsInt());
    int comps = switch (a.get("type").getAsString()) { case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; default -> 16; };
    int type = a.get("componentType").getAsInt();
    int size = switch (type) { case 5126, 5125 -> 4; case 5123, 5122 -> 2; default -> 1; };
    int count = a.get("count").getAsInt();
    int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : comps * size;
    int base = (bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
    boolean norm = a.has("normalized") && a.get("normalized").getAsBoolean();
    float[] out = new float[count * comps];
    for (int i = 0; i < count; i++) for (int c = 0; c < comps; c++) {
      int o = base + i * stride + c * size;
      float v = switch (type) {
        case 5126 -> b.getFloat(o);
        case 5125 -> (float) (b.getInt(o) & 0xFFFFFFFFL);
        case 5123 -> norm ? (b.getShort(o) & 0xFFFF) / 65535f : b.getShort(o) & 0xFFFF;
        case 5122 -> norm ? Math.max(b.getShort(o) / 32767f, -1f) : b.getShort(o);
        case 5121 -> norm ? (b.get(o) & 0xFF) / 255f : b.get(o) & 0xFF;
        default -> norm ? Math.max(b.get(o) / 127f, -1f) : b.get(o);
      };
      out[i * comps + c] = v;
    }
    return out;
  }

  // ---- Matrices (column-major 4×4, like glTF) ------------------------------------------------

  static double[] identity() { double[] m = new double[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }

  static double[] local(JsonObject node) {
    if (node.has("matrix")) { double[] m = new double[16]; JsonArray a = node.getAsJsonArray("matrix"); for (int i = 0; i < 16; i++) m[i] = a.get(i).getAsDouble(); return m; }
    double[] t = {0, 0, 0}, r = {0, 0, 0, 1}, s = {1, 1, 1};
    if (node.has("translation")) for (int i = 0; i < 3; i++) t[i] = node.getAsJsonArray("translation").get(i).getAsDouble();
    if (node.has("rotation")) for (int i = 0; i < 4; i++) r[i] = node.getAsJsonArray("rotation").get(i).getAsDouble();
    if (node.has("scale")) for (int i = 0; i < 3; i++) s[i] = node.getAsJsonArray("scale").get(i).getAsDouble();
    double x = r[0], y = r[1], z = r[2], w = r[3];
    double[] m = {
      (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0,
      (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0,
      (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
      t[0], t[1], t[2], 1,
    };
    return m;
  }

  /** A node's world transform: its parents' transforms and its own, found by searching from the scene roots. */
  static double[] worldOf(JsonObject j, int target) {
    JsonArray nodes = j.getAsJsonArray("nodes");
    Map<Integer, Integer> parent = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      JsonObject n = nodes.get(i).getAsJsonObject();
      if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) parent.put(c.getAsInt(), i);
    }
    double[] m = local(nodes.get(target).getAsJsonObject());
    for (Integer p = parent.get(target); p != null; p = parent.get(p)) m = mul(local(nodes.get(p).getAsJsonObject()), m);
    return m;
  }

  /** Undoes a node's own transform (walk() applies it again). */
  static double[] inverseLocal(JsonObject node) {
    double[] m = local(node), inv = identity();
    double[] n = normalMatrix(m); // inverse-transpose of the 3×3
    // inverse 3×3 = transpose of the inverse-transpose
    inv[0] = n[0]; inv[1] = n[4]; inv[2] = n[8]; inv[4] = n[1]; inv[5] = n[5]; inv[6] = n[9]; inv[8] = n[2]; inv[9] = n[6]; inv[10] = n[10];
    double[] t = apply(inv, m[12], m[13], m[14], 0);
    inv[12] = -t[0]; inv[13] = -t[1]; inv[14] = -t[2];
    return inv;
  }

  static double[] mul(double[] a, double[] b) {
    double[] o = new double[16];
    for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) { double s = 0; for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k]; o[c * 4 + r] = s; }
    return o;
  }

  static double[] apply(double[] m, double x, double y, double z, double w) {
    return new double[]{m[0] * x + m[4] * y + m[8] * z + m[12] * w, m[1] * x + m[5] * y + m[9] * z + m[13] * w, m[2] * x + m[6] * y + m[10] * z + m[14] * w};
  }

  /** Inverse-transpose of the upper 3×3 (enough for normals). */
  static double[] normalMatrix(double[] m) {
    double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
    double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (Math.abs(det) < 1e-12) return m;
    double[] o = identity();
    // Inverse-transpose: cofactors / det, laid out column-major.
    o[0] = (e * i - f * h) / det; o[4] = -(d * i - f * g) / det; o[8] = (d * h - e * g) / det;
    o[1] = -(b * i - c * h) / det; o[5] = (a * i - c * g) / det; o[9] = -(a * h - b * g) / det;
    o[2] = (b * f - c * e) / det; o[6] = -(a * f - c * d) / det; o[10] = (a * e - b * d) / det;
    return o;
  }

  // ---- Previews -------------------------------------------------------------------------------

  /** Colour of a triangle for the previews: its texture at its centre, or its material colour. */
  static int colorOf(Tri t) {
    if (t.m.texture == null) return t.m.color;
    float u = (t.v[6] + t.v[14] + t.v[22]) / 3, v = (t.v[7] + t.v[15] + t.v[23]) / 3;
    BufferedImage img = t.m.texture;
    int x = Math.floorMod((int) (u * img.getWidth()), img.getWidth()), y = Math.floorMod((int) (v * img.getHeight()), img.getHeight());
    return img.getRGB(x, y);
  }

  /** Draws [m] into a cell seen along axis [along] (0 = x, 1 = y, 2 = z), flat-shaded, painter's order. */
  static void drawView(Graphics2D g, Model m, int along, int ox, int oy, int w, int h, String label) {
    int ha = along == 0 ? 2 : 0, va = along == 1 ? 2 : 1; // screen axes
    float sx = m.max[ha] - m.min[ha], sy = m.max[va] - m.min[va];
    float s = Math.min((w - 16) / Math.max(sx, 1e-6f), (h - 30) / Math.max(sy, 1e-6f));
    float cx = ox + w / 2f, cy = oy + 18 + (h - 18) / 2f;
    float mx = (m.min[ha] + m.max[ha]) / 2, my = (m.min[va] + m.max[va]) / 2;
    List<Tri> tris = new ArrayList<>(m.tris);
    tris.sort(Comparator.comparingDouble(t -> -(t.v[along] + t.v[8 + along] + t.v[16 + along])));
    for (Tri t : tris) {
      Path2D.Float p = new Path2D.Float();
      for (int k = 0; k < 3; k++) {
        float px = cx + (t.v[k * 8 + ha] - mx) * s, py = cy - (t.v[k * 8 + va] - my) * s;
        if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
      }
      p.closePath();
      float nx = t.v[3], ny = t.v[4], nz = t.v[5];
      float light = 0.45f + 0.55f * Math.abs(0.4f * nx + 0.8f * ny + 0.45f * nz);
      int c = colorOf(t);
      g.setColor(new Color(Math.min(255, (int) (((c >> 16) & 255) * light)), Math.min(255, (int) (((c >> 8) & 255) * light)), Math.min(255, (int) ((c & 255) * light))));
      g.fill(p);
    }
    g.setColor(Color.WHITE); g.setFont(new Font("Segoe UI", Font.BOLD, 13));
    g.drawString(label, ox + 6, oy + 14);
  }

  static void preview(List<Model> models, File out, String[] axisNames) throws IOException {
    int W = 300, H = 170;
    BufferedImage img = new BufferedImage(W * 3, H * models.size(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x1B2027)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
    for (int i = 0; i < models.size(); i++) {
      Model m = models.get(i);
      String size = String.format("%.2f x %.2f x %.2f", m.max[0] - m.min[0], m.max[1] - m.min[1], m.max[2] - m.min[2]);
      for (int a = 0; a < 3; a++) {
        drawView(g, m, a, a * W, i * H, W, H, m.name + "  " + axisNames[a] + (a == 0 ? "   " + size + "  " + m.tris.size() + " tris" : ""));
      }
      g.setColor(new Color(0x33FFFFFF, true)); g.drawRect(0, i * H, img.getWidth() - 1, H - 1);
    }
    ImageIO.write(img, "png", out);
  }

  // ---- Orienting ------------------------------------------------------------------------------

  /** An axis as [index, sign], e.g. "+x" → {0, 1}. */
  static int[] axis(String s) { return new int[]{"xyz".indexOf(s.charAt(1)), s.charAt(0) == '-' ? -1 : 1}; }

  /**
   * Guesses which way a gun points: the longest side is its length, the muzzle end is the thinner
   * end (the stock or grip makes the other end bulky), the second longest side is its height,
   * and up is away from the grip and magazine, which hang below the barrel. Returns {fwd, up}.
   */
  static String[] guessAxes(Model m) {
    Integer[] order = {0, 1, 2};
    Arrays.sort(order, (p, q) -> Float.compare(m.max[q] - m.min[q], m.max[p] - m.min[p]));
    int len = order[0], hgt = order[1];
    float lo = m.min[len], span = m.max[len] - m.min[len];
    // Height covered by the geometry in the first and last 15% of the length.
    float[] endMin = {Float.MAX_VALUE, Float.MAX_VALUE}, endMax = {-Float.MAX_VALUE, -Float.MAX_VALUE};
    double[] endSum = {0, 0}; int[] endN = {0, 0};
    for (Tri t : m.tris) for (int k = 0; k < 3; k++) {
      float along = (t.v[k * 8 + len] - lo) / span, h = t.v[k * 8 + hgt];
      int end = along < 0.15f ? 0 : along > 0.85f ? 1 : -1;
      if (end < 0) continue;
      endMin[end] = Math.min(endMin[end], h); endMax[end] = Math.max(endMax[end], h); endSum[end] += h; endN[end]++;
    }
    boolean muzzleAtMax = (endMax[1] - endMin[1]) < (endMax[0] - endMin[0]);
    int muzzleEnd = muzzleAtMax ? 1 : 0;
    // The barrel sits high: the muzzle end's average height is above the middle of the whole gun.
    float mid = (m.min[hgt] + m.max[hgt]) / 2;
    boolean upIsMax = endN[muzzleEnd] == 0 || endSum[muzzleEnd] / endN[muzzleEnd] >= mid;
    String names = "xyz";
    return new String[]{(muzzleAtMax ? "+" : "-") + names.charAt(len), (upIsMax ? "+" : "-") + names.charAt(hgt)};
  }

  /** Turns the model so [fwd] points along -z and [up] along +y (a rotation, never a mirror). */
  static void orient(Model m, String fwd, String up) {
    int[] f = axis(fwd), u = axis(up);
    double[] F = new double[3], U = new double[3];
    F[f[0]] = f[1]; U[u[0]] = u[1];
    double[] R = {F[1] * U[2] - F[2] * U[1], F[2] * U[0] - F[0] * U[2], F[0] * U[1] - F[1] * U[0]}; // right = fwd × up
    for (Tri t : m.tris) for (int k = 0; k < 3; k++) for (int part = 0; part < 2; part++) {
      int o = k * 8 + part * 3;
      double x = t.v[o], y = t.v[o + 1], z = t.v[o + 2];
      t.v[o] = (float) (R[0] * x + R[1] * y + R[2] * z);
      t.v[o + 1] = (float) (U[0] * x + U[1] * y + U[2] * z);
      t.v[o + 2] = (float) -(F[0] * x + F[1] * y + F[2] * z);
    }
    m.bounds();
  }

  /**
   * Moves the top of the gun (a scope, above [fromHeight] of its height) sideways onto the same
   * centre line as the rest, for models whose scope was left off to one side. Returns the shift.
   */
  static float centreTop(Model m, float fromHeight) {
    float cut = m.min[1] + (m.max[1] - m.min[1]) * fromHeight;
    double topSum = 0, restSum = 0; int topN = 0, restN = 0;
    for (Tri t : m.tris) {
      float cy = (t.v[1] + t.v[9] + t.v[17]) / 3, cx = (t.v[0] + t.v[8] + t.v[16]) / 3;
      if (cy > cut) { topSum += cx; topN++; } else { restSum += cx; restN++; }
    }
    if (topN == 0 || restN == 0) return 0;
    float shift = (float) (restSum / restN - topSum / topN);
    for (Tri t : m.tris) if ((t.v[1] + t.v[9] + t.v[17]) / 3 > cut) for (int k = 0; k < 3; k++) t.v[k * 8] += shift;
    m.bounds();
    return shift;
  }

  /**
   * For a model whose scope (the parts named [prefix]…) was left upside down, under and beside the
   * rifle: turns those parts half a turn about their own length, centres them over the rest, and
   * sits them on top of it.
   */
  static void remountScope(Model m, String prefix) {
    List<Tri> scope = new ArrayList<>(), body = new ArrayList<>();
    for (Tri t : m.tris) (t.node.startsWith(prefix) ? scope : body).add(t);
    if (scope.isEmpty()) return;
    float[] sMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, sMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    for (Tri t : scope) for (int k = 0; k < 3; k++) for (int a = 0; a < 3; a++) { sMin[a] = Math.min(sMin[a], t.v[k * 8 + a]); sMax[a] = Math.max(sMax[a], t.v[k * 8 + a]); }
    float cx = (sMin[0] + sMax[0]) / 2, cy = (sMin[1] + sMax[1]) / 2;
    // The rifle's centre line, and its top under the scope.
    float bMinX = Float.MAX_VALUE, bMaxX = -Float.MAX_VALUE, top = -Float.MAX_VALUE;
    for (Tri t : body) for (int k = 0; k < 3; k++) {
      float x = t.v[k * 8], y = t.v[k * 8 + 1], z = t.v[k * 8 + 2];
      bMinX = Math.min(bMinX, x); bMaxX = Math.max(bMaxX, x);
      if (z >= sMin[2] && z <= sMax[2]) top = Math.max(top, y);
    }
    float half = (sMax[1] - sMin[1]) / 2;
    float dx = (bMinX + bMaxX) / 2 - cx, dy = top + half - cy;
    for (Tri t : scope) for (int k = 0; k < 3; k++) {
      int o = k * 8;
      // Half a turn about the scope's own length axis: x and y mirror through its centre.
      t.v[o] = 2 * cx - t.v[o] + dx; t.v[o + 1] = 2 * cy - t.v[o + 1] + dy;
      t.v[o + 3] = -t.v[o + 3]; t.v[o + 4] = -t.v[o + 4];
    }
    m.bounds();
  }

  /** Scales the model to [length] metres along z, centred across (x) and with its muzzle at z = 0. */
  static void fit(Model m, float length) {
    float s = length / (m.max[2] - m.min[2]);
    float cx = (m.min[0] + m.max[0]) / 2, front = m.min[2], bottom = m.min[1];
    for (Tri t : m.tris) for (int k = 0; k < 3; k++) {
      t.v[k * 8] = (t.v[k * 8] - cx) * s; t.v[k * 8 + 1] = (t.v[k * 8 + 1] - bottom) * s; t.v[k * 8 + 2] = (t.v[k * 8 + 2] - front) * s;
    }
    m.bounds();
  }

  /** Side views of the oriented guns (muzzle left, up up) on a 10% grid, to read grip and hand positions. */
  static void gridPreview(List<Model> models, File out) throws IOException {
    int W = 900, H = 300;
    BufferedImage img = new BufferedImage(W, H * models.size(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x1B2027)); g.fillRect(0, 0, W, img.getHeight());
    for (int i = 0; i < models.size(); i++) {
      Model m = models.get(i);
      int ox = 40, oy = i * H + 30, w = W - 80, h = H - 60;
      float len = m.max[2] - m.min[2], hgt = m.max[1] - m.min[1];
      float s = Math.min(w / len, h / hgt);
      int gw = (int) (len * s), gh = (int) (hgt * s);
      int gx = ox, gy = oy + (h - gh) / 2;
      // Seen from the right side: -z (muzzle) to the left, so screen x = z.
      List<Tri> tris = new ArrayList<>(m.tris);
      tris.sort(Comparator.comparingDouble(t -> t.v[0] + t.v[8] + t.v[16]));
      for (Tri t : tris) {
        Path2D.Float p = new Path2D.Float();
        for (int k = 0; k < 3; k++) {
          float px = gx + (t.v[k * 8 + 2] - m.min[2]) * s, py = gy + gh - (t.v[k * 8 + 1] - m.min[1]) * s;
          if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        float light = 0.45f + 0.55f * Math.abs(0.4f * t.v[3] + 0.8f * t.v[4] + 0.45f * t.v[5]);
        int c = colorOf(t);
        light *= 2.2f; // brighter than the game, so dark guns show clearly on the grid
        g.setColor(new Color(Math.min(255, (int) (((c >> 16) & 255) * light)), Math.min(255, (int) (((c >> 8) & 255) * light)), Math.min(255, (int) ((c & 255) * light))));
        g.fill(p);
      }
      g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
      for (int k = 0; k <= 10; k++) {
        int x = gx + gw * k / 10, y = gy + gh - gh * k / 10;
        g.setColor(new Color(255, 255, 0, 70)); g.drawLine(x, gy, x, gy + gh); g.drawLine(gx, y, gx + gw, y);
        g.setColor(Color.YELLOW); g.drawString(String.valueOf(k), x - 3, gy + gh + 13); g.drawString(String.valueOf(k), gx - 14, y + 4);
      }
      g.setColor(Color.WHITE); g.setFont(new Font("Segoe UI", Font.BOLD, 15));
      g.drawString(m.name + "   (muzzle left; grid: along 0 = muzzle .. 10 = back, height 0 = bottom .. 10 = top)", 10, i * H + 18);
    }
    ImageIO.write(img, "png", out);
  }

  // ---- Arms -----------------------------------------------------------------------------------

  /** The rigged first-person arms (see README "Gun models"), posed by rotating bones, then skinned. */
  static final class Rig {
    JsonObject j; JsonArray nodes; List<ByteBuffer> buffers = new ArrayList<>();
    int[] joints; double[][] inverseBind; Map<Integer, double[]> extraRotation = new HashMap<>();
    float[] pos, nor; float[] jointIdx, weights; int[] idx; int[] vertexPrimitive; int skinColor = 0xFFC9A07E;

    Rig(File dir) throws IOException {
      j = JsonParser.parseString(Files.readString(new File(dir, "scene.gltf").toPath())).getAsJsonObject();
      nodes = j.getAsJsonArray("nodes");
      for (JsonElement b : j.getAsJsonArray("buffers"))
        buffers.add(ByteBuffer.wrap(Files.readAllBytes(new File(dir, b.getAsJsonObject().get("uri").getAsString()).toPath())).order(ByteOrder.LITTLE_ENDIAN));
      JsonObject skin = j.getAsJsonArray("skins").get(0).getAsJsonObject();
      JsonArray js = skin.getAsJsonArray("joints");
      joints = new int[js.size()]; for (int i = 0; i < joints.length; i++) joints[i] = js.get(i).getAsInt();
      float[] ib = floats(j, buffers, skin.get("inverseBindMatrices").getAsInt());
      inverseBind = new double[joints.length][16];
      for (int i = 0; i < joints.length; i++) for (int k = 0; k < 16; k++) inverseBind[i][k] = ib[i * 16 + k];
      // All skinned primitives, one vertex list.
      List<Float> p = new ArrayList<>(), n = new ArrayList<>(), ji = new ArrayList<>(), w = new ArrayList<>(); List<Integer> ix = new ArrayList<>();
      for (JsonElement me : j.getAsJsonArray("meshes")) for (JsonElement pe : me.getAsJsonObject().getAsJsonArray("primitives")) {
        JsonObject pr = pe.getAsJsonObject(), at = pr.getAsJsonObject("attributes");
        if (!at.has("JOINTS_0")) continue;
        int base = p.size() / 3;
        for (float f : floats(j, buffers, at.get("POSITION").getAsInt())) p.add(f);
        for (float f : floats(j, buffers, at.get("NORMAL").getAsInt())) n.add(f);
        for (float f : floats(j, buffers, at.get("JOINTS_0").getAsInt())) ji.add(f);
        for (float f : floats(j, buffers, at.get("WEIGHTS_0").getAsInt())) w.add(f);
        for (float f : floats(j, buffers, pr.get("indices").getAsInt())) ix.add(base + (int) f);
      }
      pos = toArray(p); nor = toArray(n); jointIdx = toArray(ji); weights = toArray(w);
      idx = ix.stream().mapToInt(Integer::intValue).toArray();
    }

    static float[] toArray(List<Float> l) { float[] a = new float[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }

    int node(String prefix) {
      for (int i = 0; i < nodes.size(); i++) { JsonObject n = nodes.get(i).getAsJsonObject(); if (n.has("name") && n.get("name").getAsString().startsWith(prefix)) return i; }
      throw new IllegalArgumentException(prefix);
    }

    String name(int node) { return nodes.get(node).getAsJsonObject().get("name").getAsString(); }

    /** Every node's world matrix with the extra rotations applied (each about the bone's own x axis). */
    double[][] globals() {
      double[][] g = new double[nodes.size()][];
      Deque<int[]> st = new ArrayDeque<>();
      JsonObject scene = j.getAsJsonArray("scenes").get(0).getAsJsonObject();
      for (JsonElement r : scene.getAsJsonArray("nodes")) st.push(new int[]{r.getAsInt(), -1});
      while (!st.isEmpty()) {
        int[] e = st.pop(); JsonObject n = nodes.get(e[0]).getAsJsonObject();
        double[] l = local(n);
        double[] extra = extraRotation.get(e[0]);
        if (extra != null) l = mul(l, extra);
        g[e[0]] = e[1] < 0 ? l : mul(g[e[1]], l);
        if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) st.push(new int[]{c.getAsInt(), e[0]});
      }
      return g;
    }

    /** Bends [node] by [degrees] about its own x axis (a finger joint's hinge). */
    void bend(int node, double degrees) {
      double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
      extraRotation.put(node, new double[]{1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1});
    }

    /** The mesh skinned to the current pose; [keep] chooses the joints whose vertices stay. */
    Model skin(java.util.function.Predicate<String> keep, java.util.function.Predicate<String> sleeve, int sleeveColor) {
      double[][] g = globals();
      double[][] jm = new double[joints.length][];
      for (int i = 0; i < joints.length; i++) jm[i] = mul(g[joints[i]], inverseBind[i]);
      int vc = pos.length / 3;
      float[] sp = new float[vc * 3], sn = new float[vc * 3];
      boolean[] kept = new boolean[vc]; boolean[] isSleeve = new boolean[vc];
      for (int v = 0; v < vc; v++) {
        double x = 0, y = 0, z = 0, nx = 0, ny = 0, nz = 0; int best = -1; float bestW = -1;
        for (int k = 0; k < 4; k++) {
          float w = weights[v * 4 + k]; if (w <= 0) continue;
          int jn = (int) jointIdx[v * 4 + k];
          double[] a = apply(jm[jn], pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], 1);
          double[] b = apply(jm[jn], nor[v * 3], nor[v * 3 + 1], nor[v * 3 + 2], 0);
          x += a[0] * w; y += a[1] * w; z += a[2] * w; nx += b[0] * w; ny += b[1] * w; nz += b[2] * w;
          if (w > bestW) { bestW = w; best = jn; }
        }
        sp[v * 3] = (float) x; sp[v * 3 + 1] = (float) y; sp[v * 3 + 2] = (float) z;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz); if (l == 0) l = 1;
        sn[v * 3] = (float) (nx / l); sn[v * 3 + 1] = (float) (ny / l); sn[v * 3 + 2] = (float) (nz / l);
        String jn = best >= 0 ? name(joints[best]) : "";
        kept[v] = keep.test(jn); isSleeve[v] = sleeve.test(jn);
      }
      Model m = new Model(); m.name = "arms";
      Material skinM = new Material(); skinM.color = skinColor;
      Material sleeveM = new Material(); sleeveM.color = sleeveColor;
      for (int t = 0; t + 2 < idx.length; t += 3) {
        if (!kept[idx[t]] || !kept[idx[t + 1]] || !kept[idx[t + 2]]) continue;
        Tri tri = new Tri();
        int sleeveVotes = 0;
        for (int k = 0; k < 3; k++) {
          int v = idx[t + k];
          for (int a = 0; a < 3; a++) { tri.v[k * 8 + a] = sp[v * 3 + a]; tri.v[k * 8 + 3 + a] = sn[v * 3 + a]; }
          if (isSleeve[v]) sleeveVotes++;
        }
        tri.m = sleeveVotes >= 2 ? sleeveM : skinM;
        m.tris.add(tri);
      }
      m.bounds();
      return m;
    }
  }

  /** Curls every finger of [side] ("R" or "L") by [degrees] per joint, and the thumb by [thumb]. */
  static void curl(Rig rig, String side, double degrees, double thumb) {
    for (String f : new String[]{"index", "middle", "ring", "pinky"})
      for (int s = 1; s <= 3; s++) rig.bend(rig.node("f_" + f + ".0" + s + "." + side), degrees);
    for (int s = 2; s <= 3; s++) rig.bend(rig.node("thumb.0" + s + "." + side), thumb);
  }

  /**
   * A skinned, textured glTF (a first-person arms pack) posed by one of its animations at a
   * given time: every node's translation, rotation and scale come from the animation where it
   * has a channel, else from the node.
   */
  static final class AnimRig {
    final JsonObject j; final JsonArray nodes; final List<ByteBuffer> buffers = new ArrayList<>();
    final List<Material> materials = new ArrayList<>();
    final Map<Integer, double[]> trs = new HashMap<>(); // node → t(3) r(4) s(3) from the animation
    final File dir;

    AnimRig(File dir, String animation, double time) throws IOException {
      this.dir = dir;
      j = JsonParser.parseString(Files.readString(new File(dir, "scene.gltf").toPath())).getAsJsonObject();
      nodes = j.getAsJsonArray("nodes");
      for (JsonElement b : j.getAsJsonArray("buffers"))
        buffers.add(ByteBuffer.wrap(Files.readAllBytes(new File(dir, b.getAsJsonObject().get("uri").getAsString()).toPath())).order(ByteOrder.LITTLE_ENDIAN));
      // Materials and their base-colour textures.
      Map<String, Material> byName = new HashMap<>();
      JsonArray mats = j.getAsJsonArray("materials");
      for (int i = 0; i < mats.size(); i++) {
        JsonObject m = mats.get(i).getAsJsonObject();
        Material mat = new Material();
        JsonObject pbr = m.has("pbrMetallicRoughness") ? m.getAsJsonObject("pbrMetallicRoughness") : new JsonObject();
        if (pbr.has("baseColorTexture")) {
          int tex = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
          int img = j.getAsJsonArray("textures").get(tex).getAsJsonObject().get("source").getAsInt();
          String uri = j.getAsJsonArray("images").get(img).getAsJsonObject().get("uri").getAsString();
          mat.texture = ImageIO.read(new File(dir, java.net.URLDecoder.decode(uri, "UTF-8")));
        } else if (pbr.has("baseColorFactor")) {
          JsonArray c = pbr.getAsJsonArray("baseColorFactor");
          mat.color = 0xFF000000 | (srgb(c.get(0).getAsFloat()) << 16) | (srgb(c.get(1).getAsFloat()) << 8) | srgb(c.get(2).getAsFloat());
        }
        materials.add(mat);
        byName.put(m.has("name") ? m.get("name").getAsString() : "m" + i, mat);
      }
      // The animation's pose at [time] (each channel's key at or before it: the idle pose is steady).
      for (JsonElement ae : j.getAsJsonArray("animations")) {
        JsonObject an = ae.getAsJsonObject();
        if (!an.get("name").getAsString().equals(animation)) continue;
        JsonArray samplers = an.getAsJsonArray("samplers");
        for (JsonElement ce : an.getAsJsonArray("channels")) {
          JsonObject ch = ce.getAsJsonObject(), target = ch.getAsJsonObject("target");
          int node = target.get("node").getAsInt();
          String path = target.get("path").getAsString();
          JsonObject s = samplers.get(ch.get("sampler").getAsInt()).getAsJsonObject();
          float[] times = floats(j, buffers, s.get("input").getAsInt()), values = floats(j, buffers, s.get("output").getAsInt());
          int k = 0; while (k + 1 < times.length && times[k + 1] <= time) k++;
          double[] v = trs.computeIfAbsent(node, n -> defaultTrs(nodes.get(n).getAsJsonObject()));
          int comps = path.equals("rotation") ? 4 : 3, off = path.equals("translation") ? 0 : path.equals("rotation") ? 3 : 7;
          if (path.equals("weights")) continue;
          for (int c = 0; c < comps; c++) v[off + c] = values[k * comps + c];
        }
      }
    }

    static double[] defaultTrs(JsonObject n) {
      double[] v = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
      if (n.has("translation")) for (int i = 0; i < 3; i++) v[i] = n.getAsJsonArray("translation").get(i).getAsDouble();
      if (n.has("rotation")) for (int i = 0; i < 4; i++) v[3 + i] = n.getAsJsonArray("rotation").get(i).getAsDouble();
      if (n.has("scale")) for (int i = 0; i < 3; i++) v[7 + i] = n.getAsJsonArray("scale").get(i).getAsDouble();
      return v;
    }

    double[] localOf(int i) {
      JsonObject n = nodes.get(i).getAsJsonObject();
      double[] v = trs.get(i);
      if (v == null) return local(n);
      JsonObject tmp = new JsonObject();
      JsonArray t = new JsonArray(), r = new JsonArray(), s = new JsonArray();
      for (int k = 0; k < 3; k++) t.add(v[k]); for (int k = 0; k < 4; k++) r.add(v[3 + k]); for (int k = 0; k < 3; k++) s.add(v[7 + k]);
      tmp.add("translation", t); tmp.add("rotation", r); tmp.add("scale", s);
      return local(tmp);
    }

    double[][] globals() {
      double[][] g = new double[nodes.size()][];
      Deque<int[]> st = new ArrayDeque<>();
      for (JsonElement r : j.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("nodes")) st.push(new int[]{r.getAsInt(), -1});
      while (!st.isEmpty()) {
        int[] e = st.pop(); JsonObject n = nodes.get(e[0]).getAsJsonObject();
        double[] l = localOf(e[0]);
        g[e[0]] = e[1] < 0 ? l : mul(g[e[1]], l);
        if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) st.push(new int[]{c.getAsInt(), e[0]});
      }
      return g;
    }

    String name(int node) { JsonObject n = nodes.get(node).getAsJsonObject(); return n.has("name") ? n.get("name").getAsString() : ""; }

    int node(String contains) {
      for (int i = 0; i < nodes.size(); i++) if (name(i).contains(contains)) return i;
      throw new IllegalArgumentException(contains);
    }

    /** Every skinned primitive in this pose, as triangles; each remembers its dominant joint's name in [Tri.node]. */
    Model skinned() {
      double[][] g = globals();
      JsonObject skin = j.getAsJsonArray("skins").get(0).getAsJsonObject();
      JsonArray js = skin.getAsJsonArray("joints");
      float[] ib = floats(j, buffers, skin.get("inverseBindMatrices").getAsInt());
      double[][] jm = new double[js.size()][];
      for (int i = 0; i < js.size(); i++) {
        double[] inv = new double[16]; for (int k = 0; k < 16; k++) inv[k] = ib[i * 16 + k];
        jm[i] = mul(g[js.get(i).getAsInt()], inv);
      }
      Model m = new Model(); m.name = dir.getName();
      for (int ni = 0; ni < nodes.size(); ni++) {
        JsonObject node = nodes.get(ni).getAsJsonObject();
        if (!node.has("mesh")) continue;
        for (JsonElement pe : j.getAsJsonArray("meshes").get(node.get("mesh").getAsInt()).getAsJsonObject().getAsJsonArray("primitives")) {
          JsonObject p = pe.getAsJsonObject(), at = p.getAsJsonObject("attributes");
          if (!at.has("JOINTS_0")) continue;
          float[] pos = floats(j, buffers, at.get("POSITION").getAsInt()), nor = floats(j, buffers, at.get("NORMAL").getAsInt());
          float[] uv = at.has("TEXCOORD_0") ? floats(j, buffers, at.get("TEXCOORD_0").getAsInt()) : null;
          float[] ji = floats(j, buffers, at.get("JOINTS_0").getAsInt()), w = floats(j, buffers, at.get("WEIGHTS_0").getAsInt());
          float[] ix = floats(j, buffers, p.get("indices").getAsInt());
          Material mat = p.has("material") ? materials.get(p.get("material").getAsInt()) : new Material();
          String matName = p.has("material") ? j.getAsJsonArray("materials").get(p.get("material").getAsInt()).getAsJsonObject().get("name").getAsString() : "";
          int vc = pos.length / 3;
          float[] sp = new float[vc * 3], sn = new float[vc * 3]; String[] dominant = new String[vc];
          for (int v = 0; v < vc; v++) {
            double x = 0, y = 0, z = 0, nx = 0, ny = 0, nz = 0; int best = 0; float bw = -1;
            for (int k = 0; k < 4; k++) {
              float wk = w[v * 4 + k]; if (wk <= 0) continue;
              int jn = (int) ji[v * 4 + k];
              double[] a = apply(jm[jn], pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], 1), b = apply(jm[jn], nor[v * 3], nor[v * 3 + 1], nor[v * 3 + 2], 0);
              x += a[0] * wk; y += a[1] * wk; z += a[2] * wk; nx += b[0] * wk; ny += b[1] * wk; nz += b[2] * wk;
              if (wk > bw) { bw = wk; best = jn; }
            }
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz); if (l == 0) l = 1;
            sp[v * 3] = (float) x; sp[v * 3 + 1] = (float) y; sp[v * 3 + 2] = (float) z;
            sn[v * 3] = (float) (nx / l); sn[v * 3 + 1] = (float) (ny / l); sn[v * 3 + 2] = (float) (nz / l);
            dominant[v] = name(js.get(best).getAsInt());
          }
          for (int t = 0; t + 2 < ix.length; t += 3) {
            Tri tri = new Tri(); tri.m = mat;
            tri.node = matName + "|" + dominant[(int) ix[t]];
            for (int k = 0; k < 3; k++) {
              int v = (int) ix[t + k];
              for (int c = 0; c < 3; c++) { tri.v[k * 8 + c] = sp[v * 3 + c]; tri.v[k * 8 + 3 + c] = sn[v * 3 + c]; }
              tri.v[k * 8 + 6] = uv != null ? uv[v * 2] : 0; tri.v[k * 8 + 7] = uv != null ? uv[v * 2 + 1] : 0;
            }
            m.tris.add(tri);
          }
        }
      }
      m.bounds();
      return m;
    }
  }

  /**
   * First-person arms from an animated arms-and-gun pack (e.g. "AK74U | FREE ANIMATION"): the
   * arms in the [animation]'s pose, the pack's own gun left out. Each arm is cut out and placed
   * relative to where it holds the pack's gun: the right arm relative to the grip, the left
   * relative to the part of the gun its hand holds. The game then puts those points on each
   * gun's grip and handguard. [armMaterials]/[gunMaterials]/[magMaterial] pick the meshes by
   * material name; bones are Mixamo-style ("…RightHand", "…LeftHandMiddle1").
   */
  static void packAnimatedArms(File dir, String animation, Set<String> armMaterials, Set<String> gunMaterials, String magMaterial, File outDir) throws IOException {
    AnimRig rig = new AnimRig(dir, animation, 0);
    Model all = rig.skinned();
    double[][] g = rig.globals();
    List<double[]> gunPts = new ArrayList<>(), magPts = new ArrayList<>();
    for (Tri t : all.tris) {
      String mat = t.node.substring(0, t.node.indexOf('|'));
      for (int k = 0; k < 3; k++) {
        double[] p = {t.v[k * 8], t.v[k * 8 + 1], t.v[k * 8 + 2]};
        if (gunMaterials.contains(mat) && !mat.equals(magMaterial)) gunPts.add(p);
        if (mat.equals(magMaterial)) magPts.add(p);
      }
    }
    // The gun's frame: its long axis (principal component), muzzle at the thinner end, the magazine down.
    double[] c = centroid(gunPts);
    double[] f = principalAxis(gunPts, c);
    // Only the magazine in the gun (a spare one for reloads may be parked elsewhere).
    double reach = 0; for (double[] p : gunPts) reach = Math.max(reach, len(sub(p, c)));
    final double[] gc = c; final double lim = reach;
    magPts.removeIf(p -> len(sub(p, gc)) > lim);
    double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
    for (double[] p : gunPts) { double a = dot(sub(p, c), f); minA = Math.min(minA, a); maxA = Math.max(maxA, a); }
    double[] thick = new double[2]; // spread across the axis near each end
    for (double[] p : gunPts) {
      double a = (dot(sub(p, c), f) - minA) / (maxA - minA);
      double r = len(ortho(sub(p, c), f));
      if (a < 0.15) thick[0] = Math.max(thick[0], r); else if (a > 0.85) thick[1] = Math.max(thick[1], r);
    }
    if (thick[0] < thick[1]) f = scale(f, -1); // f now points to the muzzle (the thin end)
    double[] down = norm(ortho(sub(centroid(magPts), c), f));
    double[] up = scale(down, -1), right = cross(f, up);
    double[][] toGame = {right, up, scale(f, -1)}; // rows: game x, y, z (forward is -z)
    // Scale from the forearm (a real one is about 26 cm).
    double[] fa = pos(g[rig.node("RightForeArm")]), hand = pos(g[rig.node("RightHand_")]);
    double s = FOREARM / len(sub(hand, fa));
    // Where each hand holds the pack's gun: the gun's geometry around the palm.
    double[] rPalm = scale(add(hand, pos(g[rig.node("RightHandMiddle1")])), 0.5);
    double[] lPalm = scale(add(pos(g[rig.node("LeftHand_")]), pos(g[rig.node("LeftHandMiddle1")])), 0.5);
    double[] grip = nearCentroid(gunPts, rPalm, 0.06 / s), support = nearCentroid(gunPts, lPalm, 0.07 / s);
    // The sleeve fabric (the grey texture, unlike the skin) takes the team's uniform colour.
    for (Tri t : all.tris) if (t.m.texture != null && saturation(t.m.texture) < 0.08) t.m.sleeve = true;
    for (String side : new String[]{"Right", "Left"}) {
      Model arm = new Model(); arm.name = side;
      for (Tri t : all.tris) {
        String mat = t.node.substring(0, t.node.indexOf('|')), joint = t.node.substring(t.node.indexOf('|') + 1);
        if (armMaterials.contains(mat) && joint.contains(side)) arm.tris.add(t);
      }
      double[] origin = side.equals("Right") ? grip : support;
      for (Tri t : arm.tris) for (int k = 0; k < 3; k++) {
        int o = k * 8;
        double[] p = apply3(toGame, sub(new double[]{t.v[o], t.v[o + 1], t.v[o + 2]}, origin));
        double[] n = apply3(toGame, new double[]{t.v[o + 3], t.v[o + 4], t.v[o + 5]});
        for (int i = 0; i < 3; i++) { t.v[o + i] = (float) (p[i] * s); t.v[o + 3 + i] = (float) n[i]; }
      }
      arm.bounds();
      String tag = side.equals("Right") ? "r" : "l";
      for (String kind : new String[]{"rifle", "pistol"}) {
        arm.name = "arms_" + kind + "_" + tag;
        Packed p = new Packed(); p.model = arm;
        write(p, outDir, 1024, "arms");
      }
    }
    System.out.printf("gun length %.2f m in the pack, grip→support %.2f m, forearm scale %.4f%n", (maxA - minA) * s, len(sub(support, grip)) * s, s);
  }

  /** How colourful a texture is on average (0 = grey). */
  static double saturation(BufferedImage img) {
    double sum = 0; int n = 0;
    for (int y = 0; y < img.getHeight(); y += 16) for (int x = 0; x < img.getWidth(); x += 16) {
      int c = img.getRGB(x, y); int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
      int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
      if (mx > 20) { sum += (mx - mn) / (double) mx; n++; }
    }
    return n == 0 ? 0 : sum / n;
  }

  static double[] centroid(List<double[]> pts) {
    double[] c = new double[3]; for (double[] p : pts) for (int i = 0; i < 3; i++) c[i] += p[i];
    return scale(c, 1.0 / Math.max(1, pts.size()));
  }

  static double[] nearCentroid(List<double[]> pts, double[] at, double radius) {
    List<double[]> near = new ArrayList<>(); for (double[] p : pts) if (len(sub(p, at)) < radius) near.add(p);
    return near.isEmpty() ? at : centroid(near);
  }

  /** The direction the points spread most along (power iteration on their covariance). */
  static double[] principalAxis(List<double[]> pts, double[] c) {
    double[][] cov = new double[3][3];
    for (double[] p : pts) { double[] d = sub(p, c); for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) cov[i][k] += d[i] * d[k]; }
    double[] v = {1, 0.3, 0.2};
    for (int it = 0; it < 100; it++) v = norm(apply3(cov, v));
    return v;
  }

  /** Real forearm length, metres: sets the arms' scale. */
  static final double FOREARM = 0.26;

  /**
   * One arm posed for a gun: the hand curled round the grip (or handguard) with its palm facing
   * [palm] and its knuckles pointing [knuckles] (gun space), and the forearm running from the
   * wrist towards [elbow] (a direction). The hand's contact point, [radius] from the palm (the
   * grip's half-thickness), ends up at the origin: the game moves it to the gun's grip or
   * support point. Forearm triangles are marked for the team's sleeve colour (alpha 0).
   */
  static Model posedArm(File dir, String side, double curlDeg, double[] palm, double[] knuckles, double[] elbow, double radius) throws IOException {
    Rig rig = new Rig(dir);
    double[][] g0 = rig.globals();
    double[] W = pos(g0[rig.node("hand." + side)]), K = pos(g0[rig.node("f_middle.01." + side)]);
    double[] tip0 = pos(g0[rig.node("f_middle.03." + side + "_end")]);
    double[] E = pos(g0[rig.node("forearm." + side + "_")]), Wf = pos(g0[rig.node("forearm." + side + "_end")]);
    curl(rig, side, curlDeg, curlDeg * 0.6);
    double[] tip1 = pos(rig.globals()[rig.node("f_middle.03." + side + "_end")]);
    double handLen = len(sub(K, W));
    double s = FOREARM / len(sub(Wf, E));
    // The hand's frame: knuckle direction D, palm direction N (where the fingers curl), T = D × N.
    double[] D = norm(sub(K, W));
    double[] N = norm(ortho(sub(tip1, tip0), D));
    double[] T = cross(D, N);
    double[] Dt = norm(knuckles), Nt = norm(ortho(palm, Dt)), Tt = cross(Dt, Nt);
    double[][] R = frameRotation(D, N, T, Dt, Nt, Tt);
    // Contact point: along the palm, [radius] out from it.
    double[] C = add(add(W, scale(D, 0.55 * handLen)), scale(N, radius / s));
    String sideTag = "." + side;
    Model hand = rig.skin(n -> n.contains(sideTag) && !n.startsWith("forearm." + side + "_") && !n.startsWith("upper_arm") && !n.startsWith("deltoid") && !n.startsWith("clavicle"),
        n -> false, 0);
    transform(hand, R, s, C, new double[3]);
    // The wrist, where the hand now is (relative to the contact point at the origin).
    double[] wristNow = scale(apply3(R, sub(W, C)), s);
    // Forearm: its elbow→wrist axis turned to point from [elbow] to the wrist, rolled like the hand.
    double[] a = norm(sub(Wf, E));
    double[] at = scale(norm(elbow), -1);
    double[] b = norm(ortho(T, a)), bt = norm(ortho(Tt, at));
    double[][] Rf = frameRotation(a, b, cross(a, b), at, bt, cross(at, bt));
    Model forearm = rig.skin(n -> n.startsWith("forearm." + side + "_"), n -> true, 0x00000000);
    // Its wrist end slightly inside the hand, so there's no gap.
    transform(forearm, Rf, s, Wf, add(wristNow, scale(at, 0.012)));
    hand.tris.addAll(forearm.tris);
    hand.bounds();
    return hand;
  }

  static double[] pos(double[] m) { return new double[]{m[12], m[13], m[14]}; }
  static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
  static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
  static double[] scale(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }
  static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  static double len(double[] a) { return Math.sqrt(dot(a, a)); }
  static double[] norm(double[] a) { double l = len(a); return l == 0 ? a : scale(a, 1 / l); }
  static double[] ortho(double[] v, double[] axis) { return sub(v, scale(axis, dot(v, axis))); }
  static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
  static double[] apply3(double[][] R, double[] v) { return new double[]{dot(R[0], v), dot(R[1], v), dot(R[2], v)}; }

  /** The rotation (rows) taking the frame (a, b, c) onto (at, bt, ct). */
  static double[][] frameRotation(double[] a, double[] b, double[] c, double[] at, double[] bt, double[] ct) {
    double[][] R = new double[3][3];
    for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) R[r][col] = at[r] * a[col] + bt[r] * b[col] + ct[r] * c[col];
    return R;
  }

  /** p → R·(p − from)·s + to, for positions and normals. */
  static void transform(Model m, double[][] R, double s, double[] from, double[] to) {
    for (Tri t : m.tris) for (int k = 0; k < 3; k++) {
      int o = k * 8;
      double[] p = apply3(R, new double[]{t.v[o] - from[0], t.v[o + 1] - from[1], t.v[o + 2] - from[2]});
      double[] n = apply3(R, new double[]{t.v[o + 3], t.v[o + 4], t.v[o + 5]});
      for (int i = 0; i < 3; i++) { t.v[o + i] = (float) (p[i] * s + to[i]); t.v[o + 3 + i] = (float) n[i]; }
    }
    m.bounds();
  }

  /** The four posed arms: rifle right (grip), rifle left (handguard), pistol right and left (both on the grip). */
  static void packArms(File armsDir, File outDir) throws IOException {
    double rake = Math.toRadians(18);
    double[] forwardDown = {0, -Math.sin(rake), -Math.cos(rake)}; // knuckles on a raked pistol grip
    Map<String, Model> arms = new LinkedHashMap<>();
    // Rifle: right palm on the grip's right side, forearm back, down and out to the right.
    arms.put("arms_rifle_r", posedArm(armsDir, "R", 55, new double[]{-1, 0, 0}, forwardDown, new double[]{0.4, -0.5, 1}, 0.016));
    // Rifle: left hand under the handguard, palm up, fingers across to the right, forearm back and down to the left.
    arms.put("arms_rifle_l", posedArm(armsDir, "L", 50, new double[]{0, 1, 0}, new double[]{1, 0, 0}, new double[]{-0.55, -0.55, 1}, 0.026));
    // Pistol: both hands round the grip, the left over the right's fingers from the other side.
    arms.put("arms_pistol_r", posedArm(armsDir, "R", 55, new double[]{-1, 0, 0}, forwardDown, new double[]{0.25, -0.35, 1}, 0.015));
    arms.put("arms_pistol_l", posedArm(armsDir, "L", 55, new double[]{1, 0, 0}, forwardDown, new double[]{-0.3, -0.35, 1}, 0.02));
    for (Map.Entry<String, Model> e : arms.entrySet()) {
      Model m = e.getValue(); m.name = e.getKey();
      Packed p = new Packed(); p.model = m;
      write(p, outDir);
    }
  }

  // ---- Packing --------------------------------------------------------------------------------

  /** A packed gun: its geometry in gun space and where the hands and muzzle are. */
  static final class Packed { Model model; float gripY, gripZ, supportZ, muzzleY, muzzleZ; }

  /** Orients, scales and places one gun (see [GUNS]). */
  static Packed place(Model m) {
    Placement p = GUNS.get(m.name);
    orient(m, p.fwd(), p.up());
    if (m.name.equals("awm")) remountScope(m, "Cylinder.000");
    fit(m, p.length());
    // Now the muzzle is at z = 0, the back at z = length, the bottom at y = 0. Move the grip to the hand.
    float len = m.max[2] - m.min[2], hgt = m.max[1] - m.min[1];
    float gy = p.gripHeight() * hgt, gz = p.gripAlong() * len;
    float[] hand = HAND.get(m.name);
    float dy = hand[0] - gy, dz = hand[1] - gz;
    for (Tri t : m.tris) for (int k = 0; k < 3; k++) { t.v[k * 8 + 1] += dy; t.v[k * 8 + 2] += dz; }
    m.bounds();
    Packed out = new Packed();
    out.model = m;
    out.gripY = hand[0]; out.gripZ = hand[1];
    out.supportZ = p.supportAlong() * len + dz;
    out.muzzleY = p.muzzleHeight() * hgt + dy;
    out.muzzleZ = dz; // the front end
    return out;
  }

  /** Writes [g] as [name].gun (and [name].png for a textured model) into [dir]. */
  static void write(Packed g, File dir) throws IOException { write(g, dir, TEXTURE_SIZE, null); }

  /**
   * As write(g, dir), keeping textures up to [textureSize] pixels, named [texturePrefix]… when
   * several models share them (e.g. the arm pieces), else after the model.
   */
  static void write(Packed g, File dir, int textureSize, String texturePrefix) throws IOException {
    Model m = g.model;
    // One part per material; each texture shrunk and saved once.
    Map<Material, List<Tri>> byMaterial = new LinkedHashMap<>();
    for (Tri t : m.tris) byMaterial.computeIfAbsent(t.m, k -> new ArrayList<>()).add(t);
    List<BufferedImage> textures = new ArrayList<>();
    List<String> textureFiles = new ArrayList<>();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    ByteBuffer head = ByteBuffer.allocate(4 + 20).order(ByteOrder.LITTLE_ENDIAN);
    head.put("GUN1".getBytes()).putFloat(g.gripY).putFloat(g.gripZ).putFloat(g.supportZ).putFloat(g.muzzleY).putFloat(g.muzzleZ);
    out.write(head.array());
    for (Material mat : byMaterial.keySet()) if (mat.texture != null && !textures.contains(mat.texture)) {
      textures.add(mat.texture);
      textureFiles.add((texturePrefix != null ? texturePrefix : m.name) + (textures.size() > 1 ? "_" + textures.size() : "") + ".png");
    }
    out.write(le(textureFiles.size()));
    for (String f : textureFiles) { byte[] b = f.getBytes("UTF-8"); out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) b.length).array()); out.write(b); }
    // Parts, split so no part needs more than 65535 vertices (the game uses 16-bit indices).
    List<byte[]> parts = new ArrayList<>();
    int vertexTotal = 0;
    for (Map.Entry<Material, List<Tri>> e : byMaterial.entrySet()) {
      Material mat = e.getKey();
      int tex = mat.texture == null ? -1 : textures.indexOf(mat.texture);
      List<Tri> tris = e.getValue();
      for (int start = 0; start < tris.size(); start += 20000) {
        List<Tri> chunk = tris.subList(start, Math.min(tris.size(), start + 20000));
        Map<String, Integer> seen = new HashMap<>();
        List<float[]> verts = new ArrayList<>();
        int[] idx = new int[chunk.size() * 3];
        int n = 0;
        for (Tri t : chunk) for (int k = 0; k < 3; k++) {
          float[] v = Arrays.copyOfRange(t.v, k * 8, k * 8 + 8);
          if (tex < 0) { v[6] = 0; v[7] = 0; }
          String key = Arrays.toString(v);
          Integer i = seen.get(key);
          if (i == null) { i = verts.size(); seen.put(key, i); verts.add(v); }
          idx[n++] = i;
        }
        ByteBuffer b = ByteBuffer.allocate(16 + verts.size() * 32 + 4 + idx.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        int color = tex >= 0 ? 0xFFFFFFFF : mat.color;
        if (mat.sleeve) color &= 0x00FFFFFF;
        b.putInt(color).putInt(tex).putInt(verts.size());
        for (float[] v : verts) for (float f : v) b.putFloat(f);
        b.putInt(idx.length);
        for (int i : idx) b.putShort((short) i);
        parts.add(Arrays.copyOf(b.array(), b.position()));
        vertexTotal += verts.size();
      }
    }
    out.write(le(parts.size()));
    for (byte[] p : parts) out.write(p);
    Files.write(new File(dir, m.name + ".gun").toPath(), bytes.toByteArray());
    for (int i = 0; i < textures.size(); i++) {
      BufferedImage src = textures.get(i);
      float s = Math.min(1f, textureSize / (float) Math.max(src.getWidth(), src.getHeight()));
      int w = Math.max(1, Math.round(src.getWidth() * s)), h = Math.max(1, Math.round(src.getHeight() * s));
      BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2 = small.createGraphics();
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g2.drawImage(src, 0, 0, w, h, null);
      g2.dispose();
      ImageIO.write(small, "png", new File(dir, textureFiles.get(i)));
    }
    System.out.printf("%-8s %6d tris %6d vertices %3d parts %d textures  length %.3f m  grip (%.3f, %.3f) support %.3f muzzle (%.3f, %.3f)%n",
        m.name, m.tris.size(), vertexTotal, parts.size(), textures.size(), m.max[2] - m.min[2], g.gripY, g.gripZ, g.supportZ, g.muzzleY, g.muzzleZ);
  }

  static byte[] le(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }

  /** Side views of the packed guns with the grip (green), support hand (blue) and muzzle (red) marked. */
  static void packedPreview(List<Packed> guns, File out) throws IOException {
    int W = 450, H = 200, cols = 2;
    BufferedImage img = new BufferedImage(W * cols, H * ((guns.size() + 1) / cols), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x1B2027)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
    for (int i = 0; i < guns.size(); i++) {
      Packed p = guns.get(i); Model m = p.model;
      int ox = (i % cols) * W, oy = (i / cols) * H;
      float s = Math.min((W - 30) / (m.max[2] - m.min[2]), (H - 40) / (m.max[1] - m.min[1]));
      float x0 = ox + 15 - m.min[2] * s, y0 = oy + H - 12 + m.min[1] * s;
      List<Tri> tris = new ArrayList<>(m.tris);
      tris.sort(Comparator.comparingDouble(t -> t.v[0] + t.v[8] + t.v[16]));
      for (Tri t : tris) {
        Path2D.Float path = new Path2D.Float();
        for (int k = 0; k < 3; k++) {
          float px = x0 + t.v[k * 8 + 2] * s, py = y0 - t.v[k * 8 + 1] * s;
          if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.closePath();
        float light = (0.45f + 0.55f * Math.abs(0.4f * t.v[3] + 0.8f * t.v[4] + 0.45f * t.v[5])) * 1.6f;
        int c = colorOf(t);
        g.setColor(new Color(Math.min(255, (int) (((c >> 16) & 255) * light)), Math.min(255, (int) (((c >> 8) & 255) * light)), Math.min(255, (int) ((c & 255) * light))));
        g.fill(path);
      }
      int r = 6;
      g.setColor(Color.GREEN); g.fillOval((int) (x0 + p.gripZ * s) - r, (int) (y0 - p.gripY * s) - r, 2 * r, 2 * r);
      g.setColor(new Color(0x40A0FF)); g.fillOval((int) (x0 + p.supportZ * s) - r, (int) (y0 - (p.muzzleY - 0.02f) * s) - r, 2 * r, 2 * r);
      g.setColor(Color.RED); g.fillOval((int) (x0 + p.muzzleZ * s) - r, (int) (y0 - p.muzzleY * s) - r, 2 * r, 2 * r);
      g.setColor(Color.WHITE); g.setFont(new Font("Segoe UI", Font.BOLD, 14)); g.drawString(m.name, ox + 8, oy + 18);
    }
    ImageIO.write(img, "png", out);
  }

  public static void main(String[] a) throws Exception {
    if (a[0].equals("curltest")) {
      // curltest <arms dir> <out.png>: the right hand curled both ways, to see which makes a fist.
      List<Model> views = new ArrayList<>();
      for (double deg : new double[]{0, 55, -55}) {
        Rig rig = new Rig(new File(a[1]));
        curl(rig, "R", deg, deg / 2);
        Model m = rig.skin(n -> n.endsWith(".R") || n.contains(".R_") || n.contains(".R."), n -> false, 0);
        m.name = "right hand, curl " + (int) deg;
        views.add(m);
      }
      preview(views, new File(a[2]), new String[]{"along x", "along y", "along z"});
      return;
    }
    if (a[0].equals("fpsarms")) {
      // fpsarms <arms pack dir> <assets/guns3d dir>: arms from the "AK74U | FREE ANIMATION" pack, in its idle pose.
      packAnimatedArms(new File(a[1]), "IDLE", Set.of("Ch08_body", "Ch08_body1"), Set.of("Krinkov", "Magazine"), "Magazine", new File(a[2]));
      return;
    }
    if (a[0].equals("arms")) {
      // arms <arms dir> <assets/guns3d dir>: the posed first-person arms.
      packArms(new File(a[1]), new File(a[2]));
      return;
    }
    if (a[0].equals("pack")) {
      File outDir = new File(a[2]); outDir.mkdirs();
      List<Packed> packed = new ArrayList<>();
      for (String name : GUNS.keySet()) {
        File f = new File(a[1], name);
        if (!new File(f, "scene.gltf").exists()) { System.out.println(name + ": no model, skipped"); continue; }
        Packed p = place(load(f));
        write(p, outDir);
        packed.add(p);
      }
      if (a.length > 3) packedPreview(packed, new File(a[3]));
      return;
    }
    if (a[0].equals("parts")) {
      // parts <gun folder> fwd up: each mesh node's size and position in the gun's frame (muzzle 0 .. back 1).
      File f = new File(a[1]);
      JsonObject j = JsonParser.parseString(Files.readString(new File(f, "scene.gltf").toPath())).getAsJsonObject();
      JsonArray nodes = j.getAsJsonArray("nodes");
      Model whole = load(f); orient(whole, a[2], a[3]);
      float len = whole.max[2] - whole.min[2], hgt = whole.max[1] - whole.min[1];
      for (int n = 0; n < nodes.size(); n++) {
        JsonObject node = nodes.get(n).getAsJsonObject();
        if (!node.has("mesh")) continue;
        Model part = new Model(); part.name = node.has("name") ? node.get("name").getAsString() : "node" + n;
        // The node with its parents' transforms: walk from the root, keeping only this node's mesh.
        double[] m = worldOf(j, n);
        JsonObject copy = node.deepCopy(); copy.remove("children");
        JsonArray one = new JsonArray(); one.add(copy);
        JsonObject solo = j.deepCopy(); solo.add("nodes", one);
        List<ByteBuffer> buffers = new ArrayList<>();
        for (JsonElement b : j.getAsJsonArray("buffers"))
          buffers.add(ByteBuffer.wrap(Files.readAllBytes(new File(f, b.getAsJsonObject().get("uri").getAsString()).toPath())).order(ByteOrder.LITTLE_ENDIAN));
        List<Material> mats = new ArrayList<>(); for (JsonElement ignored : j.getAsJsonArray("materials")) mats.add(new Material());
        walk(solo, buffers, mats, 0, mul(m, inverseLocal(copy)), part);
        orient(part, a[2], a[3]);
        System.out.printf("  %-34s %5d tris  along %.2f-%.2f  height %.2f-%.2f  side %+.3f%n", part.name, part.tris.size(),
            (part.min[2] - whole.min[2]) / len, (part.max[2] - whole.min[2]) / len,
            (part.min[1] - whole.min[1]) / hgt, (part.max[1] - whole.min[1]) / hgt,
            ((part.min[0] + part.max[0]) / 2 - (whole.min[0] + whole.max[0]) / 2) / len);
      }
      return;
    }
    if (a[0].equals("orient")) {
      // orient <models dir> <out.png> [name=fwd,up ...]: auto-orient (with overrides) and draw on a grid.
      Map<String, String[]> overrides = new HashMap<>();
      for (int i = 3; i < a.length; i++) { String[] kv = a[i].split("="); overrides.put(kv[0], kv[1].split(",")); }
      List<Model> models = new ArrayList<>();
      File[] folders = new File(a[1]).listFiles(f -> new File(f, "scene.gltf").exists() && !f.getName().equals("arms"));
      Arrays.sort(folders);
      for (File f : folders) {
        Model m = load(f);
        String[] ax = overrides.getOrDefault(m.name, guessAxes(m));
        System.out.println(m.name + ": fwd " + ax[0] + ", up " + ax[1] + (overrides.containsKey(m.name) ? " (set)" : " (guessed)"));
        orient(m, ax[0], ax[1]);
        if (m.name.equals("awm")) remountScope(m, "Cylinder.000");
        fit(m, 1f);
        models.add(m);
      }
      gridPreview(models, new File(a[2]));
      return;
    }
    File dir = new File(a[1]);
    File[] folders = dir.listFiles(f -> new File(f, "scene.gltf").exists());
    Arrays.sort(folders);
    List<Model> models = new ArrayList<>();
    for (File f : folders) if (a[0].equals("preview") || GUNS.containsKey(f.getName())) models.add(load(f));
    if (a[0].equals("preview")) {
      // Seen along x shows the y-z side, along y the top (x-z), along z the front (x-y).
      preview(models, new File(a[2]), new String[]{"side (z right, y up)", "top (x right, z up)", "front (x right, y up)"});
    }
  }
}
