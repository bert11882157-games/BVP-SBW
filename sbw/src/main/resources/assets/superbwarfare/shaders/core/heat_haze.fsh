#version 150

// Dry-thrust heat haze: refracts a copy of the frame (Sampler0) behind the exhaust. UV0.x runs across the ribbon
// (0..1), UV0.y along it from the nozzle; the vertex alpha is the haze strength.

uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform float GameTime;

in vec2 texCoord0;
in float strength;

out vec4 fragColor;

void main() {
    // Soft edges across the ribbon and at its ends.
    float across = 1.0 - abs(texCoord0.x * 2.0 - 1.0);
    float edge = smoothstep(0.0, 0.6, across) * smoothstep(0.0, 0.08, texCoord0.y) * (1.0 - smoothstep(0.7, 1.0, texCoord0.y));
    float k = strength * edge;
    if (k < 0.004) discard;
    // Rising, rolling ripples carried aft with the exhaust (GameTime is in days: 24000 ticks).
    float t = GameTime * 24000.0 / 20.0;
    vec2 q = vec2(texCoord0.x * 7.0, texCoord0.y * 11.0 - t * 9.0);
    vec2 wobble = vec2(sin(q.y * 1.7 + sin(q.x * 1.3 + t * 3.0)) + 0.5 * sin(q.y * 3.9 + q.x * 2.1),
                       cos(q.y * 1.3 + q.x * 0.9) + 0.5 * cos(q.y * 3.1 - t * 5.0));
    vec2 screen = gl_FragCoord.xy / ScreenSize;
    vec2 offset = wobble * 0.0025 * k;
    vec3 scene = texture(Sampler0, clamp(screen + offset, vec2(0.001), vec2(0.999))).rgb;
    // A very slight warm brightening keeps the hot air readable over flat sky without looking like flame.
    scene *= 1.0 + 0.02 * k;
    fragColor = vec4(scene, clamp(k * 1.6, 0.0, 1.0));
}
