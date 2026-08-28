# Authored texture replacements

Place replacement PNGs in this folder and add an entry to the adjacent
`authored_texture_overrides.json` file.

Each entry must use exactly one identity selector:

- `textureId`: RuneLite cache texture ID; applies to matching textured terrain and models.
- `underlayId`: exact terrain underlay ID; applies to terrain tiles.
- `overlayId`: exact terrain overlay ID; applies to terrain tiles and takes priority over underlay.

Example:

```json
{
  "overlayId": 29,
  "source": "authored_textures/overlay-29.png"
}
```

PNG files are scaled to 256x256 at load time and mipmaps are generated. Missing
or unmapped entries fall back to the original RuneLite surface. Do not use a
semantic class such as `STONE` or `GRASS` as an identity selector.
