package simon.pyridium.worldgen.structures.tools;

/**
 * Analytische Test-Umgebung, bitgleich zu wg_struct_synth.cu (Hoehe und Biom als reine Integer-Funktionen).
 * Nur Testwerkzeug: dient dazu, Vanillas Strukturcode und den GPU-Code unabhaengig vom echten Gelaende zu
 * vergleichen.
 */
public final class Synth {
    private Synth() {
    }

    public static int hash(int a, int b, int s) {
        int h = a * 0x9E3779B1 ^ b * 0x85EBCA77 ^ s * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    /** Fels bis unterhalb h, darueber Wasser bis zum Meeresspiegel 63 */
    public static int terrain(int x, int z) {
        return 64 + (hash(x >> 3, z >> 3, 1) & 7) + (((x >> 2) + (z >> 2)) & 3) - 4;
    }

    /** erste freie Hoehe (WORLD_SURFACE_WG: ueber Wasser) */
    public static int height(int x, int z) {
        return Math.max(terrain(x, z), 63);
    }

    /** Meeresboden-Heightmaps */
    public static int floor(int x, int z) {
        return terrain(x, z);
    }

    /** Basisspalte: 0 Luft, 1 Wasser, 2 Fels */
    public static int column(int x, int y, int z) {
        int h = terrain(x, z);
        return y < h ? 2 : (y < 63 ? 1 : 0);
    }

    /** Biom-Index (Universum der Groesse n) je Quart */
    public static int biome(int qx, int qy, int qz, int n) {
        return (int) ((hash(qx >> 6, qz >> 6, 7) & 0xFFFFFFFFL) % n);
    }
}
