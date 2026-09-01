#version 330

uniform sampler2D sourceDepth;

in vec2 fUv;

// A small sorting network is inexpensive at 512x512 and gives the blocker
// field a deterministic, outlier-resistant depth instead of preserving roof
// shingles and isolated triangle edges as individual shafts.
void compareSwap(inout float a, inout float b)
{
	float lo = min(a, b);
	float hi = max(a, b);
	a = lo;
	b = hi;
}

void main()
{
	ivec2 sourceSize = textureSize(sourceDepth, 0);
	ivec2 sourceCenter = ivec2(gl_FragCoord.xy) * 2 + ivec2(1);
	float depths[9];
	int support = 0;
	int sampleIndex = 0;

	for (int y = -1; y <= 1; ++y)
	{
		for (int x = -1; x <= 1; ++x)
		{
			ivec2 texel = clamp(
				sourceCenter + ivec2(x, y),
				ivec2(0),
				sourceSize - ivec2(1));
			float depth = texelFetch(sourceDepth, texel, 0).r;
			depths[sampleIndex++] = depth;
			support += depth < 0.999999 ? 1 : 0;
		}
	}

	// Insertion sort is well-defined in GLSL 330 and keeps the implementation
	// portable to the macOS core profile. Clear-depth samples naturally sort to
	// the end, letting us take the median of only the supported blocker taps.
	for (int i = 1; i < 9; ++i)
	{
		for (int j = i; j > 0; --j)
		{
			compareSwap(depths[j - 1], depths[j]);
		}
	}

	// Three agreeing taps are enough to retain a broad silhouette corner, while
	// one- and two-tap details (roof seams, shingles, thin noise) are rejected.
	// The selected value is the median of the supported blocker depths.
	gl_FragDepth = support >= 3 ? depths[(support - 1) / 2] : 1.0;
}
