/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import static org.lwjgl.opengl.GL33C.*;

/** One uploaded tree type with a reusable mesh and streamed instance buffer. */
final class TreeGpuAsset
{
	static final int INSTANCE_FLOATS = 6;
	static final int MAX_INSTANCES = 4096;

	final TreeReplacementRegistry.Definition definition;
	final VegetationGlbMesh mesh;
	final int[] materialTextures;
	final FloatBuffer instances = ByteBuffer.allocateDirect(
		MAX_INSTANCES * INSTANCE_FLOATS * Float.BYTES)
		.order(ByteOrder.nativeOrder()).asFloatBuffer();
	final int vao;
	final int vertexBuffer;
	final int indexBuffer;
	final int instanceBuffer;
	int instanceCount;

	private TreeGpuAsset(TreeReplacementRegistry.Definition definition,
		VegetationGlbMesh mesh, int[] materialTextures, int vao,
		int vertexBuffer, int indexBuffer, int instanceBuffer)
	{
		this.definition = definition;
		this.mesh = mesh;
		this.materialTextures = materialTextures;
		this.vao = vao;
		this.vertexBuffer = vertexBuffer;
		this.indexBuffer = indexBuffer;
		this.instanceBuffer = instanceBuffer;
	}

	static TreeGpuAsset load(TreeReplacementRegistry.Definition definition)
		throws IOException
	{
		return load(definition, definition.resourcePath());
	}

	static TreeGpuAsset load(TreeReplacementRegistry.Definition definition,
		String resourcePath) throws IOException
	{
		VegetationGlbMesh mesh = VegetationGlbMesh.load(resourcePath,
			definition.materialOverrides);
		FloatBuffer vertices = ByteBuffer.allocateDirect(mesh.vertices.length * Float.BYTES)
			.order(ByteOrder.nativeOrder()).asFloatBuffer();
		vertices.put(mesh.vertices).flip();
		IntBuffer indices = ByteBuffer.allocateDirect(mesh.indices.length * Integer.BYTES)
			.order(ByteOrder.nativeOrder()).asIntBuffer();
		indices.put(mesh.indices).flip();

		int vao = glGenVertexArrays();
		int vertexBuffer = glGenBuffers();
		int indexBuffer = glGenBuffers();
		int instanceBuffer = glGenBuffers();
		int[] textures = new int[mesh.materials.length];
		try
		{
			glBindVertexArray(vao);
			glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
			glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false,
				VegetationGlbMesh.FLOATS_PER_VERTEX * Float.BYTES, 0L);
			glEnableVertexAttribArray(1);
			glVertexAttribPointer(1, 3, GL_FLOAT, false,
				VegetationGlbMesh.FLOATS_PER_VERTEX * Float.BYTES, 3L * Float.BYTES);
			glEnableVertexAttribArray(2);
			glVertexAttribPointer(2, 2, GL_FLOAT, false,
				VegetationGlbMesh.FLOATS_PER_VERTEX * Float.BYTES, 6L * Float.BYTES);

			glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
			glBufferData(GL_ARRAY_BUFFER,
				(long) MAX_INSTANCES * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW);
			glEnableVertexAttribArray(4);
			glVertexAttribPointer(4, 4, GL_FLOAT, false,
				INSTANCE_FLOATS * Float.BYTES, 0L);
			glVertexAttribDivisor(4, 1);
			glEnableVertexAttribArray(5);
			glVertexAttribPointer(5, 2, GL_FLOAT, false,
				INSTANCE_FLOATS * Float.BYTES, 4L * Float.BYTES);
			glVertexAttribDivisor(5, 1);

			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
			for (int material = 0; material < mesh.materials.length; ++material)
			{
				VegetationGlbMesh.Image image = mesh.materials[material].image;
				if (image != null)
				{
					textures[material] = uploadTexture(image);
				}
			}
		}
		catch (RuntimeException ex)
		{
			for (int texture : textures)
			{
				if (texture != 0)
				{
					glDeleteTextures(texture);
				}
			}
			glDeleteBuffers(instanceBuffer);
			glDeleteBuffers(indexBuffer);
			glDeleteBuffers(vertexBuffer);
			glDeleteVertexArrays(vao);
			throw ex;
		}
		finally
		{
			glBindVertexArray(0);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		return new TreeGpuAsset(definition, mesh, textures, vao,
			vertexBuffer, indexBuffer, instanceBuffer);
	}

	private static int uploadTexture(VegetationGlbMesh.Image image)
	{
		int texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// Match the main world texture pipeline's byte-preserving color contract.
		// The scene framebuffer is not using automatic sRGB encoding, so an sRGB
		// texture here would be decoded to linear and displayed substantially dark.
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
			image.width, image.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, image.rgba);
		glGenerateMipmap(GL_TEXTURE_2D);
		return texture;
	}

	void uploadInstances()
	{
		instances.flip();
		instanceCount = instances.remaining() / INSTANCE_FLOATS;
		glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
		glBufferData(GL_ARRAY_BUFFER,
			(long) MAX_INSTANCES * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW);
		if (instanceCount > 0)
		{
			glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
		}
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	void destroy()
	{
		for (int texture : materialTextures)
		{
			if (texture != 0)
			{
				glDeleteTextures(texture);
			}
		}
		glDeleteBuffers(instanceBuffer);
		glDeleteBuffers(indexBuffer);
		glDeleteBuffers(vertexBuffer);
		glDeleteVertexArrays(vao);
	}
}
