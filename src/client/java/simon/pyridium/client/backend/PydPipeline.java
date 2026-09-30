package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Pipeline, eingeordnet für den 2D-Compositor. Nur GUI-Pipelines
 * (Farbe, Textur, Text) werden gezeichnet; alles Dreidimensionale übernimmt
 * Pyrit, der Rest wird ausgelassen.
 */
public class PydPipeline implements BackendRenderPipeline {
	public enum Kind { COLOR, TEX, TEXT, TEXT_GRAY, ITEM, SPRITE_BLIT, SPRITE_INTERP, SKIP }

	public final BackendRenderPipeline.CreateInfo info;
	public final Kind kind;
	public final String name;
	public final int stride;
	public final int posOffset, colorOffset, uvOffset, normalOffset;
	public final boolean cull;
	public final GpuFormat posFormat, colorFormat, uvFormat;
	public final int blend;
	public final int projIndex, dynIndex, samplerIndex, animIndex, sprite2Index, lightIndex;
	public final PrimitiveTopology topology;
	private boolean closed;

	public PydPipeline(BackendRenderPipeline.CreateInfo info) {
		this.info = info;
		this.name = info.name();
		this.topology = info.primitiveTopology();
		String vs = "", fs = "";
		SpvModule vsModule = null;
		for (BackendRenderPipeline.CreateInfo.Shader s : info.shaders()) {
			if (s.module().type() == ShaderType.VERTEX) {
				vs = s.name();
				vsModule = s.module();
			} else {
				fs = s.name();
			}
		}
		// Attributnamen aus der Reflexion des Vertex-Shaders
		Map<Integer, String> names = new HashMap<>();
		if (vsModule != null) {
			try {
				for (SpvModule.Reflection.InterfaceVariable v : vsModule.reflect().inputs()) {
					names.put(v.location(), v.name());
				}
			} catch (Exception e) {
				// ohne Reflexion keine Sprites
			}
		}
		int st = 0, po = -1, co = -1, uo = -1, no = -1;
		GpuFormat pf = null, cf = null, uf = null;
		for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
			if (vb.bufferSlot() == 0) {
				st = vb.stride();
			}
		}
		for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
			if (a.bufferSlot() != 0) {
				continue;
			}
			String n = names.getOrDefault(a.location(), "");
			switch (n) {
				case "Position" -> {
					po = a.offset();
					pf = a.format();
				}
				case "Color" -> {
					co = a.offset();
					cf = a.format();
				}
				case "UV0" -> {
					uo = a.offset();
					uf = a.format();
				}
				case "Normal" -> no = a.offset();
				default -> {
				}
			}
		}
		this.stride = st;
		this.posOffset = po;
		this.colorOffset = co;
		this.uvOffset = uo;
		this.normalOffset = no;
		this.cull = info.cull();
		this.posFormat = pf;
		this.colorFormat = cf;
		this.uvFormat = uf;

		int pi = -1, di = -1, si = -1, ai = -1, s2 = -1, li = -1;
		List<BindGroupLayout.UniformDescription> u = info.uniforms();
		for (int i = 0; i < u.size(); i++) {
			switch (u.get(i).name()) {
				case "Projection" -> pi = i;
				case "DynamicTransforms" -> di = i;
				case "Sampler0", "Sprite", "CurrentSprite" -> si = i;
				case "NextSprite" -> s2 = i;
				case "SpriteAnimationInfo" -> ai = i;
				case "Lighting" -> li = i;
				default -> {
				}
			}
		}
		this.projIndex = pi;
		this.dynIndex = di;
		this.samplerIndex = si;
		this.animIndex = ai;
		this.sprite2Index = s2;
		this.lightIndex = li;

		ColorTargetState cts = info.colorTargetStates().isEmpty() ? null : info.colorTargetStates().get(0);
		this.blend = packBlend(cts);

		boolean quads = this.topology == PrimitiveTopology.QUADS;
		boolean posOk = po >= 0 && pf == GpuFormat.RGB32_FLOAT;
		Kind k = Kind.SKIP;
		if (quads && posOk && pi >= 0) {
			String f = fs.replace("minecraft:", "");
			String pn = this.name.replace("minecraft:", "");
			if ((f.equals("core/gui") || f.equals("core/position_color")) && co >= 0 && uo < 0) {
				k = Kind.COLOR;
			} else if ((f.equals("core/position_tex_color") || f.equals("core/position_tex")) && uo >= 0 && si >= 0) {
				k = Kind.TEX;
			} else if (f.equals("core/item") && uo >= 0 && si >= 0 && co >= 0) {
				// GUI-Items (Atlas der Gegenstaende): Modell-Vierecke als Sprites, flaechig beleuchtet
				k = Kind.ITEM;
			} else if (f.equals("core/text") && uo >= 0 && si >= 0 && pn.startsWith("pipeline/gui_text")) {
				k = pn.contains("grayscale") ? Kind.TEXT_GRAY : Kind.TEXT;
			}
		}
		if (vs.replace("minecraft:", "").equals("core/animate_sprite") && ai >= 0 && si >= 0) {
			k = s2 >= 0 ? Kind.SPRITE_INTERP : Kind.SPRITE_BLIT;
		}
		this.kind = k;
	}

	private static int factor(com.mojang.renderpearl.api.pipeline.BlendFactor f) {
		return f.ordinal();
	}

	static int packBlend(ColorTargetState cts) {
		int wm = cts == null ? 15 : cts.writeMask();
		int b = (wm & 15) << 24;
		if (cts != null && cts.blendFunction().isPresent()) {
			BlendFunction f = cts.blendFunction().get();
			BlendEquation c = f.color(), a = f.alpha();
			b |= factor(c.sourceFactor()) | factor(c.destFactor()) << 4 | c.op().ordinal() << 8;
			b |= factor(a.sourceFactor()) << 11 | factor(a.destFactor()) << 15 | a.op().ordinal() << 19;
			b |= 1 << 22;
		}
		return b;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
