#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
// Self-lit quads (full-bright light coordinates: fire, flash, afterburner flame) are multiplied by this, so glowing
// effects read brighter overall and far brighter in the dark.
uniform float GlowBoost;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
    texCoord0 = UV0;
    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);
    float glow = (UV2.x >= 240 && UV2.y >= 240) ? GlowBoost : 1.0;
    vertexColor = vec4(Color.rgb * light.rgb * glow, Color.a);
}
