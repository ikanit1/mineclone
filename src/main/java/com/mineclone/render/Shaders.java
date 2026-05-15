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
        centroid out vec2 vUv;
        out float vLight;
        out float vFogDist;
        out float vBlockLight;
        void main() {
            vec4 worldPos = uModel * vec4(aPos, 1.0);
            vec4 viewPos = uView * worldPos;
            gl_Position = uProjection * viewPos;
            vUv = aUv;
            vLight = aLight;
            vFogDist = length(viewPos.xyz);
            vBlockLight = aBlockLight;
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
        out vec4 FragColor;
        void main() {
            vec4 tex = texture(uAtlas, vUv);
            if (tex.a < 0.1) discard;
            float combined = max(vLight * uDaylight, vBlockLight);
            float shaped = pow(max(uAmbient, combined), 0.75);
            vec3 lit = tex.rgb * shaped;
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
        void main() {
            vec3 world = uCenter + (uRight * aCorner.x + uUp * aCorner.y) * uSize;
            gl_Position = uProjection * uView * vec4(world, 1.0);
        }
        """;

    public static final String PARTICLE_FRAGMENT = """
        #version 330 core
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() { FragColor = uColor; }
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
}
