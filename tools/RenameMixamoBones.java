import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Renames a Mixamo character's bones from "mixamorig7:Hips" (Mixamo numbers the prefix on some
 * downloads) to "mixamorig:Hips", so animations downloaded on another Mixamo character play on
 * it: the game matches animation tracks to bones by name. Only the JSON changes.
 *
 *   java -cp gson.jar tools/RenameMixamoBones.java in.glb out.glb
 */
public class RenameMixamoBones {
  public static void main(String[] a) throws Exception {
    byte[] glb = Files.readAllBytes(Path.of(a[0]));
    ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
    int jsonLen = b.getInt(12);
    JsonObject j = JsonParser.parseString(new String(glb, 20, jsonLen, StandardCharsets.UTF_8)).getAsJsonObject();
    int renamed = 0;
    for (JsonElement e : j.getAsJsonArray("nodes")) {
      JsonObject n = e.getAsJsonObject();
      if (!n.has("name")) continue;
      String name = n.get("name").getAsString();
      String fixed = name.replaceFirst("^mixamorig\\d+:", "mixamorig:");
      if (!fixed.equals(name)) { n.addProperty("name", fixed); renamed++; }
    }
    byte[] json = new Gson().toJson(j).getBytes(StandardCharsets.UTF_8);
    int padded = (json.length + 3) & ~3;
    // The binary chunk (if any) follows the JSON chunk unchanged.
    int binStart = 20 + jsonLen;
    int binLen = glb.length - binStart;
    ByteBuffer out = ByteBuffer.allocate(12 + 8 + padded + binLen).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded + binLen);
    out.putInt(padded).putInt(0x4E4F534A).put(json);
    for (int i = json.length; i < padded; i++) out.put((byte) ' ');
    out.put(glb, binStart, binLen);
    Files.write(Path.of(a[1]), out.array());
    System.out.println(a[1] + ": renamed " + renamed + " bones");
  }
}
