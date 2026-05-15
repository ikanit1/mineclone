package com.mineclone.render;

public final class Shaders {
    private Shaders() {}

    public static final String CHUNK_VERTEX = """
        #version 330 core
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec2 aUv;
        layout (location = 2) in float aLight;
        layout (location = 3) in float aBlockLight;
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform mat4 uModel;
        uniform float uTime;
        centroid out vec2 vUv;
        out float vLight;
        out float vFogDist;
        out float vBlockLight;
        void main() {
            vec4 worldPos = uModel * vec4(aPos, 1.0);
            vec4 viewPos = uView * worldPos;
            gl_Position = uProjection * viewPos;
            // aBlockLight encodes water type:
            //   < 5  : opaque block, no remap
            //   10..11 : static water (source)
            //   20..21 : animated water (flow) — cycle frames 17..32 in atlas
            bool isWater = aBlockLight > 5.0;
            bool isFlow  = aBlockLight > 15.0;
            if (isFlow) {
                // Pick current frame (6 fps over 16 frames ≈ 2.7 s loop)
                int frame = int(mod(floor(uTime * 6.0), 16.0));
                int targetTile = 17 + frame;
                int col = targetTile - (targetTile / 16) * 16;
                int row = targetTile / 16;
                float ts = 1.0 / 16.0;
                vec2 tileBase = floor(aUv / ts) * ts;          // base of source tile (tile 8)
                vec2 localUv  = aUv - tileBase;                // offset within tile (preserves inset)
                vec2 newBase  = vec2(float(col), float(row)) * ts;
                vUv = newBase + localUv;
            } else {
                vUv = aUv;
            }
            vLight = aLight;
            vFogDist = length(viewPos.xyz);
            vBlockLight = isFlow ? aBlockLight - 20.0 : (isWater ? aBlockLight - 10.0 : aBlockLight);
        }
        """;

    public static final String CHUNK_FRAGMENT = """
        #version 330 core
        centroid in vec2 vUv;
        in float vLight;
        in float vFogDist;
        in float vBlockLight;
        uniform sampler2D uAtlas;
        uniform vec3 uFogColor;
        uniform float uFogStart;
        uniform float uFogEnd;
        uniform float uAmbient;
        uniform float uDaylight;
        uniform float uBrightness;
        out vec4 FragColor;
        void main() {
            vec4 tex = texture(uAtlas, vUv);
            if (tex.a < 0.1) discard;
            float combined = max(vLight * uDaylight, vBlockLight);
            float shaped = pow(max(uAmbient, combined), 0.75) * uBrightness;
            vec3 lit = tex.rgb * min(shaped, 1.0);
            float f = clamp((vFogDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
            FragColor = vec4(mix(lit, uFogColor, f), tex.a);
        }
        """;

    public static final String HUD_VERTEX = """
        #version 330 core
        layout (location = 0) in vec2 aPos;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """;

    public static final String HUD_FRAGMENT = """
        #version 330 core
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() { FragColor = uColor; }
        """;

    public static final String LINE_VERTEX = """
        #version 330 core
        layout (location = 0) in vec3 aPos;
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform mat4 uModel;
        void main() { gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0); }
        """;

    public static final String LINE_FRAGMENT = """
        #version 330 core
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() { FragColor = uColor; }
        """;

    public static final String PARTICLE_VERTEX = """
        #version 330 core
        layout (location = 0) in vec2 aCorner;
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform vec3 uCenter;
        uniform vec3 uRight;
        uniform vec3 uUp;
        uniform float uSize;
        uniform vec2 uUv0;
        uniform vec2 uUv1;
        out vec2 vUv;
        void main() {
            vec3 world = uCenter + (uRight * aCorner.x + uUp * aCorner.y) * uSize;
            gl_Position = uProjection * uView * vec4(world, 1.0);
            vec2 frac = aCorner + vec2(0.5);
            vUv = uUv0 + (uUv1 - uUv0) * frac;
        }
        """;

    public static final String PARTICLE_FRAGMENT = """
        #version 330 core
        in vec2 vUv;
        uniform sampler2D uAtlas;
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() {
            vec4 tex = texture(uAtlas, vUv);
            if (tex.a < 0.05) discard;
            FragColor = vec4(tex.rgb * uColor.rgb, tex.a * uColor.a);
        }
        """;

    public static final String TEXT_VERTEX = """
        #version 330 core
        layout (location = 0) in vec2 aPos;
        layout (location = 1) in vec2 aUv;
        uniform vec2 uScreenSize;
        out vec2 vUv;
        void main() {
            // Convert pixel (x,y from top-left) to clip space (-1..1, y flipped).
            float x = (aPos.x / uScreenSize.x) * 2.0 - 1.0;
            float y = 1.0 - (aPos.y / uScreenSize.y) * 2.0;
            gl_Position = vec4(x, y, 0.0, 1.0);
            vUv = aUv;
        }
        """;

    public static final String TEXT_FRAGMENT = """
        #version 330 core
        in vec2 vUv;
        uniform sampler2D uFont;
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() {
            float a = texture(uFont, vUv).r;
            if (a < 0.01) discard;
            FragColor = vec4(uColor.rgb, uColor.a * a);
        }
        """;

    public static final String UI_VERTEX = """
        #version 330 core
        layout (location = 0) in vec2 aPos;
        layout (location = 1) in vec2 aUv;
        uniform vec2 uScreenSize;
        out vec2 vUv;
        void main() {
            float x = (aPos.x / uScreenSize.x) * 2.0 - 1.0;
            float y = 1.0 - (aPos.y / uScreenSize.y) * 2.0;
            gl_Position = vec4(x, y, 0.0, 1.0);
            vUv = aUv;
        }
        """;

    public static final String UI_FRAGMENT = """
        #version 330 core
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform int uUseTexture;
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() {
            vec4 c = uColor;
            if (uUseTexture == 1) {
                vec4 t = texture(uTex, vUv);
                if (t.a < 0.1) discard;
                c = t * uColor;
            }
            FragColor = c;
        }
        """;

    public static final String SKY_VERTEX = """
        #version 330 core
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec2 aUv;
        uniform mat4 uProjection;
        uniform mat4 uView;
        out vec2 vUv;
        void main() {
            gl_Position = uProjection * uView * vec4(aPos, 1.0);
            vUv = aUv;
        }
        """;

    public static final String SKY_FRAGMENT = """
        #version 330 core
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() {
            vec4 t = texture(uTex, vUv);
            if (t.a < 0.01) discard;
            FragColor = vec4(t.rgb * uColor.rgb, t.a * uColor.a);
        }
        """;
}
