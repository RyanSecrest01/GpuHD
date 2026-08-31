package net.runelite.client.plugins.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal loader for the single packaged grass GLB asset. */
final class GlbGrassMesh
{
	static final int FLOATS_PER_VERTEX = 8;
	final float[] vertices;
	final int[] indices;
	final float[] rawMin;
	final float[] rawMax;
	final float[] transformedMin;
	final float[] transformedMax;
	final float[] normalizedMin;
	final float[] normalizedMax;

	private GlbGrassMesh(float[] vertices, int[] indices,
		float[] rawMin, float[] rawMax, float[] transformedMin, float[] transformedMax,
		float[] normalizedMin, float[] normalizedMax)
	{
		this.vertices = vertices;
		this.indices = indices;
		this.rawMin = rawMin;
		this.rawMax = rawMax;
		this.transformedMin = transformedMin;
		this.transformedMax = transformedMax;
		this.normalizedMin = normalizedMin;
		this.normalizedMax = normalizedMax;
	}

	static GlbGrassMesh load(String resource) throws IOException
	{
		try (InputStream input = GlbGrassMesh.class.getResourceAsStream(resource))
		{
			if (input == null)
			{
				throw new IOException("Missing GLB grass asset: " + resource);
			}
			byte[] bytes = input.readAllBytes();
			ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			if (file.getInt() != 0x46546c67 || file.getInt() != 2)
			{
				throw new IOException("Unsupported GLB header");
			}
			file.getInt();
			String json = null;
			ByteBuffer binary = null;
			while (file.remaining() >= 8)
			{
				int length = file.getInt();
				int type = file.getInt();
				byte[] chunk = new byte[length];
				file.get(chunk);
				if (type == 0x4e4f534a)
				{
					json = new String(chunk, StandardCharsets.UTF_8).trim();
				}
				else if (type == 0x004e4942)
				{
					binary = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
				}
			}
			if (json == null || binary == null)
			{
				throw new IOException("GLB has no JSON/BIN chunks");
			}
			return decode(new JsonParser().parse(json).getAsJsonObject(), binary);
		}
	}

	private static GlbGrassMesh decode(JsonObject root, ByteBuffer binary) throws IOException
	{
		List<float[]> positions = new ArrayList<>();
		List<float[]> normals = new ArrayList<>();
		List<float[]> uvs = new ArrayList<>();
		List<Integer> outputIndices = new ArrayList<>();
		float[] rawMin = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
		float[] rawMax = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		float[] transformedMin = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
		float[] transformedMax = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		JsonArray accessors = root.getAsJsonArray("accessors");
		JsonArray views = root.getAsJsonArray("bufferViews");
		JsonArray meshes = root.getAsJsonArray("meshes");
		List<float[]> transforms = new ArrayList<>();
		for (int i = 0; i < meshes.size(); i++) transforms.add(identity());
		if (root.has("nodes") && root.has("scenes"))
		{
			JsonArray nodes = root.getAsJsonArray("nodes");
			JsonObject scene = root.getAsJsonArray("scenes")
				.get(root.has("scene") ? root.get("scene").getAsInt() : 0).getAsJsonObject();
			if (scene.has("nodes"))
			{
				for (var node : scene.getAsJsonArray("nodes"))
				{
					collectNode(nodes, node.getAsInt(), identity(), transforms);
				}
			}
		}
		for (int meshIndex = 0; meshIndex < meshes.size(); meshIndex++)
		{
			var mesh = meshes.get(meshIndex);
			for (var primitiveElement : mesh.getAsJsonObject().getAsJsonArray("primitives"))
			{
				JsonObject primitive = primitiveElement.getAsJsonObject();
				if (primitive.has("mode") && primitive.get("mode").getAsInt() != 4)
				{
					continue;
				}
				JsonObject attributes = primitive.getAsJsonObject("attributes");
				int positionAccessor = attributes.get("POSITION").getAsInt();
				float[][] p = readVectors(accessors, views, binary, positionAccessor, 3);
				float[][] n = attributes.has("NORMAL")
					? readVectors(accessors, views, binary, attributes.get("NORMAL").getAsInt(), 3)
					: null;
				float[][] uv = attributes.has("TEXCOORD_0")
					? readVectors(accessors, views, binary, attributes.get("TEXCOORD_0").getAsInt(), 2)
					: null;
				int base = positions.size();
				float[] transform = transforms.get(meshIndex);
				for (int i = 0; i < p.length; i++)
				{
					for (int c = 0; c < 3; c++)
					{
						rawMin[c] = Math.min(rawMin[c], p[i][c]);
						rawMax[c] = Math.max(rawMax[c], p[i][c]);
					}
					float[] transformed = transformPoint(transform, p[i]);
					positions.add(transformed);
					for (int c = 0; c < 3; c++)
					{
						transformedMin[c] = Math.min(transformedMin[c], transformed[c]);
						transformedMax[c] = Math.max(transformedMax[c], transformed[c]);
					}
					normals.add(n == null ? new float[]{0, 1, 0}
						: transformDirection(transform, n[i]));
					uvs.add(uv == null ? new float[]{0, 0} : uv[i]);
				}
				if (primitive.has("indices"))
				{
					for (int index : readIndices(accessors, views, binary,
						primitive.get("indices").getAsInt()))
					{
						outputIndices.add(base + index);
					}
				}
				else
				{
					for (int i = 0; i < p.length; i++)
					{
						outputIndices.add(base + i);
					}
				}
			}
		}
		if (positions.isEmpty() || outputIndices.isEmpty())
		{
			throw new IOException("GLB contains no triangle geometry");
		}
		float minY = Float.POSITIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float minX = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (float[] p : positions)
		{
			minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
			minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
		}
		float height = Math.max(maxY - minY, 0.001f);
		float scale = 64.0f / height;
		float centerX = (minX + maxX) * 0.5f;
		float centerZ = (minZ + maxZ) * 0.5f;
		// This is the single glTF Y-up -> RuneLite Y-down conversion. The
		// normalized local mesh is rooted at Y=0 and grows toward negative Y, so
		// render shaders only scale/yaw it and add the exact terrain anchor.
		float[] normalizedMin = {(minX - centerX) * scale, -height * scale,
			(minZ - centerZ) * scale};
		float[] normalizedMax = {(maxX - centerX) * scale, 0.0f,
			(maxZ - centerZ) * scale};
		float[] packed = new float[positions.size() * FLOATS_PER_VERTEX];
		for (int i = 0; i < positions.size(); i++)
		{
			float[] p = positions.get(i);
			float[] n = normals.get(i);
			float[] uv = uvs.get(i);
			int at = i * FLOATS_PER_VERTEX;
			packed[at] = (p[0] - centerX) * scale;
			packed[at + 1] = -(p[1] - minY) * scale;
			packed[at + 2] = (p[2] - centerZ) * scale;
			packed[at + 3] = n[0];
			packed[at + 4] = -n[1];
			packed[at + 5] = n[2];
			packed[at + 6] = uv[0];
			packed[at + 7] = uv[1];
		}
		return new GlbGrassMesh(packed, outputIndices.stream().mapToInt(Integer::intValue).toArray(),
			rawMin, rawMax, transformedMin, transformedMax,
			normalizedMin, normalizedMax);
	}

	private static float[] identity()
	{
		return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	private static void collectNode(JsonArray nodes, int index, float[] parent,
		List<float[]> transforms)
	{
		JsonObject node = nodes.get(index).getAsJsonObject();
		float[] local = node.has("matrix") ? matrix(node.getAsJsonArray("matrix")) : trs(node);
		float[] world = multiply(parent, local);
		if (node.has("mesh")) transforms.set(node.get("mesh").getAsInt(), world);
		if (node.has("children"))
		{
			for (var child : node.getAsJsonArray("children")) collectNode(nodes,
				child.getAsInt(), world, transforms);
		}
	}

	private static float[] matrix(JsonArray a)
	{
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) m[i] = a.get(i).getAsFloat();
		return m;
	}

	private static float[] trs(JsonObject node)
	{
		float[] s = node.has("scale") ? array(node.getAsJsonArray("scale"), 3, 1) : new float[]{1, 1, 1};
		float[] t = node.has("translation") ? array(node.getAsJsonArray("translation"), 3, 0) : new float[]{0, 0, 0};
		float[] q = node.has("rotation") ? array(node.getAsJsonArray("rotation"), 4, 0) : new float[]{0, 0, 0, 1};
		float x=q[0], y=q[1], z=q[2], w=q[3];
		return new float[]{(1-2*y*y-2*z*z)*s[0], (2*x*y+2*w*z)*s[0], (2*x*z-2*w*y)*s[0], 0,
			(2*x*y-2*w*z)*s[1], (1-2*x*x-2*z*z)*s[1], (2*y*z+2*w*x)*s[1], 0,
			(2*x*z+2*w*y)*s[2], (2*y*z-2*w*x)*s[2], (1-2*x*x-2*y*y)*s[2], 0,
			t[0], t[1], t[2], 1};
	}

	private static float[] array(JsonArray a, int count, float fallback)
	{
		float[] r = new float[count];
		for (int i = 0; i < count; i++) r[i] = i < a.size() ? a.get(i).getAsFloat() : fallback;
		return r;
	}

	private static float[] multiply(float[] a, float[] b)
	{
		float[] r = new float[16];
		for (int c=0;c<4;c++) for (int row=0;row<4;row++)
			for (int k=0;k<4;k++) r[c*4+row] += a[k*4+row] * b[c*4+k];
		return r;
	}

	private static float[] transformPoint(float[] m, float[] p)
	{
		return new float[]{m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12],
			m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13],
			m[2]*p[0]+m[6]*p[1]+m[10]*p[2]+m[14]};
	}

	private static float[] transformDirection(float[] m, float[] p)
	{
		float[] r = {m[0]*p[0]+m[4]*p[1]+m[8]*p[2],
			m[1]*p[0]+m[5]*p[1]+m[9]*p[2], m[2]*p[0]+m[6]*p[1]+m[10]*p[2]};
		float length = (float)Math.sqrt(r[0]*r[0]+r[1]*r[1]+r[2]*r[2]);
		return length > 0.0001f ? new float[]{r[0]/length,r[1]/length,r[2]/length} : new float[]{0,1,0};
	}

	private static float[][] readVectors(JsonArray accessors, JsonArray views,
		ByteBuffer binary, int accessorIndex, int components) throws IOException
	{
		JsonObject accessor = accessors.get(accessorIndex).getAsJsonObject();
		int count = accessor.get("count").getAsInt();
		int viewIndex = accessor.get("bufferView").getAsInt();
		JsonObject view = views.get(viewIndex).getAsJsonObject();
		int componentType = accessor.get("componentType").getAsInt();
		int componentBytes = componentType == 5126 ? 4 : componentType == 5123 ? 2 : 1;
		int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : components * componentBytes;
		int offset = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
			+ (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
		float[][] result = new float[count][components];
		for (int i = 0; i < count; i++)
		{
			int at = offset + i * stride;
			for (int c = 0; c < components; c++)
			{
				result[i][c] = componentType == 5126 ? binary.getFloat(at + c * 4)
					: componentType == 5123 ? binary.getShort(at + c * 2) & 0xffff
					: binary.get(at + c) & 0xff;
			}
		}
		return result;
	}

	private static int[] readIndices(JsonArray accessors, JsonArray views,
		ByteBuffer binary, int accessorIndex) throws IOException
	{
		JsonObject accessor = accessors.get(accessorIndex).getAsJsonObject();
		int count = accessor.get("count").getAsInt();
		int viewIndex = accessor.get("bufferView").getAsInt();
		JsonObject view = views.get(viewIndex).getAsJsonObject();
		int type = accessor.get("componentType").getAsInt();
		int bytes = type == 5125 ? 4 : type == 5123 ? 2 : 1;
		int offset = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
			+ (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
		int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : bytes;
		int[] result = new int[count];
		for (int i = 0; i < count; i++)
		{
			int at = offset + i * stride;
			result[i] = type == 5125 ? binary.getInt(at)
				: type == 5123 ? binary.getShort(at) & 0xffff : binary.get(at) & 0xff;
		}
		return result;
	}
}
