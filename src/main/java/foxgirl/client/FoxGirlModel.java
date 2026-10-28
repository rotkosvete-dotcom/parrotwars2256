package foxgirl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import foxgirl.FoxGirlEntity;
import foxgirl.FoxGirlMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рисует модель в формате BBS (foxgirl.json) напрямую, без конвертации.
 * Координаты BBS (Y вверх, ноги на 0) переводятся в пространство ванильных моделей
 * (x -> -x, y -> 24 - y), поэтому анимации написаны как у обычных мобов.
 */
public class FoxGirlModel extends EntityModel<FoxGirlEntity> {
	private static final float DEG = (float) Math.PI / 180f;

	private static final class Quad {
		final float[] p = new float[12]; // 4 вершины * xyz (в пикселях)
		final float[] uv = new float[8];  // 4 вершины * uv (0..1)
		final float[] n = new float[3];
	}

	private static final class Part {
		final float px, py, pz;
		float rx, ry, rz;
		final List<Quad> quads = new ArrayList<>();
		final List<Part> children = new ArrayList<>();
		Part(float px, float py, float pz) { this.px = px; this.py = py; this.pz = pz; }

		void render(PoseStack m, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
			m.pushPose();
			m.translate(px / 16f, py / 16f, pz / 16f);
			if (rx != 0 || ry != 0 || rz != 0) m.mulPose(new Quaternionf().rotationZYX(rz, ry, rx));
			m.translate(-px / 16f, -py / 16f, -pz / 16f);

			PoseStack.Pose e = m.last();
			Matrix4f pm = e.pose();
			Matrix3f nm = e.normal();
			for (Quad q : quads) {
				for (int i = 0; i < 4; i++) {
					vc.vertex(pm, q.p[i * 3] / 16f, q.p[i * 3 + 1] / 16f, q.p[i * 3 + 2] / 16f)
						.color(r, g, b, a)
						.uv(q.uv[i * 2], q.uv[i * 2 + 1])
						.overlayCoords(overlay)
						.uv2(light)
						.normal(nm, q.n[0], q.n[1], q.n[2])
						.endVertex();
				}
			}
			for (Part c : children) c.render(m, vc, light, overlay, r, g, b, a);
			m.popPose();
		}
	}

	private final Part root;
	private final Map<String, Part> parts = new HashMap<>();
	private boolean sitting;

	public FoxGirlModel(ResourceManager rm) {
		super();
		Part loadedRoot = null;
		try (BufferedReader reader = rm.getResource(new ResourceLocation(FoxGirlMod.MOD_ID, "bbs/foxgirl.json")).get().openAsReader()) {
			JsonObject model = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("model");
			JsonArray texSize = model.getAsJsonArray("texture");
			float tw = texSize.get(0).getAsFloat(), th = texSize.get(1).getAsFloat();
			JsonObject groups = model.getAsJsonObject("groups");

			Map<String, String> parentOf = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : groups.entrySet()) {
				JsonObject g = e.getValue().getAsJsonObject();
				double[] o = arr(g.getAsJsonArray("origin"));
				Part part = new Part((float) -o[0], (float) (24 - o[1]), (float) o[2]);

				if (g.has("cubes")) for (JsonElement c : g.getAsJsonArray("cubes")) addCube(part, c.getAsJsonObject(), tw, th);
				if (g.has("meshes")) for (JsonElement m : g.getAsJsonArray("meshes")) addMesh(part, m.getAsJsonObject(), tw, th);

				parts.put(e.getKey(), part);
				parentOf.put(e.getKey(), g.has("parent") && !g.get("parent").getAsString().isEmpty() ? g.get("parent").getAsString() : null);
			}
			for (Map.Entry<String, String> e : parentOf.entrySet()) {
				if (e.getValue() == null) loadedRoot = parts.get(e.getKey());
				else parts.get(e.getValue()).children.add(parts.get(e.getKey()));
			}
		} catch (Exception ex) {
			throw new RuntimeException("Не удалось загрузить модель foxgirl.json", ex);
		}
		this.root = loadedRoot;
	}

	// ---------- загрузка геометрии ----------

	private static double[] arr(JsonArray a) {
		double[] r = new double[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
		return r;
	}

	/** Поворот точки вокруг o (порядок Z, Y, X, градусы) в пространстве BBS. */
	private static double[] rot(double[] p, double[] o, double[] r) {
		double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
		double rx = Math.toRadians(r[0]), ry = Math.toRadians(r[1]), rz = Math.toRadians(r[2]);
		double nx = x * Math.cos(rz) - y * Math.sin(rz), ny = x * Math.sin(rz) + y * Math.cos(rz);
		x = nx; y = ny;
		nx = x * Math.cos(ry) + z * Math.sin(ry); double nz = -x * Math.sin(ry) + z * Math.cos(ry);
		x = nx; z = nz;
		ny = y * Math.cos(rx) - z * Math.sin(rx); nz = y * Math.sin(rx) + z * Math.cos(rx);
		y = ny; z = nz;
		return new double[]{x + o[0], y + o[1], z + o[2]};
	}

	private static void put(Quad q, int i, double[] bb) {
		q.p[i * 3] = (float) -bb[0];
		q.p[i * 3 + 1] = (float) (24 - bb[1]);
		q.p[i * 3 + 2] = (float) bb[2];
	}

	private static void addCube(Part part, JsonObject c, float tw, float th) {
		double[] o = arr(c.getAsJsonArray("origin"));
		double[] f = arr(c.getAsJsonArray("from"));
		double[] s = arr(c.getAsJsonArray("size"));
		double inf = c.has("offset") ? c.get("offset").getAsDouble() : 0;
		double[] r = c.has("rotate") ? arr(c.getAsJsonArray("rotate")) : new double[]{0, 0, 0};
		JsonObject uvs = c.getAsJsonObject("uvs");

		double x0 = f[0] - inf, y0 = f[1] - inf, z0 = f[2] - inf;
		double x1 = f[0] + s[0] + inf, y1 = f[1] + s[1] + inf, z1 = f[2] + s[2] + inf;

		// имя грани, TL, TR, BL (как видно снаружи), нормаль — в пространстве BBS
		Object[][] faces = {
			{"front",  new double[]{x1, y1, z0}, new double[]{x0, y1, z0}, new double[]{x1, y0, z0}, new double[]{0, 0, -1}},
			{"back",   new double[]{x0, y1, z1}, new double[]{x1, y1, z1}, new double[]{x0, y0, z1}, new double[]{0, 0, 1}},
			{"right",  new double[]{x1, y1, z1}, new double[]{x1, y1, z0}, new double[]{x1, y0, z1}, new double[]{1, 0, 0}},
			{"left",   new double[]{x0, y1, z0}, new double[]{x0, y1, z1}, new double[]{x0, y0, z0}, new double[]{-1, 0, 0}},
			{"top",    new double[]{x0, y1, z0}, new double[]{x1, y1, z0}, new double[]{x0, y1, z1}, new double[]{0, 1, 0}},
			{"bottom", new double[]{x1, y0, z0}, new double[]{x0, y0, z0}, new double[]{x1, y0, z1}, new double[]{0, -1, 0}},
		};
		for (Object[] face : faces) {
			String name = (String) face[0];
			if (!uvs.has(name)) continue;
			double[] tl = (double[]) face[1], tr = (double[]) face[2], bl = (double[]) face[3], n = (double[]) face[4];
			double[] br = {tr[0] + bl[0] - tl[0], tr[1] + bl[1] - tl[1], tr[2] + bl[2] - tl[2]};
			double[][] pts = {tl, tr, br, bl};
			double[] uv = arr(uvs.getAsJsonArray(name));

			Quad q = new Quad();
			for (int i = 0; i < 4; i++) put(q, i, rot(pts[i], o, r));
			float[][] corner = {{(float) uv[0], (float) uv[1]}, {(float) uv[2], (float) uv[1]}, {(float) uv[2], (float) uv[3]}, {(float) uv[0], (float) uv[3]}};
			for (int i = 0; i < 4; i++) { q.uv[i * 2] = corner[i][0] / tw; q.uv[i * 2 + 1] = corner[i][1] / th; }
			double[] rn = rot(n, new double[]{0, 0, 0}, r);
			q.n[0] = (float) -rn[0]; q.n[1] = (float) -rn[1]; q.n[2] = (float) rn[2];
			part.quads.add(q);
		}
	}

	private static void addMesh(Part part, JsonObject m, float tw, float th) {
		double[] o = arr(m.getAsJsonArray("origin"));
		double[] r = m.has("rotate") ? arr(m.getAsJsonArray("rotate")) : new double[]{0, 0, 0};
		double[] v = arr(m.getAsJsonArray("vertices"));
		double[] uv = arr(m.getAsJsonArray("uvs"));
		for (int t = 0; t + 2 < v.length / 3; t += 3) {
			Quad q = new Quad();
			double[][] bb = new double[3][];
			for (int k = 0; k < 3; k++) {
				bb[k] = rot(new double[]{o[0] + v[(t + k) * 3], o[1] + v[(t + k) * 3 + 1], o[2] + v[(t + k) * 3 + 2]}, o, r);
				put(q, k, bb[k]);
				q.uv[k * 2] = (float) uv[(t + k) * 2] / tw;
				q.uv[k * 2 + 1] = (float) uv[(t + k) * 2 + 1] / th;
			}
			// треугольник -> квад с повтором последней вершины
			System.arraycopy(q.p, 6, q.p, 9, 3);
			System.arraycopy(q.uv, 4, q.uv, 6, 2);
			// нормаль: векторное произведение, лицом к -Z (вперёд)
			double ax = bb[1][0] - bb[0][0], ay = bb[1][1] - bb[0][1], az = bb[1][2] - bb[0][2];
			double bx = bb[2][0] - bb[0][0], by = bb[2][1] - bb[0][1], bz = bb[2][2] - bb[0][2];
			double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1e-9) { nx /= len; ny /= len; nz /= len; } else { nx = 0; ny = 0; nz = -1; }
			if (nz > 0) { nx = -nx; ny = -ny; nz = -nz; }
			q.n[0] = (float) -nx; q.n[1] = (float) -ny; q.n[2] = (float) nz;
			part.quads.add(q);
		}
	}

	// ---------- анимация ----------

	private Part p(String name) { return parts.get(name); }

	private static void set(Part part, float x, float y, float z) {
		if (part != null) { part.rx = x; part.ry = y; part.rz = z; }
	}

	@Override
	public void setupAnim(FoxGirlEntity e, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
		for (Part part : parts.values()) { part.rx = 0; part.ry = 0; part.rz = 0; }
		sitting = e.isInSittingPose();

		set(p("Head"), headPitch * DEG, netHeadYaw * DEG, 0);

		float swing = limbSwing * 0.6662f;
		float armAmount = sitting ? 0 : limbSwingAmount;
		set(p("LeftArms"), (float) Math.cos(swing) * armAmount, 0, 0.03f * (float) Math.sin(ageInTicks * 0.067f));
		set(p("RightArms"), (float) Math.cos(swing + Math.PI) * armAmount, 0, -0.03f * (float) Math.sin(ageInTicks * 0.067f));

		if (sitting) {
			set(p("LeftLeg"), -1.4f, 0.3f, 0);
			set(p("RightLeg"), -1.4f, -0.3f, 0);
		} else {
			set(p("LeftLeg"), (float) Math.cos(swing + Math.PI) * 1.4f * limbSwingAmount, 0, 0);
			set(p("RightLeg"), (float) Math.cos(swing) * 1.4f * limbSwingAmount, 0, 0);
		}

		// хвост: виляет, а при ударе — мощный размах
		int swing2 = e.getTailSwing();
		if (swing2 > 0) {
			float prog = 1f - swing2 / 14f;
			set(p("Tail"), -0.5f, (prog * 2f - 1f) * 1.5f, 0);
		} else {
			set(p("Tail"), -0.15f + limbSwingAmount * 0.3f, (float) Math.sin(ageInTicks * 0.12f) * 0.3f, 0);
		}
		// ушки чуть подёргиваются
		float twitch = (float) Math.max(0, Math.sin(ageInTicks * 0.05f)) * 0.08f;
		set(p("LeftEar"), 0, 0, twitch);
		set(p("RightEar"), 0, 0, -twitch);
	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
		poseStack.pushPose();
		if (sitting) poseStack.translate(0, 10 / 16f, 0); // в этом пространстве +Y = вниз
		root.render(poseStack, vc, light, overlay, r, g, b, a);
		poseStack.popPose();
	}
}
