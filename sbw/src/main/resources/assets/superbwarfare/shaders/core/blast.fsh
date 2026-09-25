#version 150

// Premultiplied-alpha blast sprites: glowing quads carry ~0 alpha and add light, smoke quads cover what is behind.
// Fog fades glow to nothing and pulls smoke toward the fog colour, like vanilla linear fog on straight alpha.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.r + color.g + color.b + color.a < 0.002) {
        discard;
    }
    float fog = 0.0;
    if (vertexDistance > FogStart) {
        fog = (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0) * FogColor.a;
    }
    fragColor = vec4(color.rgb * (1.0 - fog) + FogColor.rgb * color.a * fog, color.a);
}
