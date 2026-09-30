import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Keeps only the skeleton and the animations of a .glb: Mixamo animations downloaded "with skin"
 * carry the whole character (10+ MB), but the game only needs the bones' motion (a few hundred KB).
 *
 *   java -cp gson.jar tools/StripAnimation.java in.glb out.glb
 */
public class StripAnimation {
  public static void main(String[] a) throws Exception {
    byte[] glb = Files.readAllBytes(Path.of(a[0]));
    ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
    int jsonLen = b.getInt(12);
    JsonObject j = JsonParser.parseString(new String(glb, 20, jsonLen, "UTF-8")).getAsJsonObject();
    int binStart = 20 + jsonLen + 8;
    ByteBuffer bin = ByteBuffer.wrap(glb, binStart, glb.length - binStart).slice().order(ByteOrder.LITTLE_ENDIAN);

    JsonArray accessors = j.getAsJsonArray("accessors"), views = j.getAsJsonArray("bufferViews");
    JsonArray newAccessors = new JsonArray(), newViews = new JsonArray();
    ByteArrayOutputStream newBin = new ByteArrayOutputStream();
    Map<Integer, Integer> remap = new HashMap<>();
    JsonArray animations = j.getAsJsonArray("animations");
    for (JsonElement ae : animations) for (JsonElement se : ae.getAsJsonObject().getAsJsonArray("samplers")) {
      JsonObject s = se.getAsJsonObject();
      for (String key : new String[]{"input", "output"}) {
        int old = s.get(key).getAsInt();
        Integer idx = remap.get(old);
        if (idx == null) {
          JsonObject acc = accessors.get(old).getAsJsonObject().deepCopy();
          JsonObject view = views.get(acc.get("bufferView").getAsInt()).getAsJsonObject();
          int comps = switch (acc.get("type").getAsString()) { case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; default -> 16; };
          int size = switch (acc.get("componentType").getAsInt()) { case 5126, 5125 -> 4; case 5123, 5122 -> 2; default -> 1; };
          int count = acc.get("count").getAsInt(), elem = comps * size;
          int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : elem;
          int base = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0) + (acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0);
          while (newBin.size() % 4 != 0) newBin.write(0);
          int offset = newBin.size();
          for (int i = 0; i < count; i++) newBin.write(glb, binStart + base + i * stride, elem);
          JsonObject nv = new JsonObject();
          nv.addProperty("buffer", 0); nv.addProperty("byteOffset", offset); nv.addProperty("byteLength", count * elem);
          newViews.add(nv);
          acc.addProperty("bufferView", newViews.size() - 1);
          acc.remove("byteOffset");
          newAccessors.add(acc);
          idx = newAccessors.size() - 1;
          remap.put(old, idx);
        }
        s.addProperty(key, idx);
      }
    }
    // Bones only: no meshes, skins, materials or images.
    for (JsonElement ne : j.getAsJsonArray("nodes")) { JsonObject n = ne.getAsJsonObject(); n.remove("mesh"); n.remove("skin"); n.remove("camera"); }
    for (String key : new String[]{"meshes", "skins", "materials", "textures", "images", "samplers", "cameras", "extensionsUsed", "extensionsRequired"}) j.remove(key);
    j.add("accessors", newAccessors);
    j.add("bufferViews", newViews);
    while (newBin.size() % 4 != 0) newBin.write(0);
    JsonArray buffers = new JsonArray(); JsonObject buf = new JsonObject(); buf.addProperty("byteLength", newBin.size()); buffers.add(buf);
    j.add("buffers", buffers);

    byte[] json = j.toString().getBytes("UTF-8");
    int jsonPadded = (json.length + 3) / 4 * 4;
    ByteBuffer out = ByteBuffer.allocate(12 + 8 + jsonPadded + 8 + newBin.size()).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
    out.putInt(jsonPadded).putInt(0x4E4F534A).put(json);
    for (int i = json.length; i < jsonPadded; i++) out.put((byte) ' ');
    out.putInt(newBin.size()).putInt(0x004E4942).put(newBin.toByteArray());
    Files.write(Path.of(a[1]), out.array());
    System.out.printf("%s: %d KB -> %d KB (%d animation(s))%n", a[1], glb.length / 1024, out.capacity() / 1024, animations.size());
  }
}
