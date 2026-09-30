package simon.pyridium.worldgen.exact;

import java.io.IOException;
import java.util.List;

/**
 * Rechen-Backend der exakten Worldgen: in Pyridium nur CUDA (CudaCompute, CUDA C++/PTX); die
 * Schnittstelle bleibt fuer Tests und moegliche weitere CUDA-Varianten. Puffer sind {Handle, Host-Adresse (0 = nur Geraet), Groesse}; Kernel entsprechen
 * den Shader-/Kernel-Dateien (Bindungen 0..n-1, 8 int Push-Konstanten ExactPush). Dispatches
 * laufen in Listenreihenfolge, jeder sieht die Schreibzugriffe der vorigen. Nicht threadsicher.
 */
public interface GpuCompute extends AutoCloseable {
    /** Liefert Binaerdaten zu einem Dateinamen (z. B. "wg_exact_task.ptx"). */
    @FunctionalInterface
    interface BinarySource {
        byte[] load(String name) throws IOException;
    }

    /** Ein Kernel/Pipeline mit Bindungen. */
    interface Kernel extends AutoCloseable {
        void bind(long[]... bufs);

        @Override
        void close();
    }

    /** Ein Dispatch: gx x gy Arbeitsgruppen (Gruppengroesse des Kernels). */
    record Dispatch(Kernel kernel, int[] push, int gx, int gy) {
    }

    String deviceName();

    /** Vom Host beschreibbarer Puffer (vor jedem Lauf fuer die GPU sichtbar), genullt. */
    long[] buffer(long size);

    /** Nur auf dem Geraet, genullt. */
    long[] deviceBuffer(long size);

    /** Ziel fuer Kopien vom Geraet, auf dem Host lesbar. */
    long[] readbackBuffer(long size);

    /** Unveraenderliche Tabelle. */
    long[] upload(int[] data);

    /** Kernel nach Name ohne Endung ("wg_exact_task"); block = Gruppengroesse (Threads). */
    Kernel kernel(BinarySource src, String name, int bindings, int block) throws Exception;

    /** Laufzeit-spezialisierte Aufgaben-Gruppe (Codegen) oder null, wenn nicht moeglich. */
    Kernel groupKernel(int[] tasks, int[] group, boolean point, int bindings) throws Exception;

    double run(List<Dispatch> list, List<long[]> copies);

    default double run(List<Dispatch> list) {
        return run(list, List.of());
    }

    /** Zeit je Dispatch (ms), Diagnose. */
    double[] runTimed(List<Dispatch> list);

    @Override
    void close();

    /** Dispatches der Aufgaben-Gruppen: spezialisierte Kernel, sonst Interpreter (taskPt fuer Punkt-Gruppen). */
    static void addGroups(List<Dispatch> list, ExactPlan plan, List<? extends Kernel> groupKernels, Kernel task, Kernel taskPt, int[] push,
                          int chunks) {
        for (int i = 0; i < plan.groups.size(); i++) {
            int[] g = plan.groups.get(i);
            int[] p = push.clone();
            p[0] = g[0];
            p[6] = g[1];
            Kernel k = groupKernels != null && groupKernels.get(i) != null ? groupKernels.get(i)
                    : plan.groupIsPoint(g) && taskPt != null ? taskPt : task;
            list.add(new Dispatch(k, p, (plan.groupSize(g) + 127) / 128, chunks));
        }
    }
}
