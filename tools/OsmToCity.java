import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Turns the OpenStreetMap download (tools/data/downtown_*.json, from fetch_osm.sh) into the
 * game's compact map file app/src/main/assets/maps/downtown.bin, plus a top-down preview PNG.
 *
 * Run from the project root (Gson comes from the Gradle cache):
 *   java -cp <path-to>/gson-2.10.1.jar tools/OsmToCity.java
 *
 * Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL).
 *
 * World units are metres. x runs east, z runs south (north is -z), y is up. (0, 0) is the
 * origin below (Martyrs' Square), which is also where players start.
 */
public class OsmToCity {

    // Martyrs' Square, the spawn point and origin of the game world.
    static double LAT0 = 33.8962, LON0 = 35.5077;
    // The downloaded area (south, west, north, east); must match fetch_osm.sh.
    static double SOUTH = 33.8905, WEST = 35.4935, NORTH = 33.9040, EAST = 35.5115;
    static String ID = "downtown";
    /** Hills: the ground's real height (elevation tiles in tools/data/elevation, see Terrain). Flat without. */
    static boolean TERRAIN = false;
    /** A city with hills: the elevation data partly measures rooftops there, so buildings are filtered out of it (see Terrain). */
    static boolean URBAN = false;
    /** Which map's downloaded data to read (this map may be cut out of a bigger one). */
    static String SOURCE = "downtown";
    static final double M_PER_DEG_LAT = 110574.0;
    static double M_PER_DEG_LON = 111320.0 * Math.cos(Math.toRadians(LAT0));

    // Building kinds (must match CityMap.kt).
    static final int B_GENERIC = 0, B_MOSQUE = 1, B_CHURCH = 2, B_CONSTRUCTION = 3, B_ROCK = 4;
    // Road kinds.
    static final int R_MAJOR = 0, R_MEDIUM = 1, R_MINOR = 2, R_PEDESTRIAN = 3, R_PATH = 4, R_PIER = 5, R_TRACK = 6;
    // Area kinds.
    static final int A_PARK = 0, A_PITCH = 1, A_PARKING = 2, A_WATER = 3, A_PLAZA = 4, A_SAND = 5, A_PIER = 6, A_CONSTRUCTION = 7;
    // Tree kinds.
    static final int T_LEAFY = 0, T_PALM = 1;

    record Building(float height, float minHeight, int kind, float[] pts, String name) {}
    record Road(int kind, float width, float[] pts) {}
    record Area(int kind, float[] pts) {}
    record Tree(int kind, float x, float z, float size) {}

    static final List<Building> buildings = new ArrayList<>();
    static final List<Road> roads = new ArrayList<>();
    static final List<Area> areas = new ArrayList<>();
    static final List<float[]> sea = new ArrayList<>();
    static final List<Tree> trees = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        // Arguments (from tools/maps.txt via build_maps.sh): id south west north east startLat startLon.
        // With none, converts Downtown as before.
        if (args.length >= 7) {
            ID = args[0];
            SOUTH = Double.parseDouble(args[1]); WEST = Double.parseDouble(args[2]);
            NORTH = Double.parseDouble(args[3]); EAST = Double.parseDouble(args[4]);
            LAT0 = Double.parseDouble(args[5]); LON0 = Double.parseDouble(args[6]);
            M_PER_DEG_LON = 111320.0 * Math.cos(Math.toRadians(LAT0));
            SOURCE = args.length >= 8 && !args[7].equals("-") ? args[7] : ID;
            TERRAIN = args.length >= 9 && (args[8].equals("terrain") || args[8].equals("city"));
            URBAN = args.length >= 9 && args[8].equals("city");
        }
        File data = new File("tools/data");
        readBuildings(load(new File(data, SOURCE + "_buildings.json")));
        readRoads(load(new File(data, SOURCE + "_roads.json")));
        readAreas(load(new File(data, SOURCE + "_areas.json")));
        keepInsideMap();
        // Palms line Beirut's avenues; a mountain village gets its pines and oaks on open ground instead.
        if (TERRAIN && !URBAN) addCountryTrees(); else addStreetPalms();

        float[] spawn = findSpawn();
        if (TERRAIN) Terrain.build(spawn);
        File out = new File("app/src/main/assets/maps/" + ID + ".bin");
        out.getParentFile().mkdirs();
        write(out, spawn);
        BufferedImage full = preview(new File(data, ID + "_preview.png"), spawn);
        thumbnail(full, new File("app/src/main/assets/maps/" + ID + "_preview.png"));

        System.out.printf(Locale.US, "buildings=%d roads=%d areas=%d sea=%d trees=%d spawn=(%.1f, %.1f)%n",
                buildings.size(), roads.size(), areas.size(), sea.size(), trees.size(), spawn[0], spawn[1]);
        System.out.printf(Locale.US, "world x %.0f..%.0f, z %.0f..%.0f; wrote %s (%d KB)%n",
                x(WEST), x(EAST), z(NORTH), z(SOUTH), out, out.length() / 1024);
    }

    // ---- Projection ------------------------------------------------------------------------

    static float x(double lon) { return (float) ((lon - LON0) * M_PER_DEG_LON); }
    static float z(double lat) { return (float) (-(lat - LAT0) * M_PER_DEG_LAT); }

    static JsonArray load(File f) throws IOException {
        return JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject().getAsJsonArray("elements");
    }

    static String tag(JsonObject e, String key) {
        JsonObject tags = e.getAsJsonObject("tags");
        if (tags == null || !tags.has(key)) return null;
        return tags.get(key).getAsString();
    }

    /** A way's geometry as x,z pairs with near-duplicate points removed. */
    static float[] geometry(JsonArray geom) {
        List<Float> pts = new ArrayList<>();
        float lx = Float.NaN, lz = Float.NaN;
        for (JsonElement g : geom) {
            if (g.isJsonNull()) continue;
            JsonObject p = g.getAsJsonObject();
            float px = x(p.get("lon").getAsDouble()), pz = z(p.get("lat").getAsDouble());
            if (!Float.isNaN(lx) && Math.hypot(px - lx, pz - lz) < 0.3) continue;
            pts.add(px); pts.add(pz);
            lx = px; lz = pz;
        }
        float[] a = new float[pts.size()];
        for (int i = 0; i < a.length; i++) a[i] = pts.get(i);
        return a;
    }

    static boolean closed(float[] p) {
        return p.length >= 8 && Math.hypot(p[0] - p[p.length - 2], p[1] - p[p.length - 1]) < 0.5;
    }

    /** Drops the repeated last point and makes the ring counter-clockwise (in x/z as drawn north-up). */
    static float[] ring(float[] p) {
        float[] r = closed(p) ? java.util.Arrays.copyOf(p, p.length - 2) : p;
        if (signedArea(r) < 0) {
            float[] rev = new float[r.length];
            for (int i = 0; i < r.length; i += 2) {
                rev[r.length - 2 - i] = r[i];
                rev[r.length - 1 - i] = r[i + 1];
            }
            r = rev;
        }
        return r;
    }

    static double signedArea(float[] r) {
        double a = 0;
        int n = r.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += (double) r[2 * i] * r[2 * j + 1] - (double) r[2 * j] * r[2 * i + 1];
        }
        return a / 2;
    }

    // ---- Buildings -------------------------------------------------------------------------

    static void readBuildings(JsonArray elements) {
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            String type = e.get("type").getAsString();
            if (tag(e, "building") == null) continue; // bare place_of_worship grounds are not buildings
            if (type.equals("way") && e.has("geometry")) {
                addBuilding(e, geometry(e.getAsJsonArray("geometry")));
            } else if (type.equals("relation") && e.has("members")) {
                for (JsonElement m : e.getAsJsonArray("members")) {
                    JsonObject mo = m.getAsJsonObject();
                    if (!"outer".equals(mo.get("role").getAsString()) || !mo.has("geometry")) continue;
                    addBuilding(e, geometry(mo.getAsJsonArray("geometry")));
                }
            }
        }
    }

    static void addBuilding(JsonObject e, float[] geom) {
        if (!closed(geom)) return;
        float[] r = ring(geom);
        if (r.length < 6) return;
        double area = Math.abs(signedArea(r));
        if (area < 12) return; // kiosks and slivers

        String b = tag(e, "building");
        String name = tag(e, "name");
        String religion = tag(e, "religion");
        String lname = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int kind = B_GENERIC;
        if ("mosque".equals(b) || "muslim".equals(religion) || lname.contains("mosque") || lname.contains("mosquée") || name != null && name.contains("جامع")) kind = B_MOSQUE;
        else if ("church".equals(b) || "cathedral".equals(b) || "chapel".equals(b) || "christian".equals(religion)
                || lname.contains("church") || lname.contains("cathedral") || lname.contains("église") || lname.contains("eglise")) kind = B_CHURCH;
        else if ("construction".equals(b)) kind = B_CONSTRUCTION;

        long id = e.get("id").getAsLong();
        float height = number(tag(e, "height"));
        float levels = number(tag(e, "building:levels"));
        if (Float.isNaN(height) && !Float.isNaN(levels)) height = levels * 3.3f + 1.5f;
        if (Float.isNaN(height)) height = estimateHeight(area, kind, id);
        float minHeight = number(tag(e, "min_height"));
        float minLevel = number(tag(e, "building:min_level"));
        if (Float.isNaN(minHeight)) minHeight = Float.isNaN(minLevel) ? 0f : minLevel * 3.3f;
        if (minHeight >= height) minHeight = 0f;
        buildings.add(new Building(height, minHeight, kind, r, name));
    }

    /** Most OSM buildings here have no height: guess floors from footprint size, same every run. */
    static float estimateHeight(double area, int kind, long id) {
        Random rnd = new Random(id);
        if (kind == B_MOSQUE || kind == B_CHURCH) return 14f + rnd.nextFloat() * 6f;
        if (kind == B_CONSTRUCTION) return 4f + rnd.nextFloat() * 10f;
        // Each area has its own skyline: the seafront (Raouche, Ain El Mreisseh) is mostly tall
        // residential towers, Hamra dense mid-rise blocks, the Souks low stone buildings, and
        // Downtown restored four-to-ten storey blocks. {min, spread} floors by footprint size.
        // A mountain village (a map with hills): houses of one to four storeys.
        int[][] profile = TERRAIN && !URBAN ? new int[][]{{1, 2}, {2, 2}, {2, 2}, {3, 2}} : switch (ID) {
            case "raouche", "ain_el_mreisseh" -> new int[][]{{3, 5}, {6, 7}, {9, 10}, {12, 12}};
            case "hamra" -> new int[][]{{3, 4}, {5, 5}, {7, 6}, {9, 7}};
            case "souks" -> new int[][]{{2, 2}, {2, 3}, {3, 3}, {3, 4}};
            default -> new int[][]{{2, 3}, {3, 4}, {4, 5}, {5, 6}};
        };
        int band = area < 120 ? 0 : area < 500 ? 1 : area < 1500 ? 2 : 3;
        int floors = profile[band][0] + rnd.nextInt(profile[band][1]);
        return floors * 3.3f + 1.5f;
    }

    static float number(String s) {
        if (s == null) return Float.NaN;
        try {
            return Float.parseFloat(s.trim().split("[ ;]")[0].replace(",", "."));
        } catch (NumberFormatException ex) {
            return Float.NaN;
        }
    }

    // ---- Roads -----------------------------------------------------------------------------

    static void readRoads(JsonArray elements) {
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            if (!e.has("geometry")) continue;
            float[] g = geometry(e.getAsJsonArray("geometry"));
            if (g.length < 4) continue;
            String h = tag(e, "highway");
            String areaHighway = tag(e, "area:highway");
            boolean isArea = "yes".equals(tag(e, "area")) || areaHighway != null;
            if (isArea) {
                if (closed(g)) areas.add(new Area(A_PLAZA, ring(g)));
                continue;
            }
            if (h == null || "yes".equals(tag(e, "tunnel")) || "construction".equals(h) || "proposed".equals(h)
                    || "elevator".equals(h) || "corridor".equals(h) || "platform".equals(h)) continue;
            int kind;
            float width;
            switch (h) {
                case "motorway", "trunk" -> { kind = R_MAJOR; width = 14f; }
                case "motorway_link", "trunk_link", "primary" -> { kind = R_MAJOR; width = 11f; }
                case "primary_link", "secondary", "secondary_link" -> { kind = R_MEDIUM; width = 9f; }
                case "tertiary", "tertiary_link" -> { kind = R_MEDIUM; width = 8f; }
                case "residential", "unclassified", "living_street" -> { kind = R_MINOR; width = 6.5f; }
                case "service" -> { kind = R_MINOR; width = 4.5f; }
                case "pedestrian" -> { kind = R_PEDESTRIAN; width = 6f; }
                // Farm tracks: dirt and sand roads, wide enough for a pickup truck.
                case "track" -> { kind = R_TRACK; width = 4f; }
                case "footway", "path", "steps", "cycleway", "bridleway" -> { kind = R_PATH; width = 2.5f; }
                default -> { continue; }
            }
            float lanes = number(tag(e, "lanes"));
            if (!Float.isNaN(lanes) && kind <= R_MINOR) width = Math.max(width, lanes * 3.3f);
            roads.add(new Road(kind, width, g));
        }
    }

    // ---- Areas, sea and trees --------------------------------------------------------------

    static void readAreas(JsonArray elements) {
        List<float[]> coast = new ArrayList<>();
        Random rnd = new Random(42);
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            String type = e.get("type").getAsString();
            if (type.equals("node")) {
                if ("tree".equals(tag(e, "natural"))) {
                    trees.add(new Tree(rnd.nextFloat() < 0.4f ? T_PALM : T_LEAFY,
                            x(e.get("lon").getAsDouble()), z(e.get("lat").getAsDouble()), 0.9f + rnd.nextFloat() * 0.4f));
                }
                continue;
            }
            if (!e.has("geometry")) continue;
            float[] g = geometry(e.getAsJsonArray("geometry"));
            if (g.length < 4) continue;
            String natural = tag(e, "natural"), leisure = tag(e, "leisure"), landuse = tag(e, "landuse");
            String manMade = tag(e, "man_made"), amenity = tag(e, "amenity"), place = tag(e, "place");

            if ("coastline".equals(natural)) { coast.add(g); continue; }
            if ("tree_row".equals(natural)) { treesAlong(g, 8f, T_LEAFY, rnd); continue; }
            if (("pier".equals(manMade) || "breakwater".equals(manMade)) && !closed(g)) {
                roads.add(new Road(R_PIER, "breakwater".equals(manMade) ? 8f : 4f, g));
                continue;
            }
            if (!closed(g)) continue;
            float[] r = ring(g);
            int kind;
            if ("park".equals(leisure) || "garden".equals(leisure) || "playground".equals(leisure)
                    || "grass".equals(landuse) || "recreation_ground".equals(landuse)
                    || "wood".equals(natural) || "scrub".equals(natural)) kind = A_PARK;
            else if ("pitch".equals(leisure)) kind = A_PITCH;
            else if ("parking".equals(amenity)) kind = A_PARKING;
            else if ("water".equals(natural) || "marina".equals(leisure)) kind = A_WATER;
            else if ("square".equals(place)) kind = A_PLAZA;
            else if ("sand".equals(natural) || "beach".equals(natural)) kind = A_SAND;
            else if ("bare_rock".equals(natural)) kind = A_PIER;
            else if ("pier".equals(manMade) || "breakwater".equals(manMade)) kind = A_PIER;
            else if ("construction".equals(landuse)) kind = A_CONSTRUCTION;
            else continue;
            areas.add(new Area(kind, r));
            if (kind == A_PARK) fillWithTrees(r, "wood".equals(natural) ? 6f : 11f, rnd);
        }
        buildSea(coast);
    }

    static void treesAlong(float[] line, float spacing, int kind, Random rnd) {
        float carry = 0f;
        for (int i = 0; i + 3 < line.length; i += 2) {
            float ax = line[i], az = line[i + 1], bx = line[i + 2], bz = line[i + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            float t = carry;
            while (t < len) {
                trees.add(new Tree(kind, ax + (bx - ax) * t / len, az + (bz - az) * t / len, 0.8f + rnd.nextFloat() * 0.4f));
                t += spacing;
            }
            carry = t - len;
        }
    }

    static void fillWithTrees(float[] ring, float spacing, Random rnd) {
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < ring.length; i += 2) {
            minX = Math.min(minX, ring[i]); maxX = Math.max(maxX, ring[i]);
            minZ = Math.min(minZ, ring[i + 1]); maxZ = Math.max(maxZ, ring[i + 1]);
        }
        for (float gx = minX + spacing / 2; gx < maxX; gx += spacing) {
            for (float gz = minZ + spacing / 2; gz < maxZ; gz += spacing) {
                float px = gx + (rnd.nextFloat() - 0.5f) * spacing * 0.7f;
                float pz = gz + (rnd.nextFloat() - 0.5f) * spacing * 0.7f;
                if (inside(ring, px, pz) && edgeDistance(ring, px, pz) > 1.5f && !inAnyBuilding(px, pz, 1.2f)) {
                    trees.add(new Tree(rnd.nextFloat() < 0.35f ? T_PALM : T_LEAFY, px, pz, 0.8f + rnd.nextFloat() * 0.6f));
                }
            }
        }
    }

    /**
     * Drops what lies outside this map's area. It only matters when a map is cut out of a bigger
     * download (Souks from Downtown); a normal download already fits. Buildings, areas and trees
     * are kept by their centre; roads if any point is inside, since the map edge stops players
     * anyway.
     */
    static void keepInsideMap() {
        float minX = x(WEST), maxX = x(EAST), minZ = z(NORTH), maxZ = z(SOUTH);
        java.util.function.BiPredicate<Float, Float> in = (px, pz) -> px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
        buildings.removeIf(b -> { float[] c = center(b.pts()); return !in.test(c[0], c[1]); });
        areas.removeIf(a -> { float[] c = center(a.pts()); return !in.test(c[0], c[1]); });
        trees.removeIf(t -> !in.test(t.x(), t.z()));
        roads.removeIf(r -> {
            float[] p = r.pts();
            for (int i = 0; i < p.length; i += 2) if (in.test(p[i], p[i + 1])) return false;
            return true;
        });
        sea.removeIf(s -> { float[] c = center(s); return !in.test(c[0], c[1]); });
    }

    static float[] center(float[] ring) {
        float sx = 0, sz = 0;
        int n = ring.length / 2;
        for (int i = 0; i < n; i++) { sx += ring[2 * i]; sz += ring[2 * i + 1]; }
        return new float[]{sx / n, sz / n};
    }

    /** Palm rows along the boulevards, on both sides, where there is room. */
    static void addStreetPalms() {
        Random rnd = new Random(7);
        for (Road road : roads) {
            if (road.kind() != R_MAJOR && road.kind() != R_MEDIUM) continue;
            float offset = road.width() / 2f + 2.2f;
            float[] p = road.pts();
            float carry = 6f;
            for (int i = 0; i + 3 < p.length; i += 2) {
                float ax = p[i], az = p[i + 1], bx = p[i + 2], bz = p[i + 3];
                float len = (float) Math.hypot(bx - ax, bz - az);
                if (len < 0.01f) continue;
                float nx = -(bz - az) / len, nz = (bx - ax) / len;
                float t = carry;
                while (t < len) {
                    float cx = ax + (bx - ax) * t / len, cz = az + (bz - az) * t / len;
                    for (int side = -1; side <= 1; side += 2) {
                        float px = cx + nx * offset * side, pz = cz + nz * offset * side;
                        if (!inAnyBuilding(px, pz, 1.5f) && !onAnyRoad(px, pz, 0.8f) && !inSeaOrWater(px, pz)) {
                            trees.add(new Tree(T_PALM, px, pz, 0.9f + rnd.nextFloat() * 0.3f));
                        }
                    }
                    t += 18f;
                }
                carry = t - len;
            }
        }
    }

    /**
     * Builds sea polygons from the coastline (OSM draws it with land on the left, sea on the
     * right): each stretch of coast crossing the map is closed off along the map edge on its
     * sea side.
     */
    static void buildSea(List<float[]> pieces) {
        List<List<float[]>> chains = mergeChains(pieces);
        float minX = x(WEST), maxX = x(EAST), minZ = z(NORTH), maxZ = z(SOUTH);
        for (List<float[]> chain : chains) {
            // A closed coastline inside the map is an island (Pigeon Rocks off Raouche): make it
            // a tall rock rising out of the sea.
            if (chain.size() >= 4 && near(chain.get(0), chain.get(chain.size() - 1))) {
                float[] flat = new float[(chain.size() - 1) * 2];
                for (int i = 0; i < chain.size() - 1; i++) { flat[2 * i] = chain.get(i)[0]; flat[2 * i + 1] = chain.get(i)[1]; }
                float[] r = ring(flat);
                double area = Math.abs(signedArea(r));
                boolean inside = true;
                for (int i = 0; i < r.length; i += 2) inside &= r[i] > minX && r[i] < maxX && r[i + 1] > minZ && r[i + 1] < maxZ;
                if (inside && area > 20) {
                    // Pigeon Rocks stand about 60 m high; a small islet is just a low rock.
                    float h = (float) (area < 300 ? 2.0 + Math.sqrt(area) * 0.7 : Math.min(60.0, 6.0 + Math.sqrt(area) * 1.25));
                    buildings.add(new Building(h, 0f, B_ROCK, r, "rock"));
                }
                continue;
            }
            for (List<float[]> run : clipToRect(chain, minX, minZ, maxX, maxZ)) {
                if (run.size() < 2) continue;
                float[] a = run.get(0), b = run.get(run.size() - 1);
                if (!onEdge(a, minX, minZ, maxX, maxZ) || !onEdge(b, minX, minZ, maxX, maxZ)) continue;
                List<float[]> poly = new ArrayList<>(run);
                // Sea is on the right of the direction of travel. With x east and z south (a
                // y-down screen), that side is enclosed by carrying on clockwise as seen on
                // screen, i.e. increasing "perimeter position" (north edge west→east, then east
                // edge north→south, ...). Checked against the preview picture.
                double pa = perimeterPos(a, minX, minZ, maxX, maxZ);
                double pb = perimeterPos(b, minX, minZ, maxX, maxZ);
                double perimeter = 2 * ((maxX - minX) + (maxZ - minZ));
                double[] cornerPos = {0, maxX - minX, (maxX - minX) + (maxZ - minZ), 2 * (maxX - minX) + (maxZ - minZ)};
                float[][] corners = {{minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}};
                // Walk from b on to a going the "increasing" way round the perimeter.
                double travelled = 0;
                double pos = pb;
                double total = (pa - pb + perimeter) % perimeter;
                while (true) {
                    // next corner strictly after pos (wrapping)
                    int next = -1;
                    double best = Double.MAX_VALUE;
                    for (int c = 0; c < 4; c++) {
                        double d = (cornerPos[c] - pos + perimeter) % perimeter;
                        if (d > 1e-6 && d < best) { best = d; next = c; }
                    }
                    if (travelled + best >= total - 1e-6) break;
                    travelled += best;
                    pos = cornerPos[next];
                    poly.add(corners[next]);
                }
                float[] flat = new float[poly.size() * 2];
                for (int i = 0; i < poly.size(); i++) { flat[2 * i] = poly.get(i)[0]; flat[2 * i + 1] = poly.get(i)[1]; }
                sea.add(ring(flat));
            }
        }
    }

    static double perimeterPos(float[] p, float minX, float minZ, float maxX, float maxZ) {
        float e = 0.5f;
        double w = maxX - minX, h = maxZ - minZ;
        if (Math.abs(p[1] - minZ) < e) return p[0] - minX;               // north edge, west→east
        if (Math.abs(p[0] - maxX) < e) return w + (p[1] - minZ);         // east edge, north→south
        if (Math.abs(p[1] - maxZ) < e) return w + h + (maxX - p[0]);     // south edge, east→west
        return 2 * w + h + (maxZ - p[1]);                                // west edge, south→north
    }

    static boolean onEdge(float[] p, float minX, float minZ, float maxX, float maxZ) {
        float e = 0.5f;
        return Math.abs(p[0] - minX) < e || Math.abs(p[0] - maxX) < e || Math.abs(p[1] - minZ) < e || Math.abs(p[1] - maxZ) < e;
    }

    static List<List<float[]>> mergeChains(List<float[]> pieces) {
        List<List<float[]>> chains = new ArrayList<>();
        for (float[] g : pieces) {
            List<float[]> pts = new ArrayList<>();
            for (int i = 0; i < g.length; i += 2) pts.add(new float[]{g[i], g[i + 1]});
            chains.add(pts);
        }
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < chains.size(); i++) {
                for (int j = 0; j < chains.size(); j++) {
                    if (i == j) continue;
                    List<float[]> a = chains.get(i), b = chains.get(j);
                    if (near(a.get(a.size() - 1), b.get(0))) {
                        a.addAll(b.subList(1, b.size()));
                        chains.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return chains;
    }

    static boolean near(float[] a, float[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.5; }

    /** Splits a polyline into the runs inside the rectangle, with points added where it crosses the edge. */
    static List<List<float[]>> clipToRect(List<float[]> line, float minX, float minZ, float maxX, float maxZ) {
        List<List<float[]>> runs = new ArrayList<>();
        List<float[]> run = null;
        for (int i = 0; i + 1 < line.size(); i++) {
            float[] a = line.get(i), b = line.get(i + 1);
            float[] seg = clipSegment(a, b, minX, minZ, maxX, maxZ);
            if (seg == null) { if (run != null) { runs.add(run); run = null; } continue; }
            float[] s = {seg[0], seg[1]}, e = {seg[2], seg[3]};
            if (run == null) { run = new ArrayList<>(); run.add(s); }
            run.add(e);
            boolean exited = Math.hypot(e[0] - b[0], e[1] - b[1]) > 0.01;
            if (exited) { runs.add(run); run = null; }
        }
        if (run != null) runs.add(run);
        return runs;
    }

    /** Liang–Barsky: the part of segment a–b inside the rectangle, or null. */
    static float[] clipSegment(float[] a, float[] b, float minX, float minZ, float maxX, float maxZ) {
        double t0 = 0, t1 = 1, dx = b[0] - a[0], dz = b[1] - a[1];
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {a[0] - minX, maxX - a[0], a[1] - minZ, maxZ - a[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return null; continue; }
            double r = q[i] / p[i];
            if (p[i] < 0) { if (r > t1) return null; if (r > t0) t0 = r; }
            else { if (r < t0) return null; if (r < t1) t1 = r; }
        }
        return new float[]{(float) (a[0] + t0 * dx), (float) (a[1] + t0 * dz), (float) (a[0] + t1 * dx), (float) (a[1] + t1 * dz)};
    }

    // ---- Geometry helpers ------------------------------------------------------------------

    static boolean inside(float[] ring, float px, float pz) {
        boolean in = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = ring[2 * i], zi = ring[2 * i + 1], xj = ring[2 * j], zj = ring[2 * j + 1];
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    static float edgeDistance(float[] ring, float px, float pz) {
        float best = Float.MAX_VALUE;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            best = Math.min(best, segDist(px, pz, ring[2 * j], ring[2 * j + 1], ring[2 * i], ring[2 * i + 1]));
        }
        return best;
    }

    static float segDist(float px, float pz, float ax, float az, float bx, float bz) {
        float dx = bx - ax, dz = bz - az;
        float len2 = dx * dx + dz * dz;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
        return (float) Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    static boolean inAnyBuilding(float px, float pz, float pad) {
        for (Building b : buildings) {
            if (inside(b.pts(), px, pz) || edgeDistance(b.pts(), px, pz) < pad) return true;
        }
        return false;
    }

    static boolean onAnyRoad(float px, float pz, float pad) {
        for (Road r : roads) {
            float[] p = r.pts();
            for (int i = 0; i + 3 < p.length; i += 2) {
                if (segDist(px, pz, p[i], p[i + 1], p[i + 2], p[i + 3]) < r.width() / 2f + pad) return true;
            }
        }
        return false;
    }

    static boolean inSeaOrWater(float px, float pz) {
        for (float[] s : sea) if (inside(s, px, pz)) return true;
        for (Area a : areas) if (a.kind() == A_WATER && inside(a.pts(), px, pz)) return true;
        return false;
    }

    /** Martyrs' Square if it's clear; otherwise the nearest clear spot on a road. */
    static float[] findSpawn() {
        if (!inAnyBuilding(0, 0, 1.5f) && !inSeaOrWater(0, 0)) return new float[]{0, 0};
        float[] best = {0, 0};
        double bestD = Double.MAX_VALUE;
        for (Road r : roads) {
            if (r.kind() == R_PIER) continue;
            float[] p = r.pts();
            for (int i = 0; i < p.length; i += 2) {
                double d = Math.hypot(p[i], p[i + 1]);
                if (d < bestD && !inAnyBuilding(p[i], p[i + 1], 1.5f)) { bestD = d; best = new float[]{p[i], p[i + 1]}; }
            }
        }
        return best;
    }

    /**
     * A mountain village's trees: OpenStreetMap rarely maps them, so pines and oaks are scattered
     * over open ground (not on roads, in buildings or right next to them), thicker away from houses.
     */
    static void addCountryTrees() {
        Random rnd = new Random(11);
        float minX = x(WEST), maxX = x(EAST), minZ = z(NORTH), maxZ = z(SOUTH);
        int wanted = (int) Math.min(1500, (maxX - minX) * (maxZ - minZ) / 700f);
        for (int tries = 0; tries < wanted * 6 && trees.size() < wanted; tries++) {
            float px = minX + rnd.nextFloat() * (maxX - minX);
            float pz = minZ + rnd.nextFloat() * (maxZ - minZ);
            if (inAnyBuilding(px, pz, 3f) || onAnyRoad(px, pz, 2.5f)) continue;
            // Gardens near houses are sparser than the hillside.
            if (inAnyBuilding(px, pz, 15f) && rnd.nextFloat() < 0.6f) continue;
            trees.add(new Tree(T_LEAFY, px, pz, 0.8f + rnd.nextFloat() * 0.7f));
        }
    }

    /**
     * The ground's height, for maps with hills: real elevation (the free "Terrarium" tiles of
     * AWS Open Data, from SRTM; tools/fetch_elevation.sh downloads them) sampled every [CELL]
     * metres, smoothed (the data is ~30 m and noisy), then levelled along the roads, so they climb
     * the hills smoothly and lie flat across. Heights are relative to the start point's ground (0).
     */
    static final class Terrain {
        static final float CELL = 4f;
        static final int ZOOM = 14;
        static int cols, rows;
        static float x0, z0;
        static float[] h;
        static final Map<String, BufferedImage> tiles = new java.util.HashMap<>();

        static void build(float[] spawn) throws IOException {
            x0 = x(WEST); z0 = z(NORTH);
            cols = (int) Math.ceil((x(EAST) - x0) / CELL) + 1;
            rows = (int) Math.ceil((z(SOUTH) - z0) / CELL) + 1;
            h = new float[cols * rows];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                double lat = LAT0 - (z0 + r * CELL) / M_PER_DEG_LAT;
                double lon = LON0 + (x0 + c * CELL) / M_PER_DEG_LON;
                h[r * cols + c] = (float) elevation(lat, lon);
            }
            // The sea: the data has the sea floor (hundreds of metres down); in the game it's sea level.
            boolean[] water = waterMask();
            boolean coast = false;
            for (int i = 0; i < h.length; i++) {
                if (water[i]) { h[i] = 0f; coast = true; }
                else h[i] = Math.max(h[i], 0f);
            }
            // In a city the data partly measures rooftops: an "opening" (lowest within OPENING
            // metres, then highest of those) takes away anything narrower than a block, leaving
            // the hills the streets are built on.
            if (URBAN) h = dilate(erode(h, OPENING_CELLS), OPENING_CELLS);
            // Smooth away the data's steps (about 3 passes of a 5-cell box ≈ a 10 m Gaussian).
            // (In a city, wider: about 20 m, as the rooftop filter leaves the data rougher.)
            for (int i = 0; i < 3; i++) h = blur(h, URBAN ? 4 : 2);
            // Land stays above the sea, and the sea stays flat.
            for (int i = 0; i < h.length; i++) h[i] = water[i] ? 0f : Math.max(h[i], COAST_HEIGHT);
            levelRoads();
            // In a city: ease the steps where roads clash, then level the roads again on the eased ground.
            if (URBAN) { easeRoads(); levelRoads(); easeRoads(); }
            // The sea back at sea level, except under a road (the Corniche runs right along the water's edge).
            for (int i = 0; i < h.length; i++) if (water[i] && !onRoad[i]) h[i] = 0f;
            // By the sea, heights are from sea level (the sea is at 0 in the game); inland, from the start.
            float base = coast ? 0f : at(spawn[0], spawn[1]);
            float start = at(spawn[0], spawn[1]);
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < h.length; i++) { h[i] -= base; lo = Math.min(lo, h[i]); hi = Math.max(hi, h[i]); }
            System.out.printf(Locale.US, "terrain %dx%d (%.0f m cells), start %.0f m above sea, ground %.0f..%.0f m (%s)%n",
                    cols, rows, CELL, start, lo, hi, coast ? "from sea level" : "from the start");
        }

        /** Each road follows the hill but evenly: its height along it is smoothed, and the ground across it made level with it. */
        static void levelRoads() {
            // For each grid point: the nearest road's height there, how far it is, and how wide that road's level band is.
            float[] target = new float[h.length];
            float[] nearest = new float[h.length];
            float[] band = new float[h.length];
            java.util.Arrays.fill(nearest, Float.MAX_VALUE);
            onRoad = new boolean[h.length];
            final float fade = 8f;
            // Busier roads are settled first; each quieter road then meets them at their height
            // where they join, ramping into it, so junctions don't step.
            List<Road> ordered = new ArrayList<>(roads);
            int[] rank = {0, 1, 2, 3, 5, 9, 4}; // by kind: major, medium, minor, pedestrian, path, pier, track
            ordered.sort((a, b) -> Integer.compare(rank[a.kind()], rank[b.kind()]));
            Map<Long, List<float[]>> settled = new java.util.HashMap<>();
            for (Road road : ordered) {
                if (road.kind() == R_PIER) continue;
                // Points every 2 m along the road, with the hill's height under each.
                List<float[]> samples = new ArrayList<>();
                float[] p = road.pts();
                for (int i = 0; i + 3 < p.length; i += 2) {
                    float len = (float) Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
                    int n = Math.max(1, (int) (len / 2f));
                    for (int k = 0; k < n; k++) {
                        float t = k / (float) n;
                        float sx = p[i] + (p[i + 2] - p[i]) * t, sz = p[i + 1] + (p[i + 3] - p[i + 1]) * t;
                        samples.add(new float[]{sx, sz, at(sx, sz)});
                    }
                }
                samples.add(new float[]{p[p.length - 2], p[p.length - 1], at(p[p.length - 2], p[p.length - 1])});
                // Smooth the height along the road over about 30 m.
                float[] along = new float[samples.size()];
                for (int i = 0; i < along.length; i++) along[i] = samples.get(i)[2];
                for (int pass = 0; pass < 3; pass++) along = blur1(along, URBAN ? 8 : 5);
                // Where it meets a road already settled, take that road's height, easing in over JOIN_RAMP metres.
                float[] dist = new float[along.length];
                for (int i = 1; i < along.length; i++) {
                    dist[i] = dist[i - 1] + (float) Math.hypot(samples.get(i)[0] - samples.get(i - 1)[0], samples.get(i)[1] - samples.get(i - 1)[1]);
                }
                List<float[]> joins = new ArrayList<>(); // (distance along, height difference)
                for (int i = 0; i < along.length; i++) {
                    float[] s = samples.get(i);
                    float[] hit = settledNear(settled, s[0], s[1], 1.5f);
                    if (hit != null) joins.add(new float[]{dist[i], hit[2] - along[i]});
                }
                if (!joins.isEmpty()) {
                    float[] fixed = along.clone();
                    for (int i = 0; i < along.length; i++) {
                        float sum = 0f, weights = 0f;
                        for (float[] j : joins) {
                            // Long enough that the ramp is never steeper than MAX_JOIN_GRADE (a crossing
                            // where one road really passes over the other comes down to meet it).
                            float ramp = Math.max(JOIN_RAMP, Math.abs(j[1]) / MAX_JOIN_GRADE);
                            float w = Math.max(0f, 1f - Math.abs(dist[i] - j[0]) / ramp);
                            sum += j[1] * w; weights += w;
                        }
                        if (weights > 0f) fixed[i] = along[i] + sum / Math.max(1f, weights);
                    }
                    along = fixed;
                }
                for (int i = 0; i < along.length; i++) {
                    float[] s = samples.get(i);
                    settled.computeIfAbsent(cellKey(s[0], s[1]), k -> new ArrayList<>()).add(new float[]{s[0], s[1], along[i]});
                }
                // Level a grid cell past the edge, so the heights in between can't tilt the road.
                float flat = road.width() / 2f + CELL + 0.5f;
                for (int i = 0; i < along.length; i++) {
                    float sx = samples.get(i)[0], sz = samples.get(i)[1];
                    int c0 = (int) Math.floor((sx - flat - fade - x0) / CELL), c1 = (int) Math.ceil((sx + flat + fade - x0) / CELL);
                    int r0 = (int) Math.floor((sz - flat - fade - z0) / CELL), r1 = (int) Math.ceil((sz + flat + fade - z0) / CELL);
                    for (int r = Math.max(0, r0); r <= Math.min(rows - 1, r1); r++) for (int c = Math.max(0, c0); c <= Math.min(cols - 1, c1); c++) {
                        float d = (float) Math.hypot(x0 + c * CELL - sx, z0 + r * CELL - sz);
                        if (d > flat + fade) continue;
                        // The nearest road sets each point's height (so a hairpin's two arms don't fight over it).
                        int idx = r * cols + c;
                        if (d < nearest[idx]) { nearest[idx] = d; target[idx] = along[i]; band[idx] = flat; }
                    }
                }
            }
            for (int i = 0; i < h.length; i++) {
                if (nearest[i] == Float.MAX_VALUE) continue;
                float w = nearest[i] <= band[i] ? 1f : 1f - (nearest[i] - band[i]) / fade;
                if (w > 0f) h[i] += (target[i] - h[i]) * smooth(w);
                onRoad[i] = nearest[i] <= band[i];
            }
        }

        /** Grid points under a road (inside its level band), set by levelRoads. */
        static boolean[] onRoad;
        /** The steepest step between neighbouring grid points under a road, after easing (rise over run). */
        static final float MAX_ROAD_GRADE = 0.35f;

        /**
         * Where two roads at different heights meet in a city (an interchange, a flyover, rough
         * data), the ground under them can step sharply. Points under roads that are much higher
         * or lower than a neighbour are eased towards their neighbours, again and again, until no
         * step is steeper than MAX_ROAD_GRADE. Ground away from roads (hillsides, cliffs) is left alone.
         */
        static void easeRoads() {
            float maxStep = MAX_ROAD_GRADE * CELL;
            for (int pass = 0; pass < 60; pass++) {
                float[] next = h.clone();
                int changed = 0;
                for (int r = 1; r < rows - 1; r++) for (int c = 1; c < cols - 1; c++) {
                    int i = r * cols + c;
                    if (!onRoad[i]) continue;
                    // Only against other road points: a cliff or bank beside the road mustn't drag it down.
                    float sum = 0f, worst = 0f;
                    int count = 0;
                    for (int j : new int[]{i - 1, i + 1, i - cols, i + cols}) {
                        if (!onRoad[j]) continue;
                        sum += h[j]; count++;
                        worst = Math.max(worst, Math.abs(h[i] - h[j]));
                    }
                    if (count == 0 || worst <= maxStep) continue;
                    next[i] = (h[i] + sum / count) / 2f;
                    changed++;
                }
                h = next;
                if (changed == 0) break;
            }
        }

        /** The opening filter's reach (cells either side): removes rooftops up to ~40 m across. */
        static final int OPENING_CELLS = 5;
        /** The lowest land beside the sea, metres above it. */
        static final float COAST_HEIGHT = 0.6f;

        /** Which grid points are in the sea or other water, drawn at the grid's scale. */
        static boolean[] waterMask() {
            BufferedImage img = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = img.createGraphics();
            g.scale(1.0 / CELL, 1.0 / CELL);
            g.translate(-x0, -z0);
            g.setColor(Color.WHITE);
            for (float[] s : sea) g.fill(path(s, true));
            // Harbour basins and marinas are sea; a fountain or pool up in the town is not.
            for (Area a : areas) if (a.kind() == A_PIER || (a.kind() == A_WATER && Math.abs(signedArea(a.pts())) > 2000.0)) g.fill(path(a.pts(), true));
            g.dispose();
            boolean[] m = new boolean[cols * rows];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) m[r * cols + c] = (img.getRGB(c, r) & 0xFF) > 127;
            return m;
        }

        /** Each point's lowest neighbour within [radius] cells (two passes, rows then columns). */
        static float[] erode(float[] src, int radius) { return extreme(src, radius, true); }

        /** Each point's highest neighbour within [radius] cells. */
        static float[] dilate(float[] src, int radius) { return extreme(src, radius, false); }

        static float[] extreme(float[] src, int radius, boolean min) {
            float[] tmp = new float[src.length], out = new float[src.length];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                float v = src[r * cols + c];
                for (int k = -radius; k <= radius; k++) { int cc = c + k; if (cc < 0 || cc >= cols) continue; float s = src[r * cols + cc]; v = min ? Math.min(v, s) : Math.max(v, s); }
                tmp[r * cols + c] = v;
            }
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                float v = tmp[r * cols + c];
                for (int k = -radius; k <= radius; k++) { int rr = r + k; if (rr < 0 || rr >= rows) continue; float s = tmp[rr * cols + c]; v = min ? Math.min(v, s) : Math.max(v, s); }
                out[r * cols + c] = v;
            }
            return out;
        }

        /** How far a quieter road eases into a busier one's height where they join, metres. */
        static final float JOIN_RAMP = 25f;
        /** The steepest a road may be made to meet another (rise over run). */
        static final float MAX_JOIN_GRADE = 0.12f;

        static long cellKey(float x, float z) { return ((long) Math.floor(x / 8f) << 32) ^ ((long) Math.floor(z / 8f) & 0xffffffffL); }

        /** A settled road point (x, z, height) within [r] metres of (x, z), the nearest, or null. */
        static float[] settledNear(Map<Long, List<float[]>> settled, float x, float z, float r) {
            float[] best = null;
            float bestD = r;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                List<float[]> list = settled.get(cellKey(x + dx * 8f, z + dz * 8f));
                if (list == null) continue;
                for (float[] p : list) {
                    float d = (float) Math.hypot(p[0] - x, p[1] - z);
                    if (d <= bestD) { bestD = d; best = p; }
                }
            }
            return best;
        }

        static float smooth(float t) { return t * t * (3f - 2f * t); }

        /** The ground's height at (x, z), bilinear between the grid points. */
        static float at(float x, float z) {
            float fc = Math.max(0f, Math.min(cols - 1.001f, (x - x0) / CELL));
            float fr = Math.max(0f, Math.min(rows - 1.001f, (z - z0) / CELL));
            int c = (int) fc, r = (int) fr;
            float tx = fc - c, tz = fr - r;
            float a = h[r * cols + c], b = h[r * cols + c + 1], d = h[(r + 1) * cols + c], e = h[(r + 1) * cols + c + 1];
            return (a + (b - a) * tx) + ((d + (e - d) * tx) - (a + (b - a) * tx)) * tz;
        }

        static float[] blur(float[] src, int radius) {
            float[] tmp = new float[src.length], out = new float[src.length];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                float sum = 0; int n = 0;
                for (int k = -radius; k <= radius; k++) { int cc = c + k; if (cc < 0 || cc >= cols) continue; sum += src[r * cols + cc]; n++; }
                tmp[r * cols + c] = sum / n;
            }
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                float sum = 0; int n = 0;
                for (int k = -radius; k <= radius; k++) { int rr = r + k; if (rr < 0 || rr >= rows) continue; sum += tmp[rr * cols + c]; n++; }
                out[r * cols + c] = sum / n;
            }
            return out;
        }

        static float[] blur1(float[] src, int radius) {
            float[] out = new float[src.length];
            for (int i = 0; i < src.length; i++) {
                float sum = 0; int n = 0;
                for (int k = -radius; k <= radius; k++) { int j = i + k; if (j < 0 || j >= src.length) continue; sum += src[j]; n++; }
                out[i] = sum / n;
            }
            return out;
        }

        /** Height above sea level at (lat, lon), bilinear between the elevation tiles' pixels. */
        static double elevation(double lat, double lon) throws IOException {
            double n = Math.pow(2, ZOOM);
            double fx = (lon + 180) / 360 * n * 256 - 0.5;
            double r = Math.toRadians(lat);
            double fy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n * 256 - 0.5;
            int px = (int) Math.floor(fx), py = (int) Math.floor(fy);
            double tx = fx - px, ty = fy - py;
            double a = pixel(px, py), b = pixel(px + 1, py), c = pixel(px, py + 1), d = pixel(px + 1, py + 1);
            return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
        }

        static double pixel(int px, int py) throws IOException {
            String key = ZOOM + "_" + (px >> 8) + "_" + (py >> 8);
            BufferedImage tile = tiles.get(key);
            if (tile == null) {
                File f = new File("tools/data/elevation/terrarium_" + key + ".png");
                if (!f.exists()) throw new IOException("Missing elevation tile " + f + " (run tools/fetch_elevation.sh " + ID + ")");
                tile = ImageIO.read(f);
                tiles.put(key, tile);
            }
            int rgb = tile.getRGB(px & 255, py & 255);
            return ((rgb >> 16) & 255) * 256 + ((rgb >> 8) & 255) + (rgb & 255) / 256.0 - 32768;
        }

        /** The grid: where it starts, its spacing and size, then each height in centimetres (row by row, north first). */
        static void write(DataOutputStream o) throws IOException {
            o.writeFloat(x0); o.writeFloat(z0); o.writeFloat(CELL);
            o.writeInt(cols); o.writeInt(rows);
            for (float v : h) o.writeInt(Math.round(v * 100f));
        }
    }

    // ---- Output ----------------------------------------------------------------------------

    static void write(File out, float[] spawn) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            o.writeBytes("BRMP");
            o.writeInt(TERRAIN ? 2 : 1);
            o.writeFloat(x(WEST)); o.writeFloat(z(NORTH)); o.writeFloat(x(EAST)); o.writeFloat(z(SOUTH));
            o.writeFloat(spawn[0]); o.writeFloat(spawn[1]);
            o.writeInt(buildings.size());
            for (Building b : buildings) {
                o.writeFloat(b.height()); o.writeFloat(b.minHeight()); o.writeByte(b.kind());
                points(o, b.pts());
            }
            o.writeInt(roads.size());
            for (Road r : roads) { o.writeByte(r.kind()); o.writeFloat(r.width()); points(o, r.pts()); }
            o.writeInt(areas.size());
            for (Area a : areas) { o.writeByte(a.kind()); points(o, a.pts()); }
            o.writeInt(sea.size());
            for (float[] s : sea) points(o, s);
            o.writeInt(trees.size());
            for (Tree t : trees) { o.writeByte(t.kind()); o.writeFloat(t.x()); o.writeFloat(t.z()); o.writeFloat(t.size()); }
            o.writeUTF(TERRAIN ? "Map data © OpenStreetMap contributors (ODbL) · Elevation: Mapzen Terrain Tiles (SRTM, NASA)"
                    : "Map data © OpenStreetMap contributors (ODbL)");
            // Version 2: the ground's height (see Terrain).
            if (TERRAIN) Terrain.write(o);
        }
    }

    static void points(DataOutputStream o, float[] p) throws IOException {
        o.writeShort(p.length / 2);
        for (float v : p) o.writeFloat(v);
    }

    /** Top-down picture of the map, 1 pixel per metre, north up, for checking the conversion. */
    /**
     * Hills on the preview: the ground lit from the north-west, darker on slopes facing away,
     * greener low down and drier up high, so the map picker shows the valleys and ridges.
     */
    static void hillShade(BufferedImage img, float minX, float minZ) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : Terrain.h) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        for (int py = 0; py < img.getHeight(); py++) for (int px = 0; px < img.getWidth(); px++) {
            float wx = minX + px, wz = minZ + py;
            float dx = Terrain.at(wx + 2f, wz) - Terrain.at(wx - 2f, wz);
            float dz = Terrain.at(wx, wz + 2f) - Terrain.at(wx, wz - 2f);
            // Normal of the slope, lit from the north-west and above.
            float nx = -dx / 4f, nz = -dz / 4f, len = (float) Math.sqrt(nx * nx + nz * nz + 1f);
            float light = Math.max(0f, (-0.5f * nx - 0.5f * nz + 0.7f) / len / 0.95f);
            float t = (Terrain.at(wx, wz) - lo) / Math.max(1f, hi - lo);
            int r = (int) ((176 + 40 * t) * (0.55f + 0.5f * light)), gr = (int) ((184 + 20 * t) * (0.55f + 0.5f * light)), b = (int) ((140 + 30 * t) * (0.55f + 0.5f * light));
            img.setRGB(px, py, (Math.min(255, r) << 16) | (Math.min(255, gr) << 8) | Math.min(255, b));
        }
    }

    static BufferedImage preview(File file, float[] spawn) throws IOException {
        float minX = x(WEST), maxX = x(EAST), minZ = z(NORTH), maxZ = z(SOUTH);
        int w = (int) (maxX - minX), h = (int) (maxZ - minZ);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(-minX, -minZ);
        g.setColor(new Color(0xD8D2C4));
        g.fillRect((int) minX, (int) minZ, w, h);
        if (TERRAIN) hillShade(img, minX, minZ);
        g.setColor(new Color(0x3A7CA5));
        for (float[] s : sea) g.fill(path(s, true));
        Map<Integer, Color> areaColors = Map.of(A_PARK, new Color(0x7FB069), A_PITCH, new Color(0x5E9E4B),
                A_PARKING, new Color(0xA7A39A), A_WATER, new Color(0x3A7CA5), A_PLAZA, new Color(0xE6DFCF),
                A_SAND, new Color(0xE8D8A8), A_PIER, new Color(0x9E9E9E), A_CONSTRUCTION, new Color(0xB59B7A));
        for (Area a : areas) { g.setColor(areaColors.get(a.kind())); g.fill(path(a.pts(), true)); }
        Color[] roadColors = {new Color(0x3C3C3C), new Color(0x4A4A4A), new Color(0x595959), new Color(0xCFC6B4), new Color(0xBDB29C), new Color(0x8C8C8C), new Color(0xC9A86A)};
        for (int pass = 5; pass >= 0; pass--) {
            for (Road r : roads) {
                if (r.kind() != pass) continue;
                g.setColor(roadColors[r.kind()]);
                g.setStroke(new BasicStroke(r.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path(r.pts(), false));
            }
        }
        for (Building b : buildings) {
            int shade = (int) Math.max(90, 200 - b.height() * 2.5f);
            Color c = b.kind() == B_MOSQUE ? new Color(0x2E6FB7) : b.kind() == B_CHURCH ? new Color(0xB05A3C) : b.kind() == B_ROCK ? new Color(0x9C8A74) : new Color(shade, shade - 12, shade - 30);
            g.setColor(c);
            g.fill(path(b.pts(), true));
            g.setColor(new Color(0x5A4E3C));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(path(b.pts(), true));
        }
        for (Tree t : trees) {
            g.setColor(t.kind() == T_PALM ? new Color(0x2F7D32) : new Color(0x3E8E41));
            g.fillOval((int) (t.x() - 2), (int) (t.z() - 2), 4, 4);
        }
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3f));
        g.drawOval((int) spawn[0] - 10, (int) spawn[1] - 10, 20, 20);
        g.dispose();
        ImageIO.write(img, "png", file);
        return img;
    }

    /** A small copy of the preview (about 480 px wide) for the in-app map picker. */
    static void thumbnail(BufferedImage full, File file) throws IOException {
        int w = 480;
        int h = Math.max(1, full.getHeight() * w / full.getWidth());
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(full, 0, 0, w, h, null);
        g.dispose();
        ImageIO.write(small, "png", file);
    }

    static Path2D path(float[] p, boolean close) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        if (close) path.closePath();
        return path;
    }
}
