package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * Punkt-Semantik ({@code DensitySampler.sampleValue}) als Register-Programm fuer die GPU:
 * Aquifer-Abfragen, Oberflaechensuche, spaeter Materialregeln. Spiegelt die Punktpfade der
 * Vanilla-Sampler (26.3): Kurzschluesse von mul/div/min/max, Rauschkoordinaten
 * {@code (b*scale)*frequency} in double, Slices als Koordinaten-Ueberschreibung und
 * find_top_surface als Unterprogramm mit laufendem y.
 *
 * <p>Befehle: je {@link #WORDS} ints [op, dst, a, b, c, i0, i1, i2]. Register sind floats.
 * Mehrere Wurzeln (Funktionen) teilen Code und Register; {@link #entry} liefert je Wurzel den
 * Code-Bereich und das Ergebnisregister.
 */
public final class ExactPointProgram {
    /** Ein Punkt-Eintrag liest den Beardifier (dann ist der GPU-Pfad nur ohne Strukturen exakt). */
    public boolean usesBeardifier;

    public static final int WORDS = 8;
    // ---- Opcodes (identisch in wg_exact_point.cu) ----
    public static final int P_END = 0, P_CONST = 1, P_ADD = 2, P_SUB = 3, P_MUL = 4, P_DIV = 5, P_MIN = 6, P_MAX = 7,
            P_CADD = 8, P_CMUL = 9, P_CSUB = 10, P_CDIV = 11, P_CMIN = 12, P_CMAX = 13, P_ABS = 14, P_SQUARE = 15,
            P_CUBE = 16, P_LEAKY = 17, P_NEGATE = 18, P_RECIP = 19, P_SQRT = 20, P_SIGN = 21, P_SQUEEZE = 22,
            P_CLAMP = 23, P_RANGE = 24, P_RANGE_CONST = 25, P_THRESH1 = 26, P_INTERVAL = 27, P_LERP = 28,
            P_LERP_CF = 29, P_LERP_CS = 30, P_GRADIENT = 31, P_NOISE = 32, P_SHIFTED = 33, P_SHIFTB = 34,
            P_SPLINE = 35, P_SETC = 36, P_RESTC = 37, P_FINDTOP = 38, P_RET = 39;

    public final IntArrayList code = new IntArrayList();
    public final IntArrayList params = new IntArrayList();
    public final List<String> unsupported = new ArrayList<>();
    public int regCount;
    /** Rausch-Stapel: gemeinsam mit einem {@link ExactPlan} (gleiche Lagen-/Stapel-Kodierung). */
    final ExactPlan noiseHost;

    /** Code-Bereich [start, end) und Ergebnisregister je kompilierter Wurzel. */
    public record Entry(int start, int end, int result) {
    }

    private final Map<Key, Integer> memo = new HashMap<>();
    private int frame; // Kennung der aktuellen Koordinaten-Ueberschreibungen (Memo-Schluessel)
    private final List<int[]> frames = new ArrayList<>(); // Kennung -> {mask, x, y, z, parent}

    private record Key(Object sampler, int frame, int scope) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.sampler == sampler && k.frame == frame && k.scope == scope;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(sampler) * 31 + frame * 7 + scope;
        }
    }

    private int scope; // Unterprogramme (find_top_surface) haben eigenes y -> eigenes Memo

    public ExactPointProgram(ExactPlan noiseHost) {
        this.noiseHost = noiseHost;
        frames.add(new int[]{0, 0, 0, 0, -1});
    }

    /** Kompiliert eine Wurzel als eigenen Code-Bereich (endet mit P_END). */
    public Entry compile(DensitySampler root) {
        // Jede Wurzel ist ein eigener Einstieg: kein Register aus anderen Bereichen wiederverwenden.
        memo.clear();
        frame = 0;
        int start = code.size() / WORDS;
        int r = node(root);
        emit(P_END, -1, r, -1, -1, 0, 0, 0);
        return new Entry(start, code.size() / WORDS, r);
    }

    /** Code fuer die GPU: Stapel-Indizes (i0 von NOISE/SHIFTED/SHIFTB) werden zu Offsets. */
    public int[] encodeCode() {
        if (noiseHost.stackOffsets == null) noiseHost.encodeStacks();
        int[] a = code.toIntArray();
        for (int at = 0; at < a.length; at += WORDS) {
            int op = a[at];
            if (op == P_NOISE || op == P_SHIFTED || op == P_SHIFTB) a[at + 5] = noiseHost.stackOffsets[a[at + 5]];
        }
        return a;
    }

    private int reg() {
        return regCount++;
    }

    private int emit(int op, int dst, int a, int b, int c, int i0, int i1, int i2) {
        code.add(op);
        code.add(dst);
        code.add(a);
        code.add(b);
        code.add(c);
        code.add(i0);
        code.add(i1);
        code.add(i2);
        return dst;
    }

    private static int fb(float f) {
        return Float.floatToRawIntBits(f);
    }

    private int node(Object s) {
        // Unter einem Rahmen mit festem y haengt nichts vom laufenden y eines Unterprogramms ab:
        // gleicher Memo-Eintrag wie ausserhalb (find_top_surface rechnet 2D-Anteile nur einmal).
        Key k = new Key(s, frame, (frames.get(frame)[0] & 2) != 0 ? 0 : scope);
        Integer m = memo.get(k);
        if (m != null) return m;
        int r = build(s);
        memo.put(k, r);
        return r;
    }

    private int build(Object s) {
        String cls = s.getClass().getName();
        String name = cls.substring(cls.lastIndexOf('.') + 1);
        try {
            switch (name) {
                case "CachingDensitySampler":
                    // Vanilla-Punktabfrage liefert ggf. einen Volumen-Cachewert: fuer pfadinvariante
                    // Teilbaeume (Aquifer/Oberflaeche der Overworld) wertgleich – Tests pruefen es.
                    return node(ExactPlan.get(s, "input"));
                case "ContextBoundSampler":
                    // Punktpfad kennt keinen Beardifier: Chunks mit Strukturen rechnet dann Vanilla
                    if (ExactPlan.get(s, "key") == net.minecraft.world.level.levelgen.Beardifier.CONTEXT_KEY) usesBeardifier = true;
                    return node(ExactPlan.get(s, "fallbackSampler"));
                case "BlendDensityFunction$Sampler":
                    return node(ExactPlan.get(s, "input"));
                case "ConstantFunction$Sampler":
                    return emit(P_CONST, reg(), -1, -1, -1, fb(f(s, "value")), 0, 0);
                case "BinaryFunction$AddSampler":
                    return bin(P_ADD, s, 0);
                case "BinaryFunction$SubSampler":
                    return bin(P_SUB, s, 0);
                case "BinaryFunction$MulSampler":
                    return bin(P_MUL, s, 0);
                case "BinaryFunction$DivSampler":
                    return bin(P_DIV, s, 0);
                case "BinaryFunction$MinSampler":
                    return bin(P_MIN, s, fb(f(s, "rightMinValue")));
                case "BinaryFunction$MaxSampler":
                    return bin(P_MAX, s, fb(f(s, "rightMaxValue")));
                case "BinaryFunction$ConstAddSampler":
                    return un(P_CADD, ExactPlan.get(s, "left"), fb(f(s, "right")));
                case "BinaryFunction$ConstMulSampler":
                    return un(P_CMUL, ExactPlan.get(s, "left"), fb(f(s, "right")));
                case "BinaryFunction$ConstSubSampler":
                    return un(P_CSUB, ExactPlan.get(s, "right"), fb(f(s, "left")));
                case "BinaryFunction$ConstDivSampler":
                    return un(P_CDIV, ExactPlan.get(s, "right"), fb(f(s, "left")));
                case "BinaryFunction$ConstMinSampler":
                    return un(P_CMIN, ExactPlan.get(s, "left"), fb(f(s, "right")));
                case "BinaryFunction$ConstMaxSampler":
                    return un(P_CMAX, ExactPlan.get(s, "left"), fb(f(s, "right")));
                case "UnaryFunction$AbsSampler":
                    return un(P_ABS, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$SquareSampler":
                    return un(P_SQUARE, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$CubeSampler":
                    return un(P_CUBE, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$LeakyReLUSampler":
                    return un(P_LEAKY, ExactPlan.get(s, "input"), fb(f(s, "negativeFactor")));
                case "UnaryFunction$NegateSampler":
                    return un(P_NEGATE, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$ReciprocalSampler":
                    return un(P_RECIP, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$SqrtSampler":
                    return un(P_SQRT, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$SignSampler":
                    return un(P_SIGN, ExactPlan.get(s, "input"), 0);
                case "UnaryFunction$SqueezeSampler":
                    return un(P_SQUEEZE, ExactPlan.get(s, "input"), 0);
                case "ClampFunction$Sampler": {
                    int a = node(ExactPlan.get(s, "input"));
                    return emit(P_CLAMP, reg(), a, -1, -1, fb(f(s, "min")), fb(f(s, "max")), 0);
                }
                case "RangeChoiceFunction$Sampler": {
                    int in = node(ExactPlan.get(s, "input"));
                    int a = node(ExactPlan.get(s, "whenInRange"));
                    int b = node(ExactPlan.get(s, "whenOutOfRange"));
                    return emit(P_RANGE, reg(), in, a, b, fb(f(s, "minInclusive")), fb(f(s, "maxExclusive")), 0);
                }
                case "RangeChoiceFunction$ConstSampler": {
                    int in = node(ExactPlan.get(s, "input"));
                    int p = params.size();
                    params.add(fb(f(s, "whenInRange")));
                    params.add(fb(f(s, "whenOutOfRange")));
                    return emit(P_RANGE_CONST, reg(), in, -1, -1, fb(f(s, "minInclusive")), fb(f(s, "maxExclusive")), p);
                }
                case "IntervalSelectFunction$SingleThresholdSampler": {
                    int in = node(ExactPlan.get(s, "input"));
                    int a = node(ExactPlan.get(s, "ifBelow"));
                    int b = node(ExactPlan.get(s, "ifAbove"));
                    return emit(P_THRESH1, reg(), in, a, b, fb(f(s, "threshold")), 0, 0);
                }
                case "IntervalSelectFunction$Sampler": {
                    float[] th = (float[]) ExactPlan.get(s, "thresholds");
                    DensitySampler[] fns = (DensitySampler[]) ExactPlan.get(s, "samplers");
                    int in = node(ExactPlan.get(s, "input"));
                    int[] regs = new int[fns.length];
                    for (int i = 0; i < fns.length; i++) regs[i] = node(fns[i]);
                    int p = params.size();
                    for (float t : th) params.add(fb(t));
                    for (int r : regs) params.add(r);
                    return emit(P_INTERVAL, reg(), in, -1, -1, p, th.length, 0);
                }
                case "LerpFunction$Sampler": {
                    int al = node(ExactPlan.get(s, "alpha"));
                    int a = node(ExactPlan.get(s, "first"));
                    int b = node(ExactPlan.get(s, "second"));
                    return emit(P_LERP, reg(), al, a, b, 0, 0, 0);
                }
                case "LerpFunction$ConstFirstSampler": {
                    int al = node(ExactPlan.get(s, "alpha"));
                    int b = node(ExactPlan.get(s, "second"));
                    return emit(P_LERP_CF, reg(), al, b, -1, fb(f(s, "first")), 0, 0);
                }
                case "LerpFunction$ConstSecondSampler": {
                    int al = node(ExactPlan.get(s, "alpha"));
                    int a = node(ExactPlan.get(s, "first"));
                    return emit(P_LERP_CS, reg(), al, a, -1, fb(f(s, "second")), 0, 0);
                }
                case "GradientFunction$ClampedSampler":
                case "GradientFunction$RepeatSampler":
                case "GradientFunction$MirroredRepeatSampler": {
                    int ax = switch (ExactPlan.get(s, "axis").toString()) {
                        case "x" -> 0;
                        case "y" -> 1;
                        default -> 2;
                    };
                    int mode = name.endsWith("ClampedSampler") ? 0 : name.endsWith("$RepeatSampler") ? 1 : 2;
                    int p = params.size();
                    params.add(i(s, "fromCoordinate"));
                    if (mode == 0) {
                        params.add(i(s, "minCoordinate"));
                        params.add(i(s, "maxCoordinate"));
                    } else {
                        params.add(i(s, "coordinateRange"));
                        params.add(0);
                    }
                    params.add(fb(f(s, "fromValue")));
                    params.add(fb(f(s, "coordinateFactor")));
                    return emit(P_GRADIENT, reg(), -1, -1, -1, ax | (mode << 4), p, 0);
                }
                case "NoiseFunction$Sampler":
                    return noise(ExactPlan.get(s, "noise"), d(s, "xzScale"), d(s, "yScale"));
                case "NoiseFunction$ShiftedXzSampler":
                case "NoiseFunction$ShiftedXyzSampler": {
                    int sx = node(ExactPlan.get(s, "shiftX"));
                    int sz = node(ExactPlan.get(s, "shiftZ"));
                    int sy = name.endsWith("XyzSampler") ? node(ExactPlan.get(s, "shiftY")) : -1;
                    int stack = noiseHost.stackIdPublic(ExactPlan.get(s, "noise"));
                    int p = params.size();
                    putD(d(s, "xzScale"));
                    putD(d(s, "yScale"));
                    return emit(P_SHIFTED, reg(), sx, sz, sy, stack, p, 0);
                }
                case "ShiftNoiseFunction$ShiftB$1": {
                    int stack = noiseHost.stackIdPublic(ExactPlan.fieldIn(s, "val$noise"));
                    return emit(P_SHIFTB, reg(), -1, -1, -1, stack, 0, 0);
                }
                case "SliceFunction$YSampler":
                    return slice(ExactPlan.get(s, "input"), 2, 0, i(s, "y"), 0);
                case "SliceFunction$XSampler":
                    return slice(ExactPlan.get(s, "input"), 1, i(s, "x"), 0, 0);
                case "SliceFunction$ZSampler":
                    return slice(ExactPlan.get(s, "input"), 4, 0, 0, i(s, "z"));
                case "SliceFunction$XzSampler":
                    return slice(ExactPlan.get(s, "input"), 5, i(s, "x"), 0, i(s, "z"));
                case "SplineFunction$Sampler":
                    return spline(s);
                case "FindTopSurfaceFunction$Sampler":
                    return findTop(s);
                default:
                    if (!unsupported.contains(name)) unsupported.add(name);
                    return emit(P_CONST, reg(), -1, -1, -1, fb(Float.NaN), 0, 0);
            }
        } catch (ReflectiveOperationException e) {
            if (!unsupported.contains(name + " (reflection)")) unsupported.add(name + " (reflection)");
            return emit(P_CONST, reg(), -1, -1, -1, fb(Float.NaN), 0, 0);
        }
    }

    private int bin(int op, Object s, int imm) throws ReflectiveOperationException {
        int a = node(ExactPlan.get(s, "left"));
        int b = node(ExactPlan.get(s, "right"));
        return emit(op, reg(), a, b, -1, imm, 0, 0);
    }

    private int un(int op, Object input, int imm) {
        int a = node(input);
        return emit(op, reg(), a, -1, -1, imm, 0, 0);
    }

    private int noise(Object noise, double xzScale, double yScale) throws ReflectiveOperationException {
        int stack = noiseHost.stackIdPublic(noise);
        int p = params.size();
        putD(xzScale);
        putD(yScale);
        return emit(P_NOISE, reg(), -1, -1, -1, stack, p, 0);
    }

    private void putD(double v) {
        long b = Double.doubleToRawLongBits(v);
        params.add((int) b);
        params.add((int) (b >>> 32));
    }

    /** Slice: Koordinaten setzen, Kind auswerten, zuruecksetzen (eigener Memo-Rahmen). */
    private int slice(Object input, int mask, int x, int y, int z) {
        int[] parent = frames.get(frame);
        int nm = parent[0] | mask;
        int[] f = {nm, (mask & 1) != 0 ? x : parent[1], (mask & 2) != 0 ? y : parent[2], (mask & 4) != 0 ? z : parent[3], frame};
        int id = -1;
        for (int i = 0; i < frames.size(); i++) {
            int[] g = frames.get(i);
            if (g[0] == f[0] && g[1] == f[1] && g[2] == f[2] && g[3] == f[3]) id = i;
        }
        if (id < 0) {
            frames.add(f);
            id = frames.size() - 1;
        }
        int saved = frame;
        emit(P_SETC, -1, x, y, z, mask, 0, 0);
        frame = id;
        int r = node(input);
        frame = saved;
        emit(P_RESTC, -1, -1, -1, -1, mask, 0, 0);
        return r;
    }

    private int spline(Object s) throws ReflectiveOperationException {
        Object fn = ExactPlan.get(s, "sampler");
        List<Object> coords = new ArrayList<>();
        IntArrayList scode = new IntArrayList();
        int root = encodeSpline(fn, coords, scode);
        int[] regs = new int[coords.size()];
        for (int i = 0; i < regs.length; i++) regs[i] = node(coords.get(i));
        int p = params.size();
        for (int r : regs) params.add(r);
        int codeAt = params.size();
        params.addAll(scode);
        return emit(P_SPLINE, reg(), -1, -1, -1, p, codeAt, root);
    }

    static int encodeSpline(Object fn, List<Object> coords, IntArrayList code) throws ReflectiveOperationException {
        Object sp = fn;
        if (fn.getClass().isAnonymousClass()) sp = ExactPlan.fieldIn(fn, "val$multipoint");
        if (sp instanceof CubicSpline.Constant<?> c) {
            int at = code.size();
            code.add(0);
            code.add(Float.floatToRawIntBits(c.value()));
            return at;
        }
        if (!(sp instanceof CubicSpline.Multipoint<?> mp)) {
            float value = (float) fn.getClass().getMethod("apply", Object.class).invoke(fn, (Object) null);
            int at = code.size();
            code.add(0);
            code.add(Float.floatToRawIntBits(value));
            return at;
        }
        Object sampler = ExactPlan.get(mp.coordinate(), "sampler");
        int ci = -1;
        for (int i = 0; i < coords.size(); i++) if (coords.get(i) == sampler) ci = i;
        if (ci < 0) {
            coords.add(sampler);
            ci = coords.size() - 1;
        }
        int n = mp.locations().length;
        int[] kids = new int[n];
        for (int i = 0; i < n; i++) kids[i] = encodeSpline(mp.values().get(i), coords, code);
        int at = code.size();
        code.add(1);
        code.add(ci);
        code.add(n);
        for (float l : mp.locations()) code.add(Float.floatToRawIntBits(l));
        for (float d : mp.derivatives()) code.add(Float.floatToRawIntBits(d));
        for (int kid : kids) code.add(kid);
        return at;
    }

    /**
     * find_top_surface: Obergrenze auswerten, dann ab floor(upper / h) * h in Schritten h nach
     * unten die Dichte (Unterprogramm, y = Probe) pruefen. Unterprogramm-Code steht direkt
     * hinter P_FINDTOP und endet mit P_RET; der Interpreter springt dorthin zurueck.
     */
    private int findTop(Object s) throws ReflectiveOperationException {
        int upper = node(ExactPlan.get(s, "upperBound"));
        hoistYSlices(ExactPlan.get(s, "density"), new java.util.IdentityHashMap<>());
        int dst = reg();
        int at = code.size() / WORDS;
        emit(P_FINDTOP, dst, upper, -1, -1, i(s, "lowerBound"), i(s, "cellHeight"), 0);
        int savedScope = scope;
        scope = ++scopeCounter;
        int r = node(ExactPlan.get(s, "density"));
        scope = savedScope;
        emit(P_RET, -1, r, -1, -1, 0, 0, 0);
        int end = code.size() / WORDS;
        code.set(at * WORDS + 4, end); // c = Befehl hinter dem Unterprogramm
        return dst;
    }

    private int scopeCounter;

    /** y-feste Teilbaeume (YSlice) vor der Schleife im aeusseren Rahmen berechnen. */
    private void hoistYSlices(Object s, java.util.Map<Object, Boolean> seen) throws ReflectiveOperationException {
        if (s == null || seen.put(s, Boolean.TRUE) != null) return;
        String cls = s.getClass().getName();
        if (cls.endsWith("SliceFunction$YSampler") || cls.endsWith("SliceFunction$XzSampler") && false) {
            node(s);
            return;
        }
        for (Class<?> c = s.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(s);
                if (v instanceof DensitySampler ds) hoistYSlices(ds, seen);
                else if (v instanceof DensitySampler[] arr) for (DensitySampler ds : arr) hoistYSlices(ds, seen);
            }
        }
    }

    private static float f(Object o, String n) throws ReflectiveOperationException {
        return ((Number) ExactPlan.get(o, n)).floatValue();
    }

    private static double d(Object o, String n) throws ReflectiveOperationException {
        return ((Number) ExactPlan.get(o, n)).doubleValue();
    }

    private static int i(Object o, String n) throws ReflectiveOperationException {
        return ((Number) ExactPlan.get(o, n)).intValue();
    }
}
