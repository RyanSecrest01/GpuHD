package net.runelite.client.plugins.gpu;

public enum MaterialPalette
{
	CLASSIC(0),
	NATURAL(1),
	LUSH(2);

	private final int id;

	MaterialPalette(int id)
	{
		this.id = id;
	}

	int getId()
	{
		return id;
	}
}
