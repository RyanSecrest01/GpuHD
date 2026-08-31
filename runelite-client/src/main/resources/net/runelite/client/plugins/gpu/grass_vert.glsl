#version 330

// Unified procedural surface-detail contract:
//   location 0: xyz = planted world position, w = stable random seed
//   location 1: x = packed RuneLite HSL, y = detail type
//               type 0 = grass clump, type 1 = stone/pebble pair,
//               type 2 = sand fragments, type 3 = dirt clods
// Grass instances emit 120 vertices: ten tapered blades with two vertical
// segments each. Scatter details reuse the first 60 vertices of this mesh.
layout(location = 0) in vec4 anchorSeed;
layout(location = 1) in vec2 anchorDetail;

// RuneLite's normal world pass uses reversed depth. These details are projected
// directly into that pass and are drawn with GL_GREATER by the Java owner.
uniform mat4 projection;
uniform vec3 focusPosition;
// Adds the scene base back to local GPU coordinates for rebase-stable wind.
uniform vec2 worldOffset;
uniform float time;
// grass, stone, sand, dirt radii respectively (world units).
uniform vec4 drawRadius;
uniform float heightScale;
uniform float windStrength;
uniform int weatherMode;

out vec2 fDetailUv;
out vec3 fWorldPos;
out vec3 fDetailNormal;
out vec3 fDetailTangent;
flat out float fSeed;
flat out float fDistanceFade;
flat out float fGroundHsl;
flat out float fDetailType;

const float TWO_PI = 6.28318530718;
const int BLADES_PER_CLUMP = 10;
const int SEGMENTS_PER_BLADE = 2;
const int VERTICES_PER_SEGMENT = 6;
const int VERTICES_PER_BLADE =
	SEGMENTS_PER_BLADE * VERTICES_PER_SEGMENT;
const int VERTICES_PER_ROCK = 30;
const int ROCK_SIDES = 5;

float hash(float value)
{
	return fract(sin(value * 127.17 + 19.73) * 43758.5453);
}

vec2 cornerForVertex(int vertex)
{
	if (vertex == 0) return vec2(-1.0, 0.0);
	if (vertex == 1) return vec2( 1.0, 0.0);
	if (vertex == 2) return vec2( 1.0, 1.0);
	if (vertex == 3) return vec2(-1.0, 0.0);
	if (vertex == 4) return vec2( 1.0, 1.0);
	return vec2(-1.0, 1.0);
}

vec3 rockRingPoint(
	int index,
	float rockSeed,
	float radiusX,
	float radiusZ,
	float rockHeight,
	float yaw)
{
	float pointSeed = hash(rockSeed * 43.17 + float(index) * 17.91);
	float angle = yaw + TWO_PI * float(index) / float(ROCK_SIDES);
	float rimScale = mix(0.86, 1.09, pointSeed);
	float rimHeight = rockHeight * mix(
		0.38, 0.56,
		hash(rockSeed * 71.03 + float(index) * 9.37));
	return vec3(
		cos(angle) * radiusX * rimScale,
		-rimHeight,
		sin(angle) * radiusZ * rimScale);
}

void emitGrass(float spatialSeed, float scale)
{
	int blade = gl_VertexID / VERTICES_PER_BLADE;
	int bladeVertex = gl_VertexID - blade * VERTICES_PER_BLADE;
	int segment = bladeVertex / VERTICES_PER_SEGMENT;
	int vertex = bladeVertex - segment * VERTICES_PER_SEGMENT;
	vec2 corner = cornerForVertex(vertex);
	float bladeT = (float(segment) + corner.y)
		/ float(SEGMENTS_PER_BLADE);

	float clumpSeed = hash(spatialSeed + anchorSeed.w * 17.31);
	float bladeSeed = hash(spatialSeed + anchorSeed.w * 31.73
		+ float(blade) * 19.19 + 11.0);
	float shapeSeed = hash(spatialSeed + anchorSeed.w * 47.11
		+ float(blade) * 7.73 + 23.0);

	// These dimensions remain cheap ribbon geometry but are deliberately large
	// enough to read at RuneLite's normal zoom. Full width is 4--7 world units.
	// A full-range geometry seed chooses a clump archetype independently from
	// density selection: short/wide tuft, normal meadow, or rare tall/fine accent.
	float archetype = hash(spatialSeed + anchorSeed.w * 101.19 + 5.7);
	float heightProfile = archetype < 0.20 ? 0.78
		: archetype > 0.90 ? 1.17 : 1.0;
	float widthProfile = archetype < 0.20 ? 1.14
		: archetype > 0.90 ? 0.78 : 1.0;
	float bladeHeight = mix(24.0, 46.0, bladeSeed)
		* heightProfile * scale;
	float halfWidth = mix(2.0, 3.5, shapeSeed)
		* widthProfile * scale;

	// Independent stable orientations prevent the five ribbons from exposing a
	// repeated star/aloe silhouette in RuneLite's common overhead view. Several
	// nearby clumps still provide coverage from every direction without billboards.
	float angle = hash(spatialSeed + anchorSeed.w * 61.37
		+ float(blade) * 29.17 + clumpSeed * 7.0) * TWO_PI;
	vec2 radialDirection = vec2(cos(angle), sin(angle));
	vec2 widthDirection = vec2(-radialDirection.y, radialDirection.x);
	float radialOffset = mix(0.30, 3.0, shapeSeed * shapeSeed) * scale;

	// All leaves in the clump share the same travelling gust phase. Individual
	// blade variation only affects compliance, avoiding the boiling-card look.
	float windSign = windStrength < 0.0 ? -1.0 : 1.0;
	vec2 flowDirection = normalize(vec2(0.88, 0.34)) * windSign;
	vec2 crossDirection = vec2(-flowDirection.y, flowDirection.x);
	float weatherWind = weatherMode == 4 ? 1.90
		: weatherMode == 2 ? 1.55
			: weatherMode == 1 ? 1.18
				: weatherMode == 3 ? 0.92 : 1.0;
	float windAmount = min(
		clamp(abs(windStrength), 0.0, 2.5) * weatherWind,
		2.15);
	float broadPhase = dot(anchorSeed.xz + worldOffset, flowDirection) * 0.0042
		- time * mix(0.72, 1.48, clamp(windAmount / 2.5, 0.0, 1.0));
	float gustEnvelope = clamp(0.55
		+ sin(broadPhase) * 0.25
		+ sin(broadPhase * 0.47 + 2.1) * 0.12,
		0.16, 0.94);
	float flutterPhase = broadPhase * 1.63 - time * 0.68
		+ bladeSeed * TWO_PI;
	float tipFlutter = sin(flutterPhase) * 0.72
		+ sin(flutterPhase * 1.91 + 1.4) * 0.28;

	vec2 restBend = radialDirection * bladeHeight
		* mix(0.045, 0.095, shapeSeed);
	vec2 windBend = flowDirection * bladeHeight * windAmount
		* mix(0.040, 0.105, gustEnvelope)
		* mix(0.88, 1.10, bladeSeed);
	vec2 flutterBend = crossDirection * tipFlutter * bladeHeight
		* 0.014 * windAmount;

	float bendProfile = pow(bladeT, 1.60);
	float flutterProfile = pow(bladeT, 2.40);
	float taper = mix(1.0, 0.075, smoothstep(0.08, 1.0, bladeT));
	vec3 position = anchorSeed.xyz;
	position.xz += radialDirection * radialOffset;
	position.xz += widthDirection * corner.x * halfWidth * taper;
	position.xz += (restBend + windBend) * bendProfile
		+ flutterBend * flutterProfile;
	// RuneLite elevation grows toward negative Y. Burying the base prevents a
	// bright gap where the blade intersects sloped terrain.
	position.y += 2.2 - bladeHeight * bladeT;

	float normalT = clamp(bladeT, 0.001, 1.0);
	float bendDerivative = 1.60 * pow(normalT, 0.60);
	float flutterDerivative = 2.40 * pow(normalT, 1.40);
	vec2 tangentXz = (restBend + windBend) * bendDerivative
		+ flutterBend * flutterDerivative;
	vec3 centerTangent = normalize(vec3(
		tangentXz.x, -bladeHeight, tangentXz.y));
	vec3 widthTangent = vec3(widthDirection.x, 0.0, widthDirection.y);
	vec3 bladeNormal = normalize(cross(centerTangent, widthTangent));

	gl_Position = projection * vec4(position, 1.0);
	fDetailUv = vec2(corner.x * 0.5 + 0.5, bladeT);
	fWorldPos = position;
	fDetailNormal = bladeNormal;
	fDetailTangent = centerTangent;
	fSeed = bladeSeed;
}

void emitScatter(float spatialSeed, float scale, int detailType)
{
	int rock = gl_VertexID / VERTICES_PER_ROCK;
	int rockVertex = gl_VertexID - rock * VERTICES_PER_ROCK;
	int face = rockVertex / 3;
	int corner = rockVertex - face * 3;
	int side = face < ROCK_SIDES ? face : face - ROCK_SIDES;
	int nextSide = (side + 1) % ROCK_SIDES;

	float rockSeed = hash(spatialSeed + anchorSeed.w * 83.71
		+ float(rock) * 29.23 + 7.0);
	float scaleSeed = hash(rockSeed * 51.31 + 4.7);
	bool isStone = detailType == 1;
	bool isSand = detailType == 2;
	float primaryScale = isStone ? mix(0.86, 1.12, scaleSeed)
		: isSand ? mix(0.72, 1.02, scaleSeed)
		: mix(0.78, 1.05, scaleSeed);
	float secondaryScale = isStone ? mix(0.55, 0.78, scaleSeed)
		: isSand ? mix(0.55, 0.80, scaleSeed)
		: mix(0.60, 0.84, scaleSeed);
	float rockScale = rock == 0 ? primaryScale : secondaryScale;
	float radius = (isStone ? mix(4.4, 6.8, rockSeed)
		: isSand ? mix(2.7, 5.0, rockSeed)
		: mix(3.1, 5.8, rockSeed)) * rockScale * scale;
	float radiusX = radius * mix(0.88, 1.16, hash(rockSeed * 31.7));
	float radiusZ = radius * (isSand
		? mix(0.38, 0.70, hash(rockSeed * 67.9))
		: mix(0.82, 1.13, hash(rockSeed * 67.9)));
	float rockHeight = (isStone
		? mix(3.8, 7.0, hash(rockSeed * 97.1 + 2.0))
		: isSand
			? mix(0.9, 2.3, hash(rockSeed * 97.1 + 2.0))
			: mix(1.5, 3.6, hash(rockSeed * 97.1 + 2.0)))
		* rockScale * scale;
	float yaw = hash(rockSeed * 121.7 + 13.0) * TWO_PI;
	float placementAngle = hash(anchorSeed.w * 149.9 + float(rock) * 37.0)
		* TWO_PI + float(rock) * 2.35;
	float placementRadius = rock == 0
		? mix(0.3, 1.8, hash(rockSeed * 17.3))
		: isStone
			? mix(3.2, 4.5, hash(rockSeed * 23.9))
			: mix(2.4, 4.3, hash(rockSeed * 23.9));
	float burial = (isStone ? 2.4 : isSand ? 0.8 : 1.3) * scale;
	vec3 rockOrigin = anchorSeed.xyz + vec3(
		cos(placementAngle) * placementRadius * scale,
		burial,
		sin(placementAngle) * placementRadius * scale);

	vec3 top = vec3(
		(hash(rockSeed * 181.1) - 0.5) * radiusX * 0.24,
		-rockHeight,
		(hash(rockSeed * 211.3) - 0.5) * radiusZ * 0.24);
	vec3 bottom = vec3(0.0,
		(isStone ? 1.5 : isSand ? 0.55 : 0.9) * scale,
		0.0);
	vec3 ringA = rockRingPoint(
		side, rockSeed, radiusX, radiusZ, rockHeight, yaw);
	vec3 ringB = rockRingPoint(
		nextSide, rockSeed, radiusX, radiusZ, rockHeight, yaw);

	vec3 p0;
	vec3 p1;
	vec3 p2;
	if (face < ROCK_SIDES)
	{
		p0 = top;
		p1 = ringA;
		p2 = ringB;
	}
	else
	{
		p0 = bottom;
		p1 = ringB;
		p2 = ringA;
	}

	vec3 localPosition = corner == 0 ? p0 : corner == 1 ? p1 : p2;
	vec3 faceNormal = normalize(cross(p1 - p0, p2 - p0));
	vec3 faceCenter = (p0 + p1 + p2) / 3.0;
	vec3 interior = vec3(0.0, -rockHeight * 0.35, 0.0);
	if (dot(faceNormal, faceCenter - interior) < 0.0)
	{
		faceNormal = -faceNormal;
	}

	vec3 position = rockOrigin + localPosition;
	gl_Position = projection * vec4(position, 1.0);
	fDetailUv = vec2(float(rock), clamp(-localPosition.y / rockHeight, 0.0, 1.0));
	fWorldPos = position;
	fDetailNormal = faceNormal;
	fDetailTangent = normalize(p1 - p0);
	// A face-stable seed provides subtle faceting without a procedural texture.
	fSeed = hash(rockSeed * 113.9 + float(face) * 19.1);
}

void main()
{
	int detailType = clamp(int(floor(anchorDetail.y + 0.5)), 0, 3);
	// Keep scatter details at their original two-piece topology while the grass
	// clump uses the expanded ten-blade mesh in this single instanced draw.
	if (detailType != 0 && gl_VertexID >= 60)
	{
		gl_Position = vec4(2.0);
		return;
	}
	// Morphology comes only from the CPU's absolute-world seed. Scene-local GPU
	// coordinates can rebase as the player walks and must not reshuffle shapes.
	float spatialSeed = anchorSeed.w * 8191.731 + float(detailType) * 131.17;
	float scale = max(heightScale, 0.05);
	if (detailType == 0)
	{
		emitGrass(spatialSeed, scale);
	}
	else
	{
		emitScatter(spatialSeed, scale, detailType);
	}

	fGroundHsl = anchorDetail.x;
	fDetailType = float(detailType);
	float selectedRadius = drawRadius[detailType];
	float safeRadius = max(selectedRadius, 1.0);
	float focusDistance = length(anchorSeed.xz - focusPosition.xz);
	fDistanceFade = 1.0
		- smoothstep(safeRadius * 0.72, safeRadius, focusDistance);
}
