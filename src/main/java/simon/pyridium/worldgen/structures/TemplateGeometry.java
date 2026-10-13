package simon.pyridium.worldgen.structures;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntUnaryOperator;

/**
 * Backt eine Strukturvorlage (Voxel-/Palettentabelle aus {@link StructureTables}, auch ihre dyadischen Mips) zu Flaechen fuer
 * Pyrits Modellpfad ({@code pyd_model_create}: Voxelisierung auf der GPU, danach geteilte HashDAG-Geometrie fuer beliebig viele
 * Instanzen). Je Schicht (y) werden gleichfarbige, sichtbare Voxel zu Rechtecken verschmolzen; ein Rechteck ist eine Flaeche auf
 * Hoehe y + 0.5 mit einer Probe je Voxelmitte (steps = Kantenlaengen), Farbe je Eckpunkt (ARGB). Reine Vorbereitung beim ersten
 * Gebrauch einer Vorlage, keine Welt-Generierung.
 */
public final class TemplateGeometry {
    public static final int FACE_BYTES = 128, MAX_FACES = 8192, MAX_SAMPLES = 1 << 20;

    /** Ergebnis: Flaechen im ModelFace-Layout, Zahl der Proben, DAG-Kantenlaenge 2^log2, tiefste feste Schicht */
    public record Baked(ByteBuffer faces, int faceCount, int samples, int log2, int lowestLayer, int sx, int sy, int sz) {
    }

    private TemplateGeometry() {
    }

    /**
     * @param level Mip-Ebene (0 = volle Aufloesung); Voxel einer groeberen Ebene sind 2^level Bloecke breit
     * @param color Blockzustand -&gt; RGB (0xRRGGBB), -1 = unsichtbar (Luft, Pflanzen ...)
     * @return null, wenn leer oder zu gross (Aufrufer nimmt eine groebere Ebene)
     */
    public static Baked bake(StructureTables t, int tpl, int level, IntUnaryOperator color) {
        int b = tpl * StructureTables.TPL_W;
        int[] row = new int[StructureTables.TPL_W];
        for (int i = 0; i < row.length; i++) row[i] = t.templates.getInt(b + i);
        int sx = row[StructureTables.TP_SX], sy = row[StructureTables.TP_SY], sz = row[StructureTables.TP_SZ], vox = row[StructureTables.TP_VOX];
        int pal = row[StructureTables.TP_PAL], mip = row[StructureTables.TP_MIP];
        if (level > 0) {
            if (mip < 0) return null;
            int nl = t.mips.getInt(mip);
            if (level > nl) return null;
            int d = mip + 1 + (level - 1) * 4;
            sx = t.mips.getInt(d);
            sy = t.mips.getInt(d + 1);
            sz = t.mips.getInt(d + 2);
            vox = t.mips.getInt(d + 3);
        }
        int size = 8, log2 = 3;
        int ext = Math.max(sx, Math.max(sy, sz));
        while (size < ext && log2 < 9) {
            size <<= 1;
            log2++;
        }
        if (ext > size) return null;
        // Farben je Voxel (0 = leer)
        int[] col = new int[sx * sy * sz];
        int lowest = -1;
        int any = 0;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    int idx = x + sx * (z + sz * y);
                    int v = t.voxels.getShort(vox + idx) & 0xffff;
                    if (v == 0) continue;
                    int st = t.palette.getInt(pal + v - 1);
                    if (st == 0) continue;
                    int c = color.applyAsInt(st);
                    if (c < 0) continue;
                    col[idx] = 0x1000000 | c;
                    any++;
                    if (lowest < 0) lowest = y;
                }
        if (any == 0) return null;
        ByteBuffer faces = ByteBuffer.allocateDirect(MAX_FACES * FACE_BYTES).order(ByteOrder.nativeOrder());
        int nf = 0, samples = 0;
        boolean[] used = new boolean[sx * sz];
        for (int y = 0; y < sy; y++) {
            java.util.Arrays.fill(used, false);
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    if (used[x + sx * z]) continue;
                    int c = col[x + sx * (z + sz * y)];
                    if (c == 0 || !exposed(col, sx, sy, sz, x, y, z)) continue;
                    // Rechteck: erst entlang x, dann entlang z
                    int w = 1;
                    while (x + w < sx && !used[x + w + sx * z] && col[x + w + sx * (z + sz * y)] == c && exposed(col, sx, sy, sz, x + w, y, z)) w++;
                    int h = 1;
                    outer:
                    while (z + h < sz) {
                        for (int i = 0; i < w; i++) {
                            int xx = x + i, zz = z + h;
                            if (used[xx + sx * zz] || col[xx + sx * (zz + sz * y)] != c || !exposed(col, sx, sy, sz, xx, y, zz)) break outer;
                        }
                        h++;
                    }
                    for (int j = 0; j < h; j++)
                        for (int i = 0; i < w; i++) used[x + i + sx * (z + j)] = true;
                    if (nf >= MAX_FACES || samples + w * h > MAX_SAMPLES) return null;
                    put(faces, nf++, samples, x, y, z, w, h, c & 0xffffff);
                    samples += w * h;
                }
        }
        faces.limit(nf * FACE_BYTES);
        return new Baked(faces, nf, samples, log2, lowest, sx, sy, sz);
    }

    /** Voxelliste einer Vorlage: Ergebnis {Puffer (uint4 x, y, z, attr je Voxel), Zahl, log2} oder null */
    public record Voxels(ByteBuffer data, int count, int log2) {
    }

    /**
     * Wie {@link #bake}, aber als Voxelliste mit Pyrit-Attributen (BlockPalette): sichtbare (an Luft grenzende) Voxel, Attribut je
     * Blockzustand; die Mip-Ebenen der Vorlage liefern je Zelle den bereits gewaehlten Zustand.
     * @param attr Blockzustand -&gt; Attribut, 0 = unsichtbar
     */
    public static Voxels bakeVoxels(StructureTables t, int tpl, int level, IntUnaryOperator attr) {
        int b = tpl * StructureTables.TPL_W;
        int sx = t.templates.getInt(b + StructureTables.TP_SX), sy = t.templates.getInt(b + StructureTables.TP_SY), sz = t.templates.getInt(b + StructureTables.TP_SZ);
        int vox = t.templates.getInt(b + StructureTables.TP_VOX), pal = t.templates.getInt(b + StructureTables.TP_PAL), mip = t.templates.getInt(b + StructureTables.TP_MIP);
        if (level > 0) {
            if (mip < 0 || level > t.mips.getInt(mip)) return null;
            int d = mip + 1 + (level - 1) * 4;
            sx = t.mips.getInt(d);
            sy = t.mips.getInt(d + 1);
            sz = t.mips.getInt(d + 2);
            vox = t.mips.getInt(d + 3);
        }
        int size = 8, log2 = 3, ext = Math.max(sx, Math.max(sy, sz));
        while (size < ext && log2 < 9) {
            size <<= 1;
            log2++;
        }
        if (ext > size) return null;
        int[] a = new int[sx * sy * sz];
        int any = 0;
        for (int i = 0; i < a.length; i++) {
            int v = t.voxels.getShort(vox + i) & 0xffff;
            if (v == 0) continue;
            int st = t.palette.getInt(pal + v - 1);
            if (st == 0) continue;
            a[i] = attr.applyAsInt(st);
            if (a[i] != 0) any++;
        }
        if (any == 0) return null;
        ByteBuffer out = ByteBuffer.allocateDirect(any * 16).order(ByteOrder.nativeOrder());
        int n = 0;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    int i = x + sx * (z + sz * y);
                    if (a[i] == 0 || !exposed(a, sx, sy, sz, x, y, z)) continue;
                    out.putInt(x).putInt(y).putInt(z).putInt(a[i]);
                    n++;
                }
        out.flip();
        return new Voxels(out, n, log2);
    }

    private static boolean exposed(int[] col, int sx, int sy, int sz, int x, int y, int z) {
        return x == 0 || y == 0 || z == 0 || x == sx - 1 || y == sy - 1 || z == sz - 1
                || col[x - 1 + sx * (z + sz * y)] == 0 || col[x + 1 + sx * (z + sz * y)] == 0
                || col[x + sx * (z + sz * (y - 1))] == 0 || col[x + sx * (z + sz * (y + 1))] == 0
                || col[x + sx * (z - 1 + sz * y)] == 0 || col[x + sx * (z + 1 + sz * y)] == 0;
    }

    /** eine Flaeche (ModelFace, 128 Bytes): Rechteck x..x+w, z..z+h auf Hoehe y + 0.5, w x h Proben */
    private static void put(ByteBuffer f, int n, int first, int x, int y, int z, int w, int h, int rgb) {
        int o = n * FACE_BYTES;
        f.putLong(o, 0);          // keine Textur: Farbe aus den Eckpunkten
        f.putInt(o + 8, 1);
        f.putInt(o + 12, 1);
        f.putInt(o + 16, first);
        f.putInt(o + 20, w);
        f.putInt(o + 24, h);
        f.putInt(o + 28, 0);
        float yy = y + 0.5f;
        float[][] p = {{x, yy, z}, {x + w, yy, z}, {x + w, yy, z + h}, {x, yy, z + h}};
        for (int c = 0; c < 4; c++) {
            f.putFloat(o + 32 + c * 12, p[c][0]);
            f.putFloat(o + 36 + c * 12, p[c][1]);
            f.putFloat(o + 40 + c * 12, p[c][2]);
            f.putFloat(o + 80 + c * 8, 0f);
            f.putFloat(o + 84 + c * 8, 0f);
            f.putInt(o + 112 + c * 4, 0xff000000 | rgb);
        }
    }
}
