#version 330

uniform sampler2D shadowMap;

in vec2 fUv;

out vec4 FragColor;

void main()
{
    float depth = texture(shadowMap, fUv).r;

    /*
     * Raw depth is often packed very close to white.
     * Expand the useful range so geometry is easier to see.
     */
    float visibleDepth =
        clamp(
            (1.0 - depth) * 20.0,
            0.0,
            1.0
        );

    FragColor = vec4(
        visibleDepth,
        visibleDepth,
        visibleDepth,
        1.0
    );
}