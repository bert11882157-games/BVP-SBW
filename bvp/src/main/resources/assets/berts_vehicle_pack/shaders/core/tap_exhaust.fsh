#version 150
#moj_import <fog.glsl>
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
    vec4 shaded = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    // TaP's GL_GREATER, 0.001 test, preserving the texture's faint transparent edges.
    if (shaded.a <= 0.001) discard;
    fragColor = linear_fog(shaded, vertexDistance, FogStart, FogEnd, FogColor);
}
