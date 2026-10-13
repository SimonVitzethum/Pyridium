package simon.pyridium.worldgen.structures;

import com.google.gson.JsonObject;
import java.util.function.ToIntFunction;

/**
 * Frueher: fest einprogrammierte Strukturtypen. Alle Vanilla-Typen sind jetzt Jigsaw-Daten (siehe {@code PackExporter},
 * {@code tools/pack/gen_structures.py}, docs/STRUCTURES.md); es gibt keinen Erzeuger ausser dem generischen Jigsaw-Loeser mehr.
 * Strukturen mit unbekanntem Typ werden vom Compiler geloggt und uebersprungen.
 */
final class ProceduralTypes {
    private ProceduralTypes() {
    }

    static int typeOf(String type) {
        return StructureTables.STT_UNSUPPORTED;
    }

    static boolean implemented(int stt) {
        return false;
    }

    static void params(StructureTables t, TemplateBaker baker, ToIntFunction<String> tpl, String type, JsonObject o, int[] row) {
        row[StructureTables.ST_P3] = t.procData.size();
    }
}
