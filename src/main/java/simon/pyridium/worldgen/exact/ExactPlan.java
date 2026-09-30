package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Bitgenaue GPU-Worldgen, Stufe Dichte: uebersetzt Vanillas <em>kompilierten</em> Sampler-Baum
 * (26.3: {@code RandomState.getSampler}, nach Mojangs eigener Optimierung) in eine Liste von
 * Volumen-Aufgaben – exakt die Aufrufstruktur von {@code DensitySampler.sampleVolume}: jede
 * Aufgabe ist ein (Sampler, Volumen)-Paar, Kinder erhalten genau die Volumen, die Vanilla ihnen
 * uebergibt (Zellvolumen der Interpolation, y-Scheiben, transponierte shift_b-Ebene ...).
 *
 * <p>Volumen sind relativ zum Chunk-Ursprung in x/z (ausser Achsen, die ein Slice absolut
 * festlegt): der Plan gilt fuer jeden Chunk, die GPU setzt nur den Ursprung ein. Dafuer muessen
 * alle Interpolationszellen 16 teilen (sonst {@link #unsupported}).
 *
 * <p>Arithmetik: alles, was Vanilla in float rechnet, rechnet die GPU in float (gleiche
 * Reihenfolge, keine FMA-Kontraktion). double nur fuer Rauschgitter-Koordinaten (je Achse
 * separierbar: kleine Tabellen je Chunk) und die verschobenen 2D-Klima-Rauschen.
 */
public final class ExactPlan {
    // ---- Opcodes (identisch in wg_exact_task_body.cuh) ----
    public static final int OP_CONST = 0, OP_ADD = 1, OP_SUB = 2, OP_MUL = 3, OP_DIV = 4, OP_MIN = 5, OP_MAX = 6,
            OP_CADD = 7, OP_CMUL = 8, OP_CSUB = 9, OP_CDIV = 10, OP_CMIN = 11, OP_CMAX = 12,
            OP_ABS = 13, OP_SQUARE = 14, OP_CUBE = 15, OP_LEAKY = 16, OP_NEGATE = 17, OP_RECIP = 18, OP_SQRT = 19,
            OP_LOG = 20, OP_SIGN = 21, OP_SQUEEZE = 22, OP_CLAMP = 23, OP_RANGE = 24, OP_RANGE_CONST = 25,
            OP_INTERVAL = 26, OP_THRESH1 = 27, OP_LERP = 28, OP_LERP_CF = 29, OP_LERP_CS = 30, OP_GRADIENT = 31,
            OP_NOISE = 32, OP_SHIFTED = 33, OP_INTERP = 34, OP_GATHER = 35, OP_BROADCAST = 36, OP_SPLINE = 37,
            OP_ROUND = 38, OP_FINDTOP = 39, OP_BEARD = 40;

    /** Kopf je Aufgabe (uints): siehe {@link Task#encode}. */
    public static final int TASK_WORDS = 24;
    /** Bit in Wort 12: Ergebnis in den Scratch schreiben (sonst nur gruppenintern im Register). */
    public static final int LIVE_OUT = 1 << 8;
    /** Hoechstzahl Aufgaben je fusionierter Gruppe (= WG_MAX_GROUP im Shader). */
    public static final int MAX_GROUP = 128;
    /** Lokale Werte-Slots je Thread einer Gruppe (= WG_MAX_SLOTS im Shader). */
    public static final int MAX_SLOTS = 32;

    /** Volumen: x/z relativ zum Chunk-Minimum (ausser abs-Bit), y absolut (ausser rel-Bit). */
    public record Vol(int sx, int sy, int sz, int x0, int y0, int z0, int stx, int sty, int stz, int absMask) {
        static final int ABS_X = 1, ABS_Z = 4;
        /** Pyridium: y relativ zum y-Ursprung des Chunks (gChunkY, LOD-Baender) */
        public static final int REL_Y = 2;

        int size() {
            return sx * sy * sz;
        }

        int index(int x, int y, int z) {
            return y + (x + z * sx) * sy;
        }

        /** Maximale Blockkoordinate relativ (x/z) bzw. absolut (y) – Vanilla maxBlockX(). */
        int maxX() {
            return x0 + sx * stx - 1;
        }

        int maxY() {
            return y0 + sy * sty - 1;
        }

        int maxZ() {
            return z0 + sz * stz - 1;
        }
    }

    /** Eine Volumen-Aufgabe (Ausgabe: Puffer mit vol.size() floats, Vanilla-Indexierung). */
    public static final class Task {
        public int id;
        public final int op;
        public final Vol vol;
        public final int[] in;
        public int p0, p1, p2, p3; // opcode-spezifisch (float-Bits, Parameter-Offsets, ...)
        public int offset;         // floats im Chunk-Scratch
        public final String what;
        /** Wert wird ausserhalb seiner Gruppe gelesen (oder ist Wurzel) und landet im Scratch. */
        public boolean liveOut = true;
        /** Gruppe (erste Aufgabe) und lokaler Slot (-1: in der Gruppe nicht gelesen). */
        public int group, slot = -1;

        Task(int id, int op, Vol vol, int[] in, String what) {
            this.id = id;
            this.op = op;
            this.vol = vol;
            this.in = in;
            this.what = what;
        }

        void encode(int[] dst, int at) {
            dst[at] = op;
            dst[at + 1] = offset;
            dst[at + 2] = vol.size();
            dst[at + 3] = vol.sx;
            dst[at + 4] = vol.sy;
            dst[at + 5] = vol.sz;
            dst[at + 6] = vol.x0;
            dst[at + 7] = vol.y0;
            dst[at + 8] = vol.z0;
            dst[at + 9] = vol.stx;
            dst[at + 10] = vol.sty;
            dst[at + 11] = vol.stz;
            dst[at + 12] = vol.absMask | (liveOut ? LIVE_OUT : 0);
            dst[at + 13] = in.length > 0 ? in[0] : -1;
            dst[at + 14] = in.length > 1 ? in[1] : -1;
            dst[at + 15] = in.length > 2 ? in[2] : -1;
            dst[at + 16] = p0;
            dst[at + 17] = p1;
            dst[at + 18] = p2;
            dst[at + 19] = p3;
            dst[at + 20] = slot;
            dst[at + 21] = group;
        }
    }

    // ---- Ergebnis ----
    public final List<Task> tasks = new ArrayList<>();
    public final List<String> unsupported = new ArrayList<>();
    /** Zusatzparameter (Splines, Intervall-Schwellen, Listen von Aufgaben-IDs). */
    public final IntArrayList params = new IntArrayList();
    /** Rausch-Lagen: je Lage 64 uints Permutation + Kopf, siehe {@link #encodeLayers}. */
    public final List<Layer> layers = new ArrayList<>();
    /** Rausch-Stapel: Liste von Lagen-Indizes. */
    public final List<int[]> stacks = new ArrayList<>();
    /** Koordinaten-Tabellen (je Aufgabe x Lage x Achse): Eintraege fuer die GPU-Vorstufe (FP64). */
    public final List<CoordSeg> coordSegs = new ArrayList<>();
    public int coordEntries;      // Eintraege je Chunk (je 3 Worte: floor, rel, fudged)
    public int scratchFloats;     // floats je Chunk
    public int root = -1;

    /** Eine Rausch-Lage (PerlinNoise oder SmearedPerlinNoise) mit ihren exakten Parametern. */
    public record Layer(byte[] perms, double offX, double offY, double offZ, double frequency, float amplitude,
                        boolean smeared, double fudgeYScale) {
    }

    /**
     * Koordinaten-Abschnitt: count Eintraege entlang einer Achse; Blockkoordinate
     * = (Chunk-Ursprung falls rel) + base + i*step, Wert = wrap(block * scale) + offset.
     */
    public record CoordSeg(int layer, int axisKind, int count, boolean relative, int axis, int base, int step,
                           double scale, int entryOffset) {
        /** axisKind: 0 = x-Art, 1 = y-Art (bei geschmierten Lagen mit Fudge), 2 = z-Art. */
    }

    private final Map<Object, Integer> stackIds = new IdentityHashMap<>();
    private final Map<Object, Integer> layerIds = new IdentityHashMap<>();
    private final Map<Key, Integer> memo = new HashMap<>();

    private record Key(Object sampler, Vol vol) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.sampler == sampler && k.vol.equals(vol);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(sampler) * 31 + vol.hashCode();
        }
    }

    /** Gemeinsames Punktprogramm (find_top_surface-Proben, Aquifer, Materialregeln). */
    public final ExactPointProgram points = new ExactPointProgram(this);

    private ExactPlan() {
    }

    /** Pyridium: Interpolation direkt auf gestuften Volumen (LOD-Plaene). */
    private boolean lodInterp;

    /**
     * Pyridium: Plan fuer LOD-Volumen (Schritt > 1). Interpolation und Rauschen werden an den
     * Stuetzpunkten ausgewertet – bitgleich zu Vanilla an genau diesen Bloecken.
     */
    public static ExactPlan forLod(List<DensitySampler> samplers, List<Vol> vols) {
        ExactPlan p = new ExactPlan();
        p.lodInterp = true;
        for (int i = 0; i < samplers.size(); i++) p.roots.add(p.task(samplers.get(i), vols.get(i)));
        p.root = p.roots.get(0);
        p.layout(false);
        return p;
    }

    /** Plan fuer den ganzen Chunk (16 x height x 16, Schritt 1) – wie NoiseBasedChunkGenerator.chunkVolume. */
    public static ExactPlan forChunk(DensitySampler root, int minY, int height) {
        return forChunk(root, minY, height, false);
    }

    /** keepAll: jeder Aufgabenpuffer bleibt erhalten (Tests: Vergleich je Knoten), sonst Wiederverwendung. */
    public static ExactPlan forChunk(DensitySampler root, int minY, int height, boolean keepAll) {
        ExactPlan p = new ExactPlan();
        p.root = p.task(root, new Vol(16, height, 16, 0, minY, 0, 1, 1, 1, 0));
        p.layout(keepAll);
        return p;
    }

    /**
     * Mehrere Wurzeln (z. B. finalDensity auf dem Chunk + sechs Klimafunktionen auf dem
     * Quart-Raster) in einem Plan: gemeinsame Teilbaeume auf gleichem Volumen werden nur einmal
     * gerechnet. {@link #roots} haelt die Aufgaben-ID je Wurzel in Reihenfolge.
     */
    public static ExactPlan forRoots(List<DensitySampler> samplers, List<Vol> vols, boolean keepAll) {
        ExactPlan p = new ExactPlan();
        for (int i = 0; i < samplers.size(); i++) p.roots.add(p.task(samplers.get(i), vols.get(i)));
        p.root = p.roots.get(0);
        p.layout(keepAll);
        return p;
    }

    /** Aufgaben-IDs der Wurzeln (forRoots); leben bis zum Schluss. */
    public final IntArrayList roots = new IntArrayList();

    /**
     * Fusionierte Dispatches: {erste Aufgabe, Anzahl}. Eine Gruppe ist ein Lauf aufeinander-
     * folgender Aufgaben auf identischem Volumen; ein Thread rechnet je Element alle Aufgaben der
     * Gruppe nacheinander (gleiche Arithmetik, also bitgleich), Zwischenwerte bleiben lokal.
     */
    public final List<int[]> groups = new ArrayList<>();

    /** Push-Wert 'Aufgabe' und 'Anzahl' eines Dispatches sowie seine Elementzahl je Chunk. */
    public int groupSize(int[] g) {
        return tasks.get(g[0]).vol.size();
    }

    public boolean groupIsPoint(int[] g) {
        return tasks.get(g[0]).op == OP_FINDTOP;
    }

    /**
     * Topologische Umordnung, die Aufgaben gleichen Volumens zusammenzieht (lange Gruppen): aus
     * den bereiten Aufgaben bevorzugt eine mit dem aktuellen Volumen, sonst die aelteste. IDs,
     * Eingaenge, Parameterlisten, Memo und Wurzeln werden umnummeriert.
     */
    private void reorder() {
        int n = tasks.size();
        if (n == 0) return;
        int[] pending = new int[n];
        List<IntArrayList> users = new ArrayList<>();
        for (int i = 0; i < n; i++) users.add(new IntArrayList());
        for (Task t : tasks)
            for (int r : new java.util.TreeSet<>(java.util.Arrays.stream(readsOf(t)).boxed().toList())) {
                pending[t.id]++;
                users.get(r).add(t.id);
            }
        java.util.TreeSet<Integer> ready = new java.util.TreeSet<>();
        for (int i = 0; i < n; i++) if (pending[i] == 0) ready.add(i);
        int[] order = new int[n];
        Vol cur = null;
        for (int k = 0; k < n; k++) {
            Integer pick = null;
            if (cur != null)
                for (int c : ready) {
                    Task t = tasks.get(c);
                    if (t.vol.equals(cur) && t.op != OP_FINDTOP) { pick = c; break; }
                }
            if (pick == null) pick = ready.first();
            ready.remove(pick);
            order[k] = pick;
            cur = tasks.get(pick).vol;
            for (int u : users.get(pick)) if (--pending[u] == 0) ready.add(u);
        }
        int[] newId = new int[n];
        for (int k = 0; k < n; k++) newId[order[k]] = k;
        // Parameterlisten mit Aufgaben-IDs (je Eintrag genau einmal umschreiben)
        java.util.Set<Integer> idParams = new java.util.TreeSet<>();
        for (Task t : tasks) {
            if (t.op == OP_INTERVAL) for (int i = 0; i <= t.p1; i++) idParams.add(t.p0 + t.p1 + i);
            if (t.op == OP_SPLINE) for (int i = 0; i < t.p1; i++) idParams.add(t.p0 + i);
        }
        for (int pi : idParams) params.set(pi, newId[params.getInt(pi)]);
        List<Task> sorted = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            Task t = tasks.get(order[k]);
            for (int i = 0; i < t.in.length; i++) if (t.in[i] >= 0) t.in[i] = newId[t.in[i]];
            t.id = k;
            sorted.add(t);
        }
        tasks.clear();
        tasks.addAll(sorted);
        memo.replaceAll((key, id) -> newId[id]);
        for (int i = 0; i < roots.size(); i++) roots.set(i, newId[roots.getInt(i)]);
        if (root >= 0) root = newId[root];
    }

    /** Lokale Slots je Gruppe: nur gruppenintern gelesene Werte, Wiederverwendung nach letzter Lesung. */
    private void assignSlots() {
        for (int[] g : groups) {
            int end = g[0] + g[1];
            int[] last = new int[g[1]];
            java.util.Arrays.fill(last, -1);
            for (int k = 0; k < g[1]; k++)
                for (int r : slotReadsOf(tasks.get(g[0] + k)))
                    if (r >= g[0] && r < end) last[r - g[0]] = Math.max(last[r - g[0]], k);
            int[] slotFreeAt = new int[MAX_SLOTS]; // Slot frei ab Aufgabe (Index in Gruppe)
            int used = 0;
            for (int k = 0; k < g[1]; k++) {
                Task t = tasks.get(g[0] + k);
                t.group = g[0];
                t.slot = -1;
                if (last[k] < 0) continue;
                int s = -1;
                for (int i = 0; i < used; i++) if (slotFreeAt[i] <= k) { s = i; break; }
                if (s < 0) {
                    if (used == MAX_SLOTS) throw new IllegalStateException("Gruppe braucht mehr als " + MAX_SLOTS + " Slots");
                    s = used++;
                }
                t.slot = s;
                // Slot erst nach der letzten Lesung frei (Lesen vor Schreiben in derselben Aufgabe)
                slotFreeAt[s] = last[k] + 1;
            }
        }
    }

    /** Passt die Gruppe [i, end) in MAX_SLOTS gleichzeitig lebende lokale Werte? */
    private boolean slotsFit(int i, int end) {
        int[] last = new int[end - i];
        java.util.Arrays.fill(last, -1);
        for (int k = i; k < end; k++)
            for (int r : slotReadsOf(tasks.get(k))) if (r >= i && r < end) last[r - i] = Math.max(last[r - i], k - i);
        int live = 0, max = 0;
        int[] endsAt = new int[end - i + 1];
        for (int k = 0; k < end - i; k++) {
            live -= endsAt[k];
            if (last[k] >= 0) {
                live++;
                endsAt[last[k] + 1 < endsAt.length ? last[k] + 1 : endsAt.length - 1]++;
            }
            max = Math.max(max, live);
        }
        return max <= MAX_SLOTS;
    }

    private void buildGroups() {
        int n = tasks.size();
        int i = 0;
        while (i < n) {
            Task t = tasks.get(i);
            int j = i + 1;
            if (t.op != OP_FINDTOP) {
                while (j < n && j - i < MAX_GROUP) {
                    Task u = tasks.get(j);
                    if (u.op == OP_FINDTOP || !u.vol.equals(t.vol)) break;
                    if (!slotsFit(i, j + 1)) break;
                    // Eingaben mit Nachbarzugriff (andere Positionen) muessen vor der Gruppe fertig sein
                    boolean positional = u.op == OP_INTERP || u.op == OP_GATHER || u.op == OP_BROADCAST;
                    if (positional && u.in.length > 0 && u.in[0] >= i) break;
                    j++;
                }
            }
            groups.add(new int[]{i, j - i});
            i = j;
        }
    }

    /** Chunk-Volumen (16 x height x 16) und Quart-Raster (4 x height/4 x 4, Schritt 4) der Biome. */
    public static Vol chunkVol(int minY, int height) {
        return new Vol(16, height, 16, 0, minY, 0, 1, 1, 1, 0);
    }

    public static Vol quartVol(int minY, int height) {
        return new Vol(4, height >> 2, 4, 0, minY, 0, 4, 4, 4, 0);
    }

    /** Plan fuer ein beliebiges (chunk-relatives) Volumen, z. B. Test einzelner Knoten. */
    public static ExactPlan forVolume(DensitySampler root, Vol vol) {
        ExactPlan p = new ExactPlan();
        p.root = p.task(root, vol);
        p.layout(true);
        return p;
    }

    /** Elementweise Eingaenge in0..in2 (ohne Nachbarzugriff): die einzigen Lesungen aus lokalen Slots. */
    private static int[] slotReadsOf(Task t) {
        if (t.op == OP_INTERP || t.op == OP_GATHER || t.op == OP_BROADCAST) return new int[0];
        return java.util.Arrays.stream(t.in).filter(i -> i >= 0).toArray();
    }

    /** Alle Aufgaben, deren Puffer eine Aufgabe liest (Eingaenge und Listen in params). */
    public int[] readsOf(Task t) {
        IntArrayList r = new IntArrayList();
        for (int i : t.in) if (i >= 0) r.add(i);
        if (t.op == OP_INTERVAL) for (int i = 0; i <= t.p1; i++) r.add(params.getInt(t.p0 + t.p1 + i));
        if (t.op == OP_SPLINE) for (int i = 0; i < t.p1; i++) r.add(params.getInt(t.p0 + i));
        return r.toIntArray();
    }

    /**
     * Scratch-Offsets: Aufgaben laufen in Listenreihenfolge; ein Puffer lebt bis zur letzten
     * lesenden Aufgabe (die Wurzel bis zum Schluss) und wird danach per First-Fit wiederverwendet.
     */
    private void layout(boolean keepAll) {
        reorder();
        buildGroups();
        assignSlots();
        int n = tasks.size();
        int[] groupEnd = new int[n], groupStart = new int[n];
        for (int[] g : groups) for (int k = 0; k < g[1]; k++) {
            groupStart[g[0] + k] = g[0];
            groupEnd[g[0] + k] = g[0] + g[1] - 1;
        }
        // Lesen innerhalb einer Gruppe verlaengert die Lebensdauer bis zu deren Ende (Threads der
        // Gruppe laufen nicht im Gleichschritt, ein frueh freigegebener Puffer wuerde ueberschrieben)
        int[] lastUse = new int[n];
        for (Task t : tasks) {
            lastUse[t.id] = t.id;
            t.liveOut = keepAll;
        }
        for (Task t : tasks) {
            int[] slotReads = slotReadsOf(t);
            for (int r : readsOf(t)) {
                lastUse[r] = Math.max(lastUse[r], groupEnd[t.id]);
                // ausserhalb der Gruppe oder nicht per Slot gelesen (Listen, Nachbarzugriff) -> Scratch
                boolean viaSlot = false;
                for (int q : slotReads) viaSlot |= q == r;
                if (groupStart[r] != groupStart[t.id] || !viaSlot) tasks.get(r).liveOut = true;
            }
        }
        if (root >= 0) {
            lastUse[root] = n;
            tasks.get(root).liveOut = true;
        }
        for (int r : roots) {
            lastUse[r] = n;
            tasks.get(r).liveOut = true;
        }
        if (keepAll) {
            int off = 0;
            for (Task t : tasks) {
                t.offset = off;
                off += t.vol.size();
            }
            scratchFloats = off;
            return;
        }
        // freie Bereiche (Offset -> Laenge), sortiert
        java.util.TreeMap<Integer, Integer> free = new java.util.TreeMap<>();
        java.util.List<List<Task>> dying = new ArrayList<>();
        for (int i = 0; i <= n; i++) dying.add(new ArrayList<>());
        int top = 0;
        for (Task t : tasks) {
            if (!t.liveOut) {
                t.offset = 0;
                releaseDying(free, dying, t);
                continue;
            }
            int size = t.vol.size();
            Integer at = null;
            for (var e : free.entrySet()) if (e.getValue() >= size) { at = e.getKey(); break; }
            if (at != null) {
                int len = free.remove(at);
                if (len > size) free.put(at + size, len - size);
                t.offset = at;
            } else {
                t.offset = top;
                top += size;
            }
            dying.get(lastUse[t.id]).add(t);
            releaseDying(free, dying, t);
        }
        scratchFloats = top;
    }

    /** Nach Aufgabe t freigeben, was sie (bzw. ihre Gruppe) zuletzt gelesen hat. */
    private static void releaseDying(java.util.TreeMap<Integer, Integer> free, List<List<Task>> dying, Task t) {
        for (Task d : dying.get(t.id)) release(free, d.offset, d.vol.size());
    }

    private static void release(java.util.TreeMap<Integer, Integer> free, int off, int len) {
        var lower = free.floorEntry(off);
        if (lower != null && lower.getKey() + lower.getValue() == off) {
            off = lower.getKey();
            len += lower.getValue();
            free.remove(lower.getKey());
        }
        var higher = free.ceilingEntry(off + len);
        if (higher != null && higher.getKey() == off + len) {
            len += higher.getValue();
            free.remove(higher.getKey());
        }
        free.put(off, len);
    }

    public String stats() {
        Map<String, Integer> ops = new java.util.TreeMap<>();
        for (Task t : tasks) ops.merge(t.what, 1, Integer::sum);
        return tasks.size() + " Aufgaben in " + groups.size() + " Dispatches, " + scratchFloats + " floats/Chunk, " + layers.size() + " Lagen, "
                + coordEntries + " Koordinaten; " + ops + (unsupported.isEmpty() ? "" : " NICHT: " + unsupported);
    }

    // =====================================================================================
    // Planung (spiegelt sampleVolume jedes Sampler-Typs)
    // =====================================================================================

    private int emit(int op, Vol vol, String what, int... in) {
        Task t = new Task(tasks.size(), op, vol, in, what);
        tasks.add(t);
        return t.id;
    }

    private Task last() {
        return tasks.get(tasks.size() - 1);
    }

    private int beardKernelOffset = -1;

    /** Beardifier.BEARD_KERNEL (24^3 floats) einmal in die Parameter. */
    private int beardKernel() throws ReflectiveOperationException {
        if (beardKernelOffset < 0) {
            var f = net.minecraft.world.level.levelgen.Beardifier.class.getDeclaredField("BEARD_KERNEL");
            f.setAccessible(true);
            float[] k = (float[]) f.get(null);
            beardKernelOffset = params.size();
            for (float x : k) params.add(Float.floatToRawIntBits(x));
        }
        return beardKernelOffset;
    }

    private int task(Object s, Vol v) {
        Key k = new Key(s, v);
        Integer m = memo.get(k);
        if (m != null) return m;
        int r = build(s, v);
        memo.put(k, r);
        return r;
    }

    private int build(Object s, Vol v) {
        String cls = s.getClass().getName();
        String name = cls.substring(cls.lastIndexOf('.') + 1);
        try {
            switch (name) {
                case "CachingDensitySampler":
                case "ContextBoundSampler": {
                    if (name.startsWith("Caching")) return task(get(s, "input"), v);
                    // Beardifier (Struktur-Anpassung): Daten je Chunk im Beard-Puffer (ExactBeard)
                    if (get(s, "key") == net.minecraft.world.level.levelgen.Beardifier.CONTEXT_KEY) {
                        emit(OP_BEARD, v, "beard");
                        last().p0 = beardKernel();
                        return last().id;
                    }
                    // sonst (Blender) ohne Nutzerfeld: Vanillas Fallback-Sampler
                    return task(get(s, "fallbackSampler"), v);
                }
                case "ConstantFunction$Sampler": {
                    emit(OP_CONST, v, "const");
                    last().p0 = Float.floatToRawIntBits(f(s, "value"));
                    return last().id;
                }
                case "BlendDensityFunction$Sampler":
                    return task(get(s, "input"), v); // ohne Blender: Durchreichen
                case "BinaryFunction$AddSampler":
                    return emit(OP_ADD, v, "add", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$SubSampler":
                    return emit(OP_SUB, v, "sub", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$MulSampler":
                    return emit(OP_MUL, v, "mul", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$DivSampler":
                    return emit(OP_DIV, v, "div", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$MinSampler":
                    return emit(OP_MIN, v, "min", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$MaxSampler":
                    return emit(OP_MAX, v, "max", task(get(s, "left"), v), task(get(s, "right"), v));
                case "BinaryFunction$ConstAddSampler":
                    return constOp(OP_CADD, v, "cadd", get(s, "left"), f(s, "right"));
                case "BinaryFunction$ConstMulSampler":
                    return constOp(OP_CMUL, v, "cmul", get(s, "left"), f(s, "right"));
                case "BinaryFunction$ConstSubSampler":
                    return constOp(OP_CSUB, v, "csub", get(s, "right"), f(s, "left"));
                case "BinaryFunction$ConstDivSampler":
                    return constOp(OP_CDIV, v, "cdiv", get(s, "right"), f(s, "left"));
                case "BinaryFunction$ConstMinSampler":
                    return constOp(OP_CMIN, v, "cmin", get(s, "left"), f(s, "right"));
                case "BinaryFunction$ConstMaxSampler":
                    return constOp(OP_CMAX, v, "cmax", get(s, "left"), f(s, "right"));
                case "UnaryFunction$AbsSampler":
                    return emit(OP_ABS, v, "abs", task(get(s, "input"), v));
                case "UnaryFunction$SquareSampler":
                    return emit(OP_SQUARE, v, "square", task(get(s, "input"), v));
                case "UnaryFunction$CubeSampler":
                    return emit(OP_CUBE, v, "cube", task(get(s, "input"), v));
                case "UnaryFunction$LeakyReLUSampler":
                    return constOp(OP_LEAKY, v, "leaky", get(s, "input"), f(s, "negativeFactor"));
                case "UnaryFunction$NegateSampler":
                    return emit(OP_NEGATE, v, "negate", task(get(s, "input"), v));
                case "UnaryFunction$ReciprocalSampler":
                    return emit(OP_RECIP, v, "recip", task(get(s, "input"), v));
                case "UnaryFunction$SqrtSampler":
                    return emit(OP_SQRT, v, "sqrt", task(get(s, "input"), v));
                case "UnaryFunction$LogSampler":
                    if (!unsupported.contains("log")) unsupported.add("log");
                    return emit(OP_LOG, v, "log", task(get(s, "input"), v));
                case "UnaryFunction$SignSampler":
                    return emit(OP_SIGN, v, "sign", task(get(s, "input"), v));
                case "UnaryFunction$SqueezeSampler":
                    return emit(OP_SQUEEZE, v, "squeeze", task(get(s, "input"), v));
                case "ClampFunction$Sampler": {
                    int id = emit(OP_CLAMP, v, "clamp", task(get(s, "input"), v));
                    tasks.get(id).p0 = Float.floatToRawIntBits(f(s, "min"));
                    tasks.get(id).p1 = Float.floatToRawIntBits(f(s, "max"));
                    return id;
                }
                case "RangeChoiceFunction$Sampler": {
                    int id = emit(OP_RANGE, v, "range", task(get(s, "input"), v), task(get(s, "whenInRange"), v),
                            task(get(s, "whenOutOfRange"), v));
                    tasks.get(id).p0 = Float.floatToRawIntBits(f(s, "minInclusive"));
                    tasks.get(id).p1 = Float.floatToRawIntBits(f(s, "maxExclusive"));
                    return id;
                }
                case "RangeChoiceFunction$ConstSampler": {
                    int id = emit(OP_RANGE_CONST, v, "rangeK", task(get(s, "input"), v));
                    Task t = tasks.get(id);
                    t.p0 = Float.floatToRawIntBits(f(s, "minInclusive"));
                    t.p1 = Float.floatToRawIntBits(f(s, "maxExclusive"));
                    t.p2 = Float.floatToRawIntBits(f(s, "whenInRange"));
                    t.p3 = Float.floatToRawIntBits(f(s, "whenOutOfRange"));
                    return id;
                }
                case "IntervalSelectFunction$SingleThresholdSampler": {
                    int id = emit(OP_THRESH1, v, "thresh1", task(get(s, "input"), v), task(get(s, "ifBelow"), v),
                            task(get(s, "ifAbove"), v));
                    tasks.get(id).p0 = Float.floatToRawIntBits(f(s, "threshold"));
                    return id;
                }
                case "IntervalSelectFunction$Sampler": {
                    float[] th = (float[]) get(s, "thresholds");
                    DensitySampler[] fns = (DensitySampler[]) get(s, "samplers");
                    int[] fnTasks = new int[fns.length];
                    for (int i = 0; i < fns.length; i++) fnTasks[i] = task(fns[i], v);
                    int id = emit(OP_INTERVAL, v, "interval", task(get(s, "input"), v));
                    Task t = tasks.get(id);
                    t.p0 = params.size();
                    t.p1 = th.length;
                    for (float x : th) params.add(Float.floatToRawIntBits(x));
                    for (int ft : fnTasks) params.add(ft);
                    return id;
                }
                case "LerpFunction$Sampler":
                    return emit(OP_LERP, v, "lerp", task(get(s, "alpha"), v), task(get(s, "first"), v),
                            task(get(s, "second"), v));
                case "LerpFunction$ConstFirstSampler": {
                    int id = emit(OP_LERP_CF, v, "lerpCF", task(get(s, "alpha"), v), task(get(s, "second"), v));
                    tasks.get(id).p0 = Float.floatToRawIntBits(f(s, "first"));
                    return id;
                }
                case "LerpFunction$ConstSecondSampler": {
                    int id = emit(OP_LERP_CS, v, "lerpCS", task(get(s, "alpha"), v), task(get(s, "first"), v));
                    tasks.get(id).p0 = Float.floatToRawIntBits(f(s, "second"));
                    return id;
                }
                case "GradientFunction$ClampedSampler":
                case "GradientFunction$RepeatSampler":
                case "GradientFunction$MirroredRepeatSampler":
                    return gradient(s, name, v);
                case "NoiseFunction$Sampler":
                    return noise(get(s, "noise"), d(s, "xzScale"), d(s, "yScale"), 1.0F, false, v);
                case "NoiseFunction$ShiftedXzSampler":
                    return shifted(s, v, false);
                case "NoiseFunction$ShiftedXyzSampler":
                    return shifted(s, v, true);
                case "ShiftNoiseFunction$ShiftB$1":
                    return shiftB(s, v);
                case "InterpolatedFunction$Sampler":
                    return interpolated(s, v);
                case "SliceFunction$YSampler":
                    return slice(get(s, "input"), v, 2, 0, i(s, "y"), 0);
                case "SliceFunction$XSampler":
                    return slice(get(s, "input"), v, 1, i(s, "x"), 0, 0);
                case "SliceFunction$ZSampler":
                    return slice(get(s, "input"), v, 4, 0, 0, i(s, "z"));
                case "SliceFunction$XzSampler":
                    return slice(get(s, "input"), v, 5, i(s, "x"), 0, i(s, "z"));
                case "SplineFunction$Sampler":
                    return spline(s, v);
                case "FindTopSurfaceFunction$Sampler": {
                    // Volumenpfad: upperBound als Volumen, je (x, z) Punktproben der Dichte
                    if (v.sy != 1 && !unsupported.contains("findtop-sizeY")) unsupported.add("findtop-sizeY");
                    int ub = task(get(s, "upperBound"), v);
                    ExactPointProgram.Entry dens = points.compile((DensitySampler) get(s, "density"));
                    int id = emit(OP_FINDTOP, v, "findTop", ub);
                    Task t = tasks.get(id);
                    t.p0 = dens.start();
                    t.p1 = i(s, "lowerBound");
                    t.p2 = i(s, "cellHeight");
                    return id;
                }
                default:
                    if (!unsupported.contains(name)) unsupported.add(name);
                    emit(OP_CONST, v, "unsupported:" + name);
                    return last().id;
            }
        } catch (ReflectiveOperationException e) {
            if (!unsupported.contains(name + " (reflection)")) unsupported.add(name + " (reflection)");
            emit(OP_CONST, v, "unsupported:" + name);
            return last().id;
        }
    }

    private int constOp(int op, Vol v, String what, Object input, float k) {
        int id = emit(op, v, what, task(input, v));
        tasks.get(id).p0 = Float.floatToRawIntBits(k);
        return id;
    }

    private int gradient(Object s, String name, Vol v) throws ReflectiveOperationException {
        Object axis = get(s, "axis");
        int ax = switch (axis.toString()) {
            case "x" -> 0;
            case "y" -> 1;
            default -> 2;
        };
        int mode = name.endsWith("ClampedSampler") ? 0 : name.endsWith("$RepeatSampler") ? 1 : 2;
        int id = emit(OP_GRADIENT, v, "gradient");
        Task t = tasks.get(id);
        t.p0 = ax | (mode << 4);
        t.p1 = params.size();
        params.add(i(s, "fromCoordinate"));
        if (mode == 0) {
            params.add(i(s, "minCoordinate"));
            params.add(i(s, "maxCoordinate"));
        } else {
            params.add(i(s, "coordinateRange"));
            params.add(0);
        }
        params.add(Float.floatToRawIntBits(f(s, "fromValue")));
        params.add(Float.floatToRawIntBits(f(s, "coordinateFactor")));
        return id;
    }

    // ---- Rauschen (Volumenpfad) ----

    /** Fuer Punktprogramme: Stapel dieses Plans (gleiche Lagen-/Stapelpuffer). */
    public int stackIdPublic(Object noise) throws ReflectiveOperationException {
        stackOffsets = null; // Kodierung neu erzeugen
        return stackId(noise);
    }

    /** Simplex-Lage (nur Permutation + Versatz) fuer die Biomtemperatur; Frequenz/Amplitude extra. */
    public int simplexLayer(Object noise) throws ReflectiveOperationException {
        for (int i = 0; i < layers.size(); i++) if (layerObj.get(i) == noise) return i;
        byte[] perms = (byte[]) fieldIn(noise, "perms");
        layers.add(new Layer(perms.clone(), (double) fieldIn(noise, "offsetX"), (double) fieldIn(noise, "offsetY"),
                (double) fieldIn(noise, "offsetZ"), 0.0, 0.0F, false, 0.0));
        layerObj.add(noise);
        return layers.size() - 1;
    }

    /** Leerer Plan, nur als Rausch-/Parameter-Wirt fuer Punktprogramme. */
    public static ExactPlan empty() {
        ExactPlan p = new ExactPlan();
        p.layout(true);
        return p;
    }

    /** Stapel eintragen: nur Perlin/Smeared-Lagen (26.3 Vanilla); sonst unsupported. */
    private int stackId(Object noise) throws ReflectiveOperationException {
        Integer id = stackIds.get(noise);
        if (id != null) return id;
        List<Integer> ls = new ArrayList<>();
        Object[] arr;
        if (noise.getClass().getName().contains("NoiseStack")) {
            arr = (Object[]) fieldIn(noise, "layers");
        } else {
            throw new ReflectiveOperationException("Rauschtyp " + noise.getClass().getName());
        }
        for (Object layer : arr) {
            Object n = get(layer, "noise");
            double freq = d(layer, "frequency");
            float amp = f(layer, "amplitude");
            if (n.getClass() != PerlinNoise.class && n.getClass() != SmearedPerlinNoise.class) {
                throw new ReflectiveOperationException("Lagen-Rauschen " + n.getClass().getName());
            }
            ls.add(layerId(n, freq, amp));
        }
        int[] a = ls.stream().mapToInt(Integer::intValue).toArray();
        stacks.add(a);
        stackIds.put(noise, stacks.size() - 1);
        return stacks.size() - 1;
    }

    private int layerId(Object n, double freq, float amp) throws ReflectiveOperationException {
        // Gleiche Permutation kann mit anderer Frequenz/Amplitude in verschiedenen Stapeln stehen
        // (addStack): Schluessel ist das Lagen-Tripel, nicht nur das Rauschobjekt.
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (layerObj.get(i) == n && l.frequency == freq && l.amplitude == amp) return i;
        }
        byte[] perms = (byte[]) fieldIn(n, "perms");
        double ox = (double) fieldIn(n, "offsetX"), oy = (double) fieldIn(n, "offsetY"), oz = (double) fieldIn(n, "offsetZ");
        boolean smeared = n instanceof SmearedPerlinNoise;
        double fudge = smeared ? (double) fieldIn(n, "fudgeYScale") : 0.0;
        layers.add(new Layer(perms.clone(), ox, oy, oz, freq, amp, smeared, fudge));
        layerObj.add(n);
        return layers.size() - 1;
    }

    private final List<Object> layerObj = new ArrayList<>();

    /**
     * NOISE-Aufgabe: Volumenpfad von PerlinNoise/SmearedPerlinNoise.addToVolume je Lage.
     * transposed: shift_b-Ebene (virtuelles x = Welt-z, virtuelles y = Welt-x, z = 0).
     */
    private int noise(Object noise, double xzScale, double yScale, float outerAmp, boolean transposed, Vol v)
            throws ReflectiveOperationException {
        int stack = stackId(noise);
        int id = emit(OP_NOISE, v, transposed ? "noiseT" : "noise");
        Task t = tasks.get(id);
        t.p0 = stack;
        t.p1 = Float.floatToRawIntBits(outerAmp);
        t.p2 = params.size(); // je Lage: Lagen-Index, Tabellen-Offsets x/y/z (Eintraege), effektive Amplitude
        t.p3 = transposed ? 1 : 0;
        int[] ls = stacks.get(stack);
        // Virtuelle Achsen (Vanilla: transposedVolume(sizeZ, sizeX, 1, minZ, minX, 0, stepZ, stepX, 1))
        for (int li : ls) {
            Layer L = layers.get(li);
            double sXz = xzScale * L.frequency, sY = yScale * L.frequency; // NoiseStack.addToVolume
            int xo, yo, zo;
            if (!transposed) {
                xo = coordSeg(li, 0, v.sx, (v.absMask & Vol.ABS_X) == 0, 0, v.x0, v.stx, sXz);
                yo = coordSeg(li, 1, v.sy, (v.absMask & Vol.REL_Y) != 0, 1, v.y0, v.sty, sY);
                zo = coordSeg(li, 2, v.sz, (v.absMask & Vol.ABS_Z) == 0, 2, v.z0, v.stz, sXz);
            } else {
                // virtuelles x: Welt-z mit xzScale; virtuelles y: Welt-x mit yScale; z: Block 0 mit xzScale
                xo = coordSeg(li, 0, v.sz, (v.absMask & Vol.ABS_Z) == 0, 2, v.z0, v.stz, sXz);
                yo = coordSeg(li, 1, v.sx, (v.absMask & Vol.ABS_X) == 0, 0, v.x0, v.stx, sY);
                zo = coordSeg(li, 2, 1, false, 1, 0, 1, sXz);
            }
            params.add(li);
            params.add(xo);
            params.add(yo);
            params.add(zo);
            params.add(Float.floatToRawIntBits(outerAmp * L.amplitude)); // amplitude * layer.amplitude (float)
        }
        return id;
    }

    private int coordSeg(int layer, int kind, int count, boolean relative, int axis, int base, int step, double scale) {
        int off = coordEntries;
        coordSegs.add(new CoordSeg(layer, kind, count, relative, axis, base, step, scale, off));
        coordEntries += count;
        return off;
    }

    /** shift_b: transponierte 2D-Ebene, Amplitude 4, danach ueber y verteilt. */
    private int shiftB(Object s, Vol v) throws ReflectiveOperationException {
        Object noise = fieldIn(s, "val$noise");
        // Transponierte Ebene je (x,z) einmal; Ausgabe identisch ueber y (setRange).
        Vol plane = new Vol(v.sx, 1, v.sz, v.x0, 0, v.z0, v.stx, 1, v.stz, v.absMask & ~Vol.REL_Y);
        int n = noise(noise, 0.25, 0.25, 4.0F, true, plane);
        if (v.sy == 1 && v.y0 == 0) return n;
        int id = emit(OP_BROADCAST, v, "broadcastY", n);
        tasks.get(id).p0 = 2;
        return id;
    }

    /** Verschobenes Rauschen: Punktpfad je Volumenpunkt, Koordinaten in FP64 (wenige 2D-Punkte). */
    private int shifted(Object s, Vol v, boolean xyz) throws ReflectiveOperationException {
        int sx = task(get(s, "shiftX"), v);
        int sz = task(get(s, "shiftZ"), v);
        int sy = xyz ? task(get(s, "shiftY"), v) : -1;
        int stack = stackId(get(s, "noise"));
        int id = emit(OP_SHIFTED, v, "shifted", sx, sz, sy);
        Task t = tasks.get(id);
        t.p0 = stack;
        t.p1 = params.size();
        long xz = Double.doubleToRawLongBits(d(s, "xzScale")), y = Double.doubleToRawLongBits(d(s, "yScale"));
        params.add((int) xz);
        params.add((int) (xz >>> 32));
        params.add((int) y);
        params.add((int) (y >>> 32));
        return id;
    }

    // ---- Interpolation ----

    private int interpolated(Object s, Vol v) throws ReflectiveOperationException {
        Object input = get(s, "input");
        int cxz = i(s, "cellSizeXz"), cy = i(s, "cellSizeY");
        if (16 % cxz != 0 && !unsupported.contains("interp-cell-" + cxz)) unsupported.add("interp-cell-" + cxz);
        boolean relX = (v.absMask & Vol.ABS_X) == 0, relZ = (v.absMask & Vol.ABS_Z) == 0;
        // floorMod-Pruefungen: relative Achsen haben Chunk-Ursprung (Vielfaches von 16, cxz | 16)
        if ((v.stx == cxz || v.sx == 1) && (v.sty == cy || v.sy == 1) && (v.stz == cxz || v.sz == 1)
                && Math.floorMod(v.x0, cxz) == 0 && Math.floorMod(v.y0, cy) == 0 && Math.floorMod(v.z0, cxz) == 0) {
            return task(input, v);
        }
        // Pyridium (LOD): Schritte als Vielfache der Zellgroesse, an Zellecken ausgerichtet -> die
        // Interpolation liefert dort bitgenau den Eckwert, also direkt auswerten
        if ((v.stx % cxz == 0 || v.sx == 1) && (v.sty % cy == 0 || v.sy == 1) && (v.stz % cxz == 0 || v.sz == 1)
                && Math.floorMod(v.x0, cxz) == 0 && Math.floorMod(v.y0, cy) == 0 && Math.floorMod(v.z0, cxz) == 0) {
            return task(input, v);
        }
        if (v.stx == 1 && v.sty == 1 && v.stz == 1) return interpBlock(input, s, v, cxz, cy);
        // Pyridium (LOD): OP_INTERP rechnet auch mit Schrittweite (op34), ohne Vollvolumen
        if (lodInterp) return interpBlock(input, s, v, cxz, cy);
        Vol blockVol = new Vol(v.sx * v.stx, v.sy * v.sty, v.sz * v.stz, v.x0, v.y0, v.z0, 1, 1, 1, v.absMask);
        int b = interpBlock(input, s, blockVol, cxz, cy);
        return emit(OP_GATHER, v, "gather", b);
    }

    private int interpBlock(Object input, Object s, Vol v, int cxz, int cy) throws ReflectiveOperationException {
        int minCellX = Math.floorDiv(v.x0, cxz), minCellY = Math.floorDiv(v.y0, cy), minCellZ = Math.floorDiv(v.z0, cxz);
        int maxCellX = Math.floorDiv(v.maxX(), cxz), maxCellY = Math.floorDiv(v.maxY(), cy), maxCellZ = Math.floorDiv(v.maxZ(), cxz);
        int ccx = maxCellX - minCellX + 1, ccy = maxCellY - minCellY + 1, ccz = maxCellZ - minCellZ + 1;
        Vol cell = new Vol(Math.floorMod(v.maxX(), cxz) == 0 ? ccx : ccx + 1,
                Math.floorMod(v.maxY(), cy) == 0 ? ccy : ccy + 1,
                Math.floorMod(v.maxZ(), cxz) == 0 ? ccz : ccz + 1,
                minCellX * cxz, minCellY * cy, minCellZ * cxz, cxz, cy, cxz, v.absMask);
        int in = task(input, cell);
        int id = emit(OP_INTERP, v, "interp", in);
        Task t = tasks.get(id);
        t.p0 = cxz | (cy << 16);
        t.p1 = Float.floatToRawIntBits(f(s, "cellSizeXzInv"));
        t.p2 = Float.floatToRawIntBits(f(s, "cellSizeYInv"));
        return id;
    }

    // ---- Slices ----

    /** mask: 1 = x fest, 2 = y fest, 4 = z fest (absolute Koordinaten). */
    private int slice(Object input, Vol v, int mask, int x, int y, int z) {
        boolean fx = (mask & 1) != 0, fy = (mask & 2) != 0, fz = (mask & 4) != 0;
        boolean aliasX = !fx || (v.sx == 1 && (v.absMask & Vol.ABS_X) != 0 && v.x0 == x);
        boolean aliasY = !fy || (v.sy == 1 && v.y0 == y && (v.absMask & Vol.REL_Y) == 0);
        boolean aliasZ = !fz || (v.sz == 1 && (v.absMask & Vol.ABS_Z) != 0 && v.z0 == z);
        // XzSampler prueft beide Achsen gemeinsam; Einzelachsen einzeln (gleiche Semantik)
        if (aliasX && aliasY && aliasZ) return task(input, v);
        int absMask = (v.absMask | (fx ? Vol.ABS_X : 0) | (fz ? Vol.ABS_Z : 0)) & (fy ? ~Vol.REL_Y : ~0);
        Vol iv = new Vol(fx ? 1 : v.sx, fy ? 1 : v.sy, fz ? 1 : v.sz, fx ? x : v.x0, fy ? y : v.y0, fz ? z : v.z0,
                v.stx, v.sty, v.stz, absMask);
        int in = task(input, iv);
        int id = emit(OP_BROADCAST, v, "broadcast", in);
        tasks.get(id).p0 = mask;
        return id;
    }

    // ---- Splines ----

    private int spline(Object s, Vol v) throws ReflectiveOperationException {
        Object fn = get(s, "sampler");
        List<Object> coords = new ArrayList<>();
        IntArrayList code = new IntArrayList();
        int rootNode = encodeSpline(fn, coords, code);
        int[] coordTasks = new int[coords.size()];
        for (int i = 0; i < coords.size(); i++) coordTasks[i] = task(coords.get(i), v);
        int id = emit(OP_SPLINE, v, "spline");
        Task t = tasks.get(id);
        t.p0 = params.size();                 // Koordinaten-Aufgaben
        t.p1 = coordTasks.length;
        for (int ct : coordTasks) params.add(ct);
        t.p2 = params.size();                 // Spline-Knoten (relativ zu p2)
        t.p3 = rootNode;
        params.addAll(code);
        return id;
    }

    /**
     * Knoten: Konstante = [0, floatBits]; Multipoint = [1, coordIdx, n, loc[n], der[n], child[n]]
     * (child = Knoten-Offset relativ zum Code-Anfang).
     */
    private int encodeSpline(Object fn, List<Object> coords, IntArrayList code) throws ReflectiveOperationException {
        Object sp = fn;
        if (fn.getClass().isAnonymousClass()) sp = fieldIn(fn, "val$multipoint");
        if (sp instanceof CubicSpline.Constant<?> c) {
            int at = code.size();
            code.add(0);
            code.add(Float.floatToRawIntBits(c.value()));
            return at;
        }
        if (!(sp instanceof CubicSpline.Multipoint<?> mp)) {
            // BoundedFloatFunction.constant(...) fuer konstante Splines
            float value = (float) fn.getClass().getMethod("apply", Object.class).invoke(fn, (Object) null);
            int at = code.size();
            code.add(0);
            code.add(Float.floatToRawIntBits(value));
            return at;
        }
        Object coord = mp.coordinate(); // SamplerCoordinate(sampler, index, range)
        Object sampler = get(coord, "sampler");
        int ci = -1;
        for (int i = 0; i < coords.size(); i++) if (coords.get(i) == sampler) ci = i;
        if (ci < 0) {
            coords.add(sampler);
            ci = coords.size() - 1;
        }
        int n = mp.locations().length;
        int[] kids = new int[n];
        List<?> values = mp.values();
        for (int i = 0; i < n; i++) kids[i] = encodeSpline(values.get(i), coords, code);
        int at = code.size();
        code.add(1);
        code.add(ci);
        code.add(n);
        for (float l : mp.locations()) code.add(Float.floatToRawIntBits(l));
        for (float d : mp.derivatives()) code.add(Float.floatToRawIntBits(d));
        for (int k : kids) code.add(k);
        return at;
    }

    // =====================================================================================
    // Kodierung fuer die GPU
    // =====================================================================================

    /** Kodiert die Aufgaben; Stapel-Indizes (NOISE/SHIFTED p0) werden zu Offsets in encodeStacks(). */
    public int[] encodeTasks() {
        if (stackOffsets == null) encodeStacks();
        int[] a = new int[tasks.size() * TASK_WORDS];
        for (Task t : tasks) {
            t.encode(a, t.id * TASK_WORDS);
            if (t.op == OP_NOISE || t.op == OP_SHIFTED) a[t.id * TASK_WORDS + 16] = stackOffsets[t.p0];
        }
        return a;
    }

    /** Lagen: je 72 uints = 64 Permutation (Bytes gepackt) + offX/offY/offZ/fudge (je 2) . */
    public static final int LAYER_WORDS = 72;

    public int[] encodeLayers() {
        int[] a = new int[layers.size() * LAYER_WORDS];
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            int b = i * LAYER_WORDS;
            for (int k = 0; k < 256; k++) a[b + (k >> 2)] |= (l.perms[k] & 0xFF) << ((k & 3) * 8);
            putD(a, b + 64, l.offX);
            putD(a, b + 66, l.offY);
            putD(a, b + 68, l.offZ);
            putD(a, b + 70, l.smeared ? l.fudgeYScale : 0.0);
        }
        return a;
    }

    /** Stapel: [n, layer0, amp0bits, freq0 (2), ...] je Stapel an Offset stackOffsets[i]. */
    public int[] stackOffsets;

    public int[] encodeStacks() {
        IntArrayList a = new IntArrayList();
        stackOffsets = new int[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) {
            stackOffsets[i] = a.size();
            int[] ls = stacks.get(i);
            a.add(ls.length);
            for (int li : ls) {
                Layer l = layers.get(li);
                a.add(li);
                a.add(Float.floatToRawIntBits(l.amplitude));
                long fb = Double.doubleToRawLongBits(l.frequency);
                a.add((int) fb);
                a.add((int) (fb >>> 32));
            }
        }
        return a.toIntArray();
    }

    /**
     * Koordinaten-Segmente fuer die FP64-Vorstufe: je Segment 12 uints
     * [layer, kind | smeared<<2 | relative<<3 | axis<<4, count, base, step, entryOffset, scaleLo, scaleHi, 0...].
     */
    public static final int SEG_WORDS = 12;

    public int[] encodeSegs() {
        int[] a = new int[coordSegs.size() * SEG_WORDS];
        for (int i = 0; i < coordSegs.size(); i++) {
            CoordSeg c = coordSegs.get(i);
            int b = i * SEG_WORDS;
            a[b] = c.layer;
            a[b + 1] = c.axisKind | (layers.get(c.layer).smeared ? 4 : 0) | (c.relative ? 8 : 0) | (c.axis << 4);
            a[b + 2] = c.count;
            a[b + 3] = c.base;
            a[b + 4] = c.step;
            a[b + 5] = c.entryOffset;
            putD(a, b + 6, c.scale);
        }
        return a;
    }

    private static void putD(int[] a, int at, double v) {
        long bits = Double.doubleToRawLongBits(v);
        a[at] = (int) bits;
        a[at + 1] = (int) (bits >>> 32);
    }

    // =====================================================================================
    // Reflection (Vanillas Sampler sind Records/private Klassen)
    // =====================================================================================

    static Object get(Object o, String name) throws ReflectiveOperationException {
        if (o.getClass().isRecord()) {
            for (RecordComponent rc : o.getClass().getRecordComponents()) {
                if (rc.getName().equals(name)) {
                    var m = rc.getAccessor();
                    m.setAccessible(true);
                    return m.invoke(o);
                }
            }
        }
        return fieldIn(o, name);
    }

    public static Object fieldIn(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    private static float f(Object o, String name) throws ReflectiveOperationException {
        return ((Number) get(o, name)).floatValue();
    }

    private static double d(Object o, String name) throws ReflectiveOperationException {
        return ((Number) get(o, name)).doubleValue();
    }

    private static int i(Object o, String name) throws ReflectiveOperationException {
        return ((Number) get(o, name)).intValue();
    }

    /** Fuer Tests: der Sampler hinter einer Aufgabe (Referenzvergleich je Knoten). */
    public Object samplerOf(int taskId) {
        for (var e : memo.entrySet()) if (e.getValue() == taskId) return e.getKey().sampler();
        return null;
    }
}
