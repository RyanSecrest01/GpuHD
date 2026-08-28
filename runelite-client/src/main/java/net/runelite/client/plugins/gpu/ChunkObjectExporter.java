package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.coords.WorldPoint;

@Singleton
@Slf4j
final class ChunkObjectExporter
{
    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Client client;

    @Inject
    ChunkObjectExporter(Client client)
    {
        this.client = client;
    }

    Path exportCurrentChunk()
    {
        if (client.getLocalPlayer() == null)
        {
            throw new IllegalStateException(
                    "Cannot export objects before the local player exists."
            );
        }

        WorldView worldView =
                client.getLocalPlayer().getWorldView();

        if (worldView == null)
        {
            worldView =
                    client.getTopLevelWorldView();
        }

        Scene scene =
                worldView.getScene();

        WorldPoint player =
                client.getLocalPlayer().getWorldLocation();

        /*
         * OSRS map chunks are 8x8 world tiles.
         */
        int chunkMinX =
                player.getX() & ~7;

        int chunkMinY =
                player.getY() & ~7;

        int chunkMaxX =
                chunkMinX + 7;

        int chunkMaxY =
                chunkMinY + 7;

        Map<Integer, ObjectRecord> records =
                new LinkedHashMap<>();

        Tile[][][] tiles =
                scene.getTiles();

        /*
         * Scan every plane because roofs/walls above the player's
         * current plane may still matter for authored materials.
         */
        for (int plane = 0;
             plane < tiles.length;
             ++plane)
        {
            Tile[][] planeTiles =
                    tiles[plane];

            if (planeTiles == null)
            {
                continue;
            }

            for (Tile[] row : planeTiles)
            {
                if (row == null)
                {
                    continue;
                }

                for (Tile tile : row)
                {
                    if (tile == null)
                    {
                        continue;
                    }

                    WorldPoint location =
                            tile.getWorldLocation();

                    if (location == null)
                    {
                        continue;
                    }

                    int worldX =
                            location.getX();

                    int worldY =
                            location.getY();

                    if (worldX < chunkMinX
                            || worldX > chunkMaxX
                            || worldY < chunkMinY
                            || worldY > chunkMaxY)
                    {
                        continue;
                    }

                    add(
                            records,
                            tile.getWallObject(),
                            "WALL",
                            location
                    );

                    add(
                            records,
                            tile.getDecorativeObject(),
                            "DECORATIVE",
                            location
                    );

                    add(
                            records,
                            tile.getGroundObject(),
                            "GROUND",
                            location
                    );

                    GameObject[] gameObjects =
                            tile.getGameObjects();

                    if (gameObjects != null)
                    {
                        for (GameObject gameObject : gameObjects)
                        {
                            add(
                                    records,
                                    gameObject,
                                    "GAME",
                                    location
                            );
                        }
                    }
                }
            }
        }

        List<ObjectRecord> sorted =
                new ArrayList<>(
                        records.values()
                );

        sorted.sort(
                Comparator.comparingInt(
                        record -> record.id
                )
        );

        ExportBundle bundle =
                new ExportBundle();

        bundle.chunkMinX =
                chunkMinX;

        bundle.chunkMinY =
                chunkMinY;

        bundle.chunkMaxX =
                chunkMaxX;

        bundle.chunkMaxY =
                chunkMaxY;

        bundle.playerPlane =
                player.getPlane();

        bundle.uniqueObjectCount =
                sorted.size();

        bundle.objects =
                sorted;

        Path directory = exportRoot().resolve("objects");

        try
        {
            Files.createDirectories(
                    directory
            );

            String baseName =
                    "chunk-"
                            + chunkMinX
                            + "-"
                            + chunkMinY
                            + "-"
                            + TIMESTAMP.format(
                            LocalDateTime.now()
                    );

            Path json =
                    directory.resolve(
                            baseName + ".json"
                    );

            Path csv =
                    directory.resolve(
                            baseName + ".csv"
                    );

            Files.writeString(
                    json,
                    GSON.toJson(bundle),
                    StandardCharsets.UTF_8
            );

            Files.writeString(
                    csv,
                    toCsv(sorted),
                    StandardCharsets.UTF_8
            );

            exportTerrainManifest(scene, chunkMinX, chunkMinY);
            exportTextureManifest();

            log.info(
                    "Exported {} unique object IDs from chunk {},{} to {}",
                    sorted.size(),
                    chunkMinX,
                    chunkMinY,
                    directory
            );

            return directory;
        }
        catch (IOException ex)
        {
            throw new IllegalStateException(
                    "Unable to export chunk object IDs",
                    ex
            );
        }
    }

    private void exportTerrainManifest(Scene scene, int minX, int minY) throws IOException
    {
        Path dir = exportRoot().resolve("manifests");
        Files.createDirectories(dir);
        StringBuilder out = new StringBuilder("underlay_id,overlay_id,world_x,world_y,plane\n");
        short[][][] under = scene.getUnderlayIds(), over = scene.getOverlayIds();
        for (Tile[][] plane : scene.getTiles()) if (plane != null) for (Tile[] row : plane) if (row != null) for (Tile tile : row)
        {
            if (tile == null || tile.getWorldLocation() == null) continue;
            int x = tile.getWorldLocation().getX(), y = tile.getWorldLocation().getY();
            if (x < minX || x > minX + 7 || y < minY || y > minY + 7) continue;
            int sx = tile.getSceneLocation().getX(), sy = tile.getSceneLocation().getY(), p = tile.getPlane();
            out.append(under[p][sx][sy] & 0xffff).append(',').append(over[p][sx][sy] & 0xffff).append(',').append(x).append(',').append(y).append(',').append(p).append('\n');
        }
        Path file = dir.resolve("chunk-" + minX + "-" + minY + "-terrain.csv");
        Files.writeString(file, out, StandardCharsets.UTF_8);
        appendMaster("terrain", file, minX + "," + minY);
    }

    private void exportTextureManifest() throws IOException
    {
        Path dir = exportRoot().resolve("manifests");
        Files.createDirectories(dir);
        TextureProvider provider = client.getTextureProvider();
        StringBuilder out = new StringBuilder("texture_id,native_pixels,loaded\n");
        Texture[] textures = provider == null ? null : provider.getTextures();
        if (textures != null) for (int id = 0; id < textures.length; id++)
        {
            int[] pixels = textures[id] == null ? null : provider.load(id);
            out.append(id).append(',').append(pixels == null ? 0 : pixels.length).append(',').append(pixels != null).append('\n');
        }
        Path file = dir.resolve("textures.csv");
        Files.writeString(file, out, StandardCharsets.UTF_8);
        appendMaster("textures", file, "global");
    }

    private static Path exportRoot()
    {
        return Path.of(System.getProperty("user.dir")).resolve("gpu-source-export");
    }

    private static void appendMaster(String type, Path source, String scope) throws IOException
    {
        Path master = exportRoot().resolve("master-manifest.csv");
        if (!Files.exists(master)) Files.writeString(master, "type,scope,source\n", StandardCharsets.UTF_8);
        String row = type + "," + scope + "," + source.getFileName() + "\n";
        List<String> lines = Files.readAllLines(master, StandardCharsets.UTF_8);
        if (lines.stream().noneMatch(row::equals)) Files.writeString(master, row, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    private void add(
            Map<Integer, ObjectRecord> records,
            TileObject object,
            String type,
            WorldPoint tileLocation)
    {
        if (object == null)
        {
            return;
        }

        int id =
                object.getId();

        ObjectRecord record =
                records.computeIfAbsent(
                        id,
                        ignored ->
                        {
                            ObjectRecord created =
                                    new ObjectRecord();

                            created.id =
                                    id;

                            created.name =
                                    getObjectName(id);

                            return created;
                        }
                );

        record.types.add(
                type
        );

        record.occurrences++;

        /*
         * Keep one example position so you can quickly locate
         * an object again without bloating the dump.
         */
        if (record.sampleWorldX == null)
        {
            record.sampleWorldX =
                    tileLocation.getX();

            record.sampleWorldY =
                    tileLocation.getY();

            record.samplePlane =
                    tileLocation.getPlane();
        }
    }

    private String getObjectName(int id)
    {
        try
        {
            ObjectComposition composition =
                    client.getObjectDefinition(
                            id
                    );

            if (composition == null
                    || composition.getName() == null)
            {
                return "";
            }

            return composition.getName();
        }
        catch (RuntimeException ex)
        {
            return "";
        }
    }

    private static String toCsv(
            List<ObjectRecord> records)
    {
        StringBuilder csv =
                new StringBuilder();

        csv.append(
                "object_id,name,types,occurrences,sample_world_x,sample_world_y,sample_plane\n"
        );

        for (ObjectRecord record : records)
        {
            csv
                    .append(record.id)
                    .append(',')
                    .append(csvEscape(record.name))
                    .append(',')
                    .append(csvEscape(
                            String.join(
                                    "|",
                                    record.types
                            )
                    ))
                    .append(',')
                    .append(record.occurrences)
                    .append(',')
                    .append(record.sampleWorldX)
                    .append(',')
                    .append(record.sampleWorldY)
                    .append(',')
                    .append(record.samplePlane)
                    .append('\n');
        }

        return csv.toString();
    }

    private static String csvEscape(
            String value)
    {
        if (value == null)
        {
            return "";
        }

        String escaped =
                value.replace(
                        "\"",
                        "\"\""
                );

        if (escaped.contains(",")
                || escaped.contains("\"")
                || escaped.contains("\n"))
        {
            return "\""
                    + escaped
                    + "\"";
        }

        return escaped;
    }

    private static final class ExportBundle
    {
        int chunkMinX;
        int chunkMinY;
        int chunkMaxX;
        int chunkMaxY;
        int playerPlane;
        int uniqueObjectCount;
        List<ObjectRecord> objects;
    }

    private static final class ObjectRecord
    {
        int id;
        String name;

        Set<String> types =
                new LinkedHashSet<>();

        int occurrences;

        Integer sampleWorldX;
        Integer sampleWorldY;
        Integer samplePlane;
    }
}
