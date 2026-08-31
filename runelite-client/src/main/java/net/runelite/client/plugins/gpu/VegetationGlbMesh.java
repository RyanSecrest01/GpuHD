/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Shared asset-driven vegetation representation. Geometry deliberately comes
 * from the proven grass GLB importer; this layer adds primitive/material and
 * RGBA texture ownership needed by trees without changing grass placement.
 */
final class VegetationGlbMesh
{
	static final int FLOATS_PER_VERTEX = GlbGrassMesh.FLOATS_PER_VERTEX;

	final float[] vertices;
	final int[] indices;
	final Primitive[] primitives;
	final Material[] materials;
	final float[] normalizedMin;
	final float[] normalizedMax;
	final int sourceNodeCount;
	final int sourcePrimitiveCount;

	private VegetationGlbMesh(GlbGrassMesh geometry, int[] batchedIndices,
		Primitive[] primitives, Material[] materials, int sourceNodeCount,
		int sourcePrimitiveCount)
	{
		vertices = geometry.vertices;
		indices = batchedIndices;
		// The shared loader's glTF Y-up -> RuneLite Y-down conversion is a
		// reflection. Grass is double-sided, but tree bark relies on culling, so
		// restore outward triangle winding for this material-aware path.
		for (int index = 0; index + 2 < indices.length; index += 3)
		{
			int swap = indices[index + 1];
			indices[index + 1] = indices[index + 2];
			indices[index + 2] = swap;
		}
		normalizedMin = geometry.normalizedMin;
		normalizedMax = geometry.normalizedMax;
		this.primitives = primitives;
		this.materials = materials;
		this.sourceNodeCount = sourceNodeCount;
		this.sourcePrimitiveCount = sourcePrimitiveCount;
	}

	static VegetationGlbMesh load(String resource) throws IOException
	{
		return load(resource, Map.of());
	}

	static VegetationGlbMesh load(String resource,
		Map<String, TreeReplacementRegistry.MaterialOverride> overrides)
		throws IOException
	{
		GlbGrassMesh geometry = GlbGrassMesh.load(resource);
		byte[] bytes;
		try (InputStream input = VegetationGlbMesh.class.getResourceAsStream(resource))
		{
			if (input == null)
			{
				throw new IOException("Missing vegetation GLB asset: " + resource);
			}
			bytes = input.readAllBytes();
		}
		ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (file.getInt() != 0x46546c67 || file.getInt() != 2)
		{
			throw new IOException("Unsupported vegetation GLB header: " + resource);
		}
		file.getInt();
		String json = null;
		ByteBuffer binary = null;
		while (file.remaining() >= 8)
		{
			int length = file.getInt();
			int type = file.getInt();
			if (length < 0 || length > file.remaining())
			{
				throw new IOException("Invalid vegetation GLB chunk length");
			}
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
			throw new IOException("Vegetation GLB has no JSON/BIN chunks");
		}
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		Material[] materials = readMaterials(root, binary, resource);
		applyMaterialOverrides(materials, resource, overrides);
		Primitive[] sourcePrimitives = readPrimitives(root, materials.length);
		int indexCount = 0;
		for (Primitive primitive : sourcePrimitives)
		{
			indexCount += primitive.indexCount;
		}
		if (indexCount != geometry.indices.length)
		{
			throw new IOException("Vegetation primitive/index mismatch for " + resource
				+ ": " + indexCount + " != " + geometry.indices.length);
		}
		Batch batch = batchPrimitivesByMaterial(geometry.indices,
			sourcePrimitives, materials.length);
		int sourceNodeCount = root.has("nodes")
			? root.getAsJsonArray("nodes").size() : 0;
		return new VegetationGlbMesh(geometry, batch.indices, batch.primitives,
			materials, sourceNodeCount, sourcePrimitives.length);
	}

	/**
	 * The shared GLB decoder has already baked every node transform into its
	 * vertices and concatenated the results. Reorder only whole index ranges so
	 * primitives using the same final runtime material become one draw group.
	 * Vertex positions, normals, UVs, triangle winding and topology are untouched.
	 */
	private static Batch batchPrimitivesByMaterial(int[] sourceIndices,
		Primitive[] sourcePrimitives, int materialCount) throws IOException
	{
		int[] materialCounts = new int[materialCount];
		for (Primitive primitive : sourcePrimitives)
		{
			if (primitive.firstIndex < 0 || primitive.indexCount < 0
				|| primitive.firstIndex + primitive.indexCount > sourceIndices.length)
			{
				throw new IOException("Vegetation primitive index range is invalid");
			}
			materialCounts[primitive.material] += primitive.indexCount;
		}

		int[] indices = new int[sourceIndices.length];
		List<Primitive> primitives = new ArrayList<>();
		int[] materialOffsets = new int[materialCount];
		int output = 0;
		for (int material = 0; material < materialCount; ++material)
		{
			materialOffsets[material] = output;
			if (materialCounts[material] == 0)
			{
				continue;
			}
			primitives.add(new Primitive(output, materialCounts[material], material));
			output += materialCounts[material];
		}
		if (output != sourceIndices.length)
		{
			throw new IOException("Vegetation batching lost index data");
		}
		int[] materialWrites = materialOffsets.clone();
		for (Primitive primitive : sourcePrimitives)
		{
			int target = materialWrites[primitive.material];
			System.arraycopy(sourceIndices, primitive.firstIndex,
				indices, target, primitive.indexCount);
			materialWrites[primitive.material] += primitive.indexCount;
		}
		return new Batch(indices, primitives.toArray(Primitive[]::new));
	}

	private static void applyMaterialOverrides(Material[] materials, String modelResource,
		Map<String, TreeReplacementRegistry.MaterialOverride> overrides)
		throws IOException
	{
		Set<String> unmatched = new HashSet<>(overrides.keySet());
		for (int index = 0; index < materials.length; ++index)
		{
			Material material = materials[index];
			String materialName = index == 0 ? "None" : material.name;
			TreeReplacementRegistry.MaterialOverride override =
				overrides.get(materialName);
			if (override == null)
			{
				continue;
			}
			int slash = modelResource.lastIndexOf('/');
			String textureResource = modelResource.substring(0, slash + 1)
				+ override.texture;
			Image image = readPackagedImage(textureResource);
			float alphaCutoff = override.alphaCutoff == null
				? material.alphaCutoff : override.alphaCutoff;
			boolean doubleSided = override.doubleSided == null
				? material.doubleSided : override.doubleSided;
			float windResponse = override.windResponse == null
				? material.windResponse : override.windResponse;
			materials[index] = new Material(material.name, material.baseColorFactor,
				image, alphaCutoff, doubleSided, windResponse);
			unmatched.remove(materialName);
		}
		if (!unmatched.isEmpty())
		{
			throw new IOException("Vegetation material overrides did not match "
				+ modelResource + ": " + unmatched);
		}
	}

	private static Primitive[] readPrimitives(JsonObject root, int materialCount)
		throws IOException
	{
		JsonArray accessors = root.getAsJsonArray("accessors");
		JsonArray meshes = root.getAsJsonArray("meshes");
		List<Primitive> output = new ArrayList<>();
		int firstIndex = 0;
		for (var meshElement : meshes)
		{
			JsonArray primitives = meshElement.getAsJsonObject().getAsJsonArray("primitives");
			for (var primitiveElement : primitives)
			{
				JsonObject primitive = primitiveElement.getAsJsonObject();
				if (primitive.has("mode") && primitive.get("mode").getAsInt() != 4)
				{
					continue;
				}
				int accessor = primitive.has("indices")
					? primitive.get("indices").getAsInt()
					: primitive.getAsJsonObject("attributes").get("POSITION").getAsInt();
				int count = accessors.get(accessor).getAsJsonObject().get("count").getAsInt();
				int material = primitive.has("material")
					? primitive.get("material").getAsInt() + 1 : 0;
				if (material < 0 || material >= materialCount)
				{
					throw new IOException("Vegetation primitive has invalid material index");
				}
				output.add(new Primitive(firstIndex, count, material));
				firstIndex += count;
			}
		}
		return output.toArray(Primitive[]::new);
	}

	private static Material[] readMaterials(JsonObject root, ByteBuffer binary,
		String resource) throws IOException
	{
		JsonArray sourceMaterials = root.has("materials")
			? root.getAsJsonArray("materials") : new JsonArray();
		Material[] output = new Material[sourceMaterials.size() + 1];
		output[0] = Material.defaultMaterial();
		for (int index = 0; index < sourceMaterials.size(); ++index)
		{
			JsonObject source = sourceMaterials.get(index).getAsJsonObject();
			JsonObject pbr = source.has("pbrMetallicRoughness")
				? source.getAsJsonObject("pbrMetallicRoughness") : new JsonObject();
			float[] factor = {1, 1, 1, 1};
			if (pbr.has("baseColorFactor"))
			{
				JsonArray values = pbr.getAsJsonArray("baseColorFactor");
				for (int component = 0; component < Math.min(4, values.size()); ++component)
				{
					factor[component] = values.get(component).getAsFloat();
				}
			}
			Image image = null;
			if (pbr.has("baseColorTexture"))
			{
				int textureIndex = pbr.getAsJsonObject("baseColorTexture")
					.get("index").getAsInt();
				image = readTextureImage(root, binary, resource, textureIndex);
			}
			String alphaMode = source.has("alphaMode")
				? source.get("alphaMode").getAsString() : "OPAQUE";
			float alphaCutoff = source.has("alphaCutoff")
				? source.get("alphaCutoff").getAsFloat()
				: "OPAQUE".equals(alphaMode) ? 0.0f : 0.5f;
			output[index + 1] = new Material(
				source.has("name") ? source.get("name").getAsString() : "material-" + index,
				factor, image, alphaCutoff,
				source.has("doubleSided") && source.get("doubleSided").getAsBoolean(),
				0.0f);
		}
		return output;
	}

	private static Image readTextureImage(JsonObject root, ByteBuffer binary,
		String resource, int textureIndex) throws IOException
	{
		JsonArray textures = root.getAsJsonArray("textures");
		JsonArray images = root.getAsJsonArray("images");
		if (textures == null || images == null || textureIndex < 0
			|| textureIndex >= textures.size())
		{
			throw new IOException("Vegetation material references a missing texture");
		}
		int imageIndex = textures.get(textureIndex).getAsJsonObject()
			.get("source").getAsInt();
		JsonObject image = images.get(imageIndex).getAsJsonObject();
		byte[] encoded;
		if (image.has("bufferView"))
		{
			JsonObject view = root.getAsJsonArray("bufferViews")
				.get(image.get("bufferView").getAsInt()).getAsJsonObject();
			int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
			int length = view.get("byteLength").getAsInt();
			if (offset < 0 || length < 0 || offset + length > binary.capacity())
			{
				throw new IOException("Vegetation image buffer view is out of range");
			}
			encoded = new byte[length];
			ByteBuffer copy = binary.duplicate();
			copy.position(offset);
			copy.get(encoded);
		}
		else if (image.has("uri"))
		{
			String uri = image.get("uri").getAsString();
			if (uri.startsWith("data:"))
			{
				int comma = uri.indexOf(',');
				if (comma < 0)
				{
					throw new IOException("Invalid vegetation data URI");
				}
				encoded = uri.substring(0, comma).contains(";base64")
					? Base64.getDecoder().decode(uri.substring(comma + 1))
					: URLDecoder.decode(uri.substring(comma + 1), StandardCharsets.UTF_8)
						.getBytes(StandardCharsets.ISO_8859_1);
			}
			else
			{
				int slash = resource.lastIndexOf('/');
				String imageResource = resource.substring(0, slash + 1) + uri;
				try (InputStream input = VegetationGlbMesh.class
					.getResourceAsStream(imageResource))
				{
					if (input == null)
					{
						throw new IOException("Missing vegetation image: " + imageResource);
					}
					encoded = input.readAllBytes();
				}
			}
		}
		else
		{
			throw new IOException("Vegetation image has no bufferView or URI");
		}
		return decodeImage(encoded);
	}

	private static Image readPackagedImage(String resource) throws IOException
	{
		try (InputStream input = VegetationGlbMesh.class.getResourceAsStream(resource))
		{
			if (input == null)
			{
				throw new IOException("Missing vegetation material override: " + resource);
			}
			return decodeImage(input.readAllBytes());
		}
	}

	private static Image decodeImage(byte[] encoded) throws IOException
	{
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(encoded));
		if (decoded == null)
		{
			throw new IOException("Unsupported vegetation image encoding");
		}
		int width = decoded.getWidth();
		int height = decoded.getHeight();
		ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = height - 1; y >= 0; --y)
		{
			for (int x = 0; x < width; ++x)
			{
				int argb = decoded.getRGB(x, y);
				rgba.put((byte) (argb >> 16));
				rgba.put((byte) (argb >> 8));
				rgba.put((byte) argb);
				rgba.put((byte) (argb >> 24));
			}
		}
		rgba.flip();
		return new Image(width, height, rgba);
	}

	private static final class Batch
	{
		private final int[] indices;
		private final Primitive[] primitives;

		private Batch(int[] indices, Primitive[] primitives)
		{
			this.indices = indices;
			this.primitives = primitives;
		}
	}

	static final class Primitive
	{
		final int firstIndex;
		final int indexCount;
		final int material;

		private Primitive(int firstIndex, int indexCount, int material)
		{
			this.firstIndex = firstIndex;
			this.indexCount = indexCount;
			this.material = material;
		}
	}

	static final class Material
	{
		final String name;
		final float[] baseColorFactor;
		final Image image;
		final float alphaCutoff;
		final boolean doubleSided;
		final float windResponse;

		private Material(String name, float[] baseColorFactor, Image image,
			float alphaCutoff, boolean doubleSided, float windResponse)
		{
			this.name = name;
			this.baseColorFactor = baseColorFactor;
			this.image = image;
			this.alphaCutoff = alphaCutoff;
			this.doubleSided = doubleSided;
			this.windResponse = windResponse;
		}

		private static Material defaultMaterial()
		{
			return new Material("default", new float[]{1, 1, 1, 1},
				null, 0.0f, false, 0.0f);
		}
	}

	static final class Image
	{
		final int width;
		final int height;
		final ByteBuffer rgba;

		private Image(int width, int height, ByteBuffer rgba)
		{
			this.width = width;
			this.height = height;
			this.rgba = rgba;
		}
	}
}
