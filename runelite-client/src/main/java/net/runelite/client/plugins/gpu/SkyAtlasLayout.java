package net.runelite.client.plugins.gpu;

public class SkyAtlasLayout
{
    public enum Slot
    {
        TOP_LEFT,
        TOP_MIDDLE,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_MIDDLE,
        BOTTOM_RIGHT
    }

    public final Slot north;
    public final Slot east;
    public final Slot south;
    public final Slot west;

    public SkyAtlasLayout(
            Slot north,
            Slot east,
            Slot south,
            Slot west)
    {
        this.north = north;
        this.east = east;
        this.south = south;
        this.west = west;
    }
}