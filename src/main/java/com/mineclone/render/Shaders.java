package com.mineclone.render;

/**
 * Все GLSL-программы движка.
 *
 * Конвейер света устроен так: мир считается в ЛИНЕЙНОМ пространстве
 * (альбедо из атласа разгамливается на входе), копится в HDR-буфер
 * {@link PostProcess}, и только в композите проходит тонемап ACES и обратную
 * гамму. Поэтому любой шейдер, который пишет в сцену, обязан отдавать линейный
 * цвет — для этого у всех «мировых» программ есть {@code uLinearOut}: 1.0 —
 * писать линейно (дальше будет пост), 0.0 — самому сделать тонемап и гамму
 * (аварийный путь, если HDR-буфер не собрался на драйвере).
 *
 * Нормали в чанках и мобах не лежат в вершинах: грани воксельные и плоские,
 * поэтому {@code normalize(cross(dFdx(world), dFdy(world)))} даёт точную
 * нормаль грани бесплатно и без правок мешера.
 */
public final class Shaders {
    private Shaders() {}

    private static final String VER = "#version 330 core\n";

    /** Гамма и тонемап — общее для всех программ, которые пишут цвет. */
    private static final String LIB_COLOR = """
        const float GAMMA = 2.2;
        vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(GAMMA)); }
        vec3 toSrgb(vec3 c)   { return pow(max(c, vec3(0.0)), vec3(1.0 / GAMMA)); }
        vec3 tonemapACES(vec3 x) {
            const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }
        vec3 finishColor(vec3 c, float linearOut) {
            if (linearOut > 0.5) return c;
            return toSrgb(tonemapACES(c));
        }
        """;

    /**
     * Два каскада теней (ближний резкий, дальний широкий) с PCF.
     * Сравнение делает железо: sampler2DShadow + GL_LINEAR = бесплатный 2x2,
     * поверх него 3x3 или 5x5 отсчётов по качеству.
     */
    private static final String LIB_SHADOW = """
        uniform sampler2DShadow uShadow0;
        uniform sampler2DShadow uShadow1;
        uniform mat4  uShadowMat0;
        uniform mat4  uShadowMat1;
        uniform float uShadowTexel;     // 1 / размер карты
        uniform float uShadowSplit;     // дистанция стыка каскадов
        uniform float uShadowFar;       // где тени растворяются совсем
        uniform float uShadowBias0;
        uniform float uShadowBias1;
        uniform float uShadowStrength;  // 0 — теней нет
        uniform int   uShadowTaps;      // 1 -> 3x3, 2 -> 5x5

        float pcf(sampler2DShadow map, vec4 sc, float texel) {
            vec3 p = sc.xyz / sc.w;
            p = p * 0.5 + 0.5;
            if (p.z >= 1.0 || p.z <= 0.0) return 1.0;
            float sum = 0.0;
            if (uShadowTaps >= 2) {
                for (int y = -2; y <= 2; y++)
                    for (int x = -2; x <= 2; x++)
                        sum += texture(map, vec3(p.xy + vec2(x, y) * texel, p.z));
                return sum / 25.0;
            }
            for (int y = -1; y <= 1; y++)
                for (int x = -1; x <= 1; x++)
                    sum += texture(map, vec3(p.xy + vec2(x, y) * texel, p.z));
            return sum / 9.0;
        }

        /** 1.0 — полностью освещено, 0.0 — целиком в тени. */
        float shadowFactor(vec3 world, vec3 n, float viewDist, float ndl) {
            if (uShadowStrength <= 0.001 || ndl <= 0.0) return 1.0;
            // Смещение вдоль нормали убирает shadow acne на косом свете:
            // чем острее угол, тем дальше уводим точку от поверхности.
            float slope = 1.0 + 2.0 * clamp(1.0 - ndl, 0.0, 1.0);
            float lo = uShadowSplit - 4.0, hi = uShadowSplit + 4.0;
            float s0 = 1.0, s1 = 1.0;
            if (viewDist < hi)
                s0 = pcf(uShadow0, uShadowMat0 * vec4(world + n * (uShadowBias0 * slope), 1.0), uShadowTexel);
            if (viewDist > lo)
                s1 = pcf(uShadow1, uShadowMat1 * vec4(world + n * (uShadowBias1 * slope), 1.0), uShadowTexel);
            float s = mix(s0, s1, clamp((viewDist - lo) / (hi - lo), 0.0, 1.0));
            float fade = 1.0 - smoothstep(uShadowFar * 0.7, uShadowFar, viewDist);
            return 1.0 - (1.0 - s) * uShadowStrength * fade;
        }
        """;

    /** Направленный свет, полусферный ambient, факелы, туман. */
    private static final String LIB_LIGHT = """
        uniform vec3  uCamPos;
        uniform vec3  uLightDir;      // направление НА источник (солнце/луна)
        uniform vec3  uLightColor;    // HDR-яркость источника
        uniform vec3  uSkyLight;      // верхняя полусфера ambient
        uniform vec3  uGroundLight;   // нижняя полусфера (отражённый свет земли)
        uniform vec3  uTorchColor;    // цвет блочного света
        uniform vec3  uAmbientColor;  // пол освещения — чтобы пещеры не были чёрными
        uniform vec3  uFogColor;
        uniform vec3  uFogSunColor;   // подсвет тумана в сторону солнца
        uniform float uFogStart;
        uniform float uFogEnd;
        uniform float uBrightness;
        uniform float uTime;
        uniform float uLinearOut;
        uniform vec3  uHeightFogColor;
        uniform float uHeightFogDensity;  // 0 — низового тумана нет
        uniform float uHeightFogTop;      // выше этой Y тумана не бывает
        uniform float uHeightFogDepth;    // на скольких блоках он набирает плотность
        uniform vec3  uPointPos;      // источник в руке игрока, мировые координаты
        uniform vec3  uPointColor;    // нулевой цвет = источника нет
        uniform float uPointRadius;   // в блоках; за радиусом вклад ровно ноль
        uniform vec3  uBouncePos;
        uniform vec3  uBounceColor;
        uniform float uBounceRadius;

        /** Направленная яркость грани — тот же профиль, что печёт мешер. */
        float faceShade(vec3 n) {
            float ax = abs(n.x), ay = abs(n.y), az = abs(n.z);
            if (ay >= ax && ay >= az) return n.y > 0.0 ? 1.0 : 0.65;
            return ax >= az ? 0.80 : 0.88;
        }

        /**
         * Плоская нормаль грани из экранных производных мировой позиции.
         *
         * ВАЖНО: звать только ДО discard. Производные берутся по квадру 2x2,
         * и если часть фрагментов квадра уже отброшена, результат по спецификации
         * не определён — на листве это давало NaN и чёрные деревья.
         */
        vec3 faceNormal(vec3 world, vec3 toCam) {
            vec3 n = cross(dFdx(world), dFdy(world));
            float len = length(n);
            if (len < 1e-9) return toCam;   // вырожденный квад — светим как к камере
            n /= len;
            return dot(n, toCam) < 0.0 ? -n : n;
        }

        /** Факел дышит: медленный шум +-7% — иначе свет выглядит мёртвым. */
        float torchFlicker(vec3 world) {
            float t = uTime * 2.7 + dot(floor(world), vec3(0.7, 1.3, 2.1));
            return 0.93 + 0.07 * (sin(t) * 0.6 + sin(t * 2.3) * 0.4);
        }

        /**
         * Точечный источник в руке. Стен не знает, поэтому от протечки сквозь
         * тонкую стену спасает только N.L: у дальней грани нормаль смотрит
         * от света, и вклад обнуляется сам.
         */
        vec3 pointLight(vec3 world, vec3 n) {
            vec3 d = uPointPos - world;
            float dist = length(d);
            if (dist >= uPointRadius) return vec3(0.0);
            float att = pow(1.0 - dist / uPointRadius, 1.6);
            // Всенаправленная доля: блочный свет в MC не знает про нормали,
            // и без неё стены тоннеля, стоящие к игроку ребром, остаются
            // чёрными — идти по такой пещере невозможно.
            float ndl = 0.35 + 0.65 * max(dot(n, d / max(dist, 1e-4)), 0.0);
            float flicker = 0.92 + 0.08 * sin(uTime * 7.3);
            return uPointColor * ndl * att * flicker;
        }

        /** Цветной вторичный свет от ближайшего яркого вокселя. */
        vec3 voxelBounce(vec3 world, vec3 n) {
            vec3 d = uBouncePos - world;
            float dist = length(d);
            if (dist >= uBounceRadius || dot(uBounceColor, uBounceColor) < 1e-6) return vec3(0.0);
            float att = pow(1.0 - dist / uBounceRadius, 2.0);
            float wrap = 0.35 + 0.65 * max(dot(n, d / max(dist, 1e-4)), 0.0);
            return uBounceColor * att * wrap;
        }

        /**
         * Низовой туман: стелется по низинам и тает с высотой.
         *
         * Честное интегрирование плотности вдоль луча здесь не нужно — на
         * глаз хватает среднего между плотностью у камеры и у фрагмента.
         * Зато это два вычисления вместо цикла.
         */
        float heightFogAt(float y) {
            return clamp((uHeightFogTop - y) / max(uHeightFogDepth, 0.001), 0.0, 1.0);
        }

        vec3 applyHeightFog(vec3 lit, vec3 world, float viewDist) {
            if (uHeightFogDensity <= 0.0) return lit;
            float d = 0.5 * (heightFogAt(uCamPos.y) + heightFogAt(world.y));
            float f = 1.0 - exp(-viewDist * uHeightFogDensity * d);
            return mix(lit, uHeightFogColor, clamp(f, 0.0, 1.0));
        }

        vec3 applyFog(vec3 lit, vec3 world, float viewDist) {
            float f = smoothstep(uFogStart, uFogEnd, viewDist);
            if (f <= 0.0) return lit;
            vec3 viewDir = normalize(world - uCamPos);
            float toward = max(dot(viewDir, uLightDir), 0.0);
            vec3 fog = uFogColor + uFogSunColor * pow(toward, 5.0);
            return mix(lit, fog, f);
        }
        """;

    /**
     * Качание листвы на ветру. Лист узнаётся по тайлу атласа (7, «leaves») —
     * отдельного атрибута в вершине нет, а заводить его ради одного блока
     * значило бы переписать формат VBO всех чанков.
     *
     * Смещение — гладкая функция мировой позиции: соседние листовые блоки
     * делят вершины и качаются вместе, без щелей между кубами. Амплитуда в
     * сотые доли блока — крона шевелится, а не пляшет; порыв идёт медленной
     * волной по лесу, а не синхронно по всем деревьям.
     */
    private static final String LIB_SWAY = """
        uniform float uWindSway;   // 0 — штиль и все непрозрачные меши без листвы
        uniform vec2  uWindDir;    // единичное направление ветра по XZ
        uniform vec3  uInteractorPos;
        uniform float uInteractorRadius;
        uniform vec3  uMobInteractorPos;
        uniform float uMobInteractorRadius;

        vec3 bendAway(vec3 world, vec3 actor, float radius) {
            vec2 delta = world.xz - actor.xz;
            float dist = length(delta);
            float vertical = 1.0 - smoothstep(0.0, 2.4, abs(world.y - actor.y));
            float touch = (1.0 - smoothstep(radius * 0.25, radius, dist)) * vertical;
            if (touch <= 0.0) return world;
            vec2 away = dist > 0.001 ? delta / dist : vec2(1.0, 0.0);
            world.xz += away * touch * touch * 0.28;
            world.y -= touch * 0.055;
            return world;
        }

        vec3 swayLeaves(vec3 world, vec2 uv, float time) {
            vec2 tile = floor(uv * 16.0);
            if (tile.x != 7.0 || tile.y != 0.0) return world;
            if (uWindSway > 0.0) {
                float phase = dot(world, vec3(0.37, 0.21, 0.43));
                float wave = sin(time * 0.42 + world.x * 0.045 + world.z * 0.038);
                float gust = 0.55 + 0.45 * wave;
                float sway = sin(time * 1.7 + phase) * 0.65 + sin(time * 3.3 + phase * 1.9) * 0.35;
                float flutter = sin(time * 7.1 + phase * 4.3) * 0.25;
                world.xz += uWindDir * (sway * gust * 0.055 + flutter * 0.012) * uWindSway;
                world.y  += sin(time * 2.4 + phase * 1.3) * 0.018 * uWindSway * gust;
            }
            // Контактная деформация локальна и не зависит от ветра: куст
            // раздвигается даже в полный штиль. Второй интерактор — ближайший
            // крупный моб, чтобы стадо оставляло за собой живую волну.
            world = bendAway(world, uInteractorPos, uInteractorRadius);
            world = bendAway(world, uMobInteractorPos, uMobInteractorRadius);
            return world;
        }
        """;

    // ------------------------------------------------------------------
    //  Чанки
    // ------------------------------------------------------------------

    public static final String CHUNK_VERTEX = VER + LIB_SWAY + """
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec2 aUv;
        layout (location = 4) in vec3 aRepeat;
        out vec3 vRepeat;
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
        out vec3  vWorld;
        void main() {
            vRepeat = aRepeat;
            vec4 worldPos = uModel * vec4(aPos, 1.0);
            worldPos.xyz = swayLeaves(worldPos.xyz, aUv, uTime);
            vec4 viewPos = uView * worldPos;
            gl_Position = uProjection * viewPos;
            // aBlockLight encoding:
            //   0..1   : regular block light
            //   10..11 : static water (source, no animation)
            //   20..51 : animated flow, packed as (20 + flowDir*10 + blRaw)
            //            flowDir in {0=+Z south, 1=+X east, 2=-Z north, 3=-X west}
            bool isWater = aBlockLight > 5.0;
            bool isFlow  = aBlockLight > 15.0;
            float blRaw  = aBlockLight;
            if (isFlow) {
                float v = aBlockLight - 20.0;            // 0..31
                float flowDirF = floor(v / 10.0);        // 0..3
                blRaw = v - flowDirF * 10.0;             // 0..1
                int flowDir = int(flowDirF);

                // Cycle frames at 6 fps over 16 frames ~ 2.7 s loop
                int frame = int(mod(floor(uTime * 6.0), 16.0));
                int targetTile = 17 + frame;
                int col = targetTile - (targetTile / 16) * 16;
                int row = targetTile / 16;
                float ts = 1.0 / 16.0;
                vec2 tileBase = floor(aUv / ts) * ts;     // base of source tile (tile 8)
                vec2 localUv  = aUv - tileBase;           // offset within tile (preserves inset)

                // Rotate localUv around tile centre by flowDir * 90 degrees.
                // Default (dir 0 = +Z south) keeps the texture as-drawn:
                // the bands in the atlas flow along +V (= world -Z = north),
                // which means peaks travel south -> matches flowDir 0.
                // 90-degree rotations stay perfectly within tile bounds.
                vec2 c = localUv - vec2(ts * 0.5);
                if (flowDir == 1)      c = vec2( c.y, -c.x); //  90 CW  -> +X east
                else if (flowDir == 2) c = vec2(-c.x, -c.y); // 180     -> -Z north
                else if (flowDir == 3) c = vec2(-c.y,  c.x); //  90 CCW -> -X west
                localUv = c + vec2(ts * 0.5);

                vec2 newBase = vec2(float(col), float(row)) * ts;
                vUv = newBase + localUv;
            } else if (isWater) {
                blRaw = aBlockLight - 10.0;
                // Gentle ripple animation for still water (4 fps - slower than flow)
                int frame = int(mod(floor(uTime * 4.0), 16.0));
                int targetTile = 17 + frame;
                int col = targetTile - (targetTile / 16) * 16;
                int row = targetTile / 16;
                float ts = 1.0 / 16.0;
                vec2 tileBase = floor(aUv / ts) * ts;
                vec2 localUv  = aUv - tileBase;
                vec2 newBase  = vec2(float(col), float(row)) * ts;
                vUv = newBase + localUv;
            } else {
                vUv = aUv;
            }
            vLight = aLight;
            vFogDist = length(viewPos.xyz);
            vBlockLight = blRaw;
            vWorld = worldPos.xyz;
        }
        """;

    public static final String CHUNK_FRAGMENT = VER + LIB_COLOR + LIB_SHADOW + LIB_LIGHT + """
        centroid in vec2 vUv;
        in float vLight;        // AO * faceShade * доля неба (печёт мешер)
        in float vFogDist;
        in float vBlockLight;   // AO * блочный свет
        in vec3  vWorld;
        uniform sampler2D uAtlas;
        uniform sampler2DArray uBlockArray;
        in vec3 vRepeat;
        out vec4 FragColor;
        void main() {
            // Нормаль и выборка атласа — до discard: обе опираются на производные.
            vec3 toCam = uCamPos - vWorld;
            float dist = length(toCam);
            vec3 V = toCam / max(dist, 1e-4);
            vec3 N = faceNormal(vWorld, V);

            vec4 tex = (vRepeat.z > 0.5 ? texture(uBlockArray, vec3(vRepeat.xy, vRepeat.z - 1.0)) : texture(uAtlas, vUv));
            if (tex.a < 0.1) discard;
            vec3 albedo = toLinear(tex.rgb);

            // Мешер уже умножил долю неба на направленную яркость грани —
            // делим обратно, чтобы вместо неё встал честный N.L от солнца.
            float skyVis = clamp(vLight / faceShade(N), 0.0, 1.0);
            float blockVis = clamp(vBlockLight, 0.0, 1.0);

            float ndl = max(dot(N, uLightDir), 0.0);
            float shade = shadowFactor(vWorld, N, vFogDist, ndl);
            float direct = ndl * shade * skyVis;

            vec3 hemi = mix(uGroundLight, uSkyLight, N.y * 0.5 + 0.5) * skyVis;
            vec3 torch = uTorchColor * pow(blockVis, 1.4) * torchFlicker(vWorld);

            // Блик: на песке и снеге читается как слюда, на камне почти не виден.
            vec3 H = normalize(uLightDir + V);
            float spec = pow(max(dot(N, H), 0.0), 42.0) * 0.16 * shade * skyVis;

            vec3 lit = albedo * (uLightColor * direct + hemi + torch + uAmbientColor
                                 + pointLight(vWorld, N) + voxelBounce(vWorld, N));
            lit += uLightColor * spec;

            // Тонкие материалы пропускают встречный свет. Тип берём из
            // тайла атласа: отдельного material-id в старом VBO нет.
            vec2 tile = floor(vUv * 16.0);
            bool foliage = tile.x == 7.0 && tile.y == 0.0;
            bool snow = (tile.x == 8.0 && tile.y == 3.0)
                     || (tile.x == 15.0 && tile.y == 2.0);
            if (foliage || snow) {
                float back = pow(max(dot(-N, uLightDir), 0.0), 1.6) * skyVis;
                vec3 transmit = foliage ? vec3(0.22, 0.65, 0.16)
                                        : vec3(0.66, 0.82, 1.0);
                lit += albedo * transmit * uLightColor * back * (foliage ? 0.42 : 0.20);
            }
            lit *= uBrightness;

            lit = applyHeightFog(lit, vWorld, vFogDist);
            FragColor = vec4(finishColor(applyFog(lit, vWorld, vFogDist), uLinearOut), tex.a);
        }
        """;

    // ------------------------------------------------------------------
    //  Вода — отдельная программа: рябь в нормалях, френель, солнечная дорожка
    // ------------------------------------------------------------------

    public static final String WATER_FRAGMENT = VER + LIB_COLOR + LIB_SHADOW + LIB_LIGHT + """
        centroid in vec2 vUv;
        in float vLight;
        in float vFogDist;
        in float vBlockLight;
        in vec3  vWorld;
        uniform sampler2D uAtlas;
        uniform sampler2D uScene;
        uniform float uSsrOn;
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform vec3 uWaterTint;
        out vec4 FragColor;

        /** Сумма косых синусов — дешёвый капиллярный шум без текстуры. */
        vec3 waveNormal(vec2 p, float t) {
            float dx = 0.0, dz = 0.0;
            dx +=  cos(p.x * 1.70 + t * 1.10) * 1.70 * 0.055;
            dz += -cos(p.y * 2.30 - t * 0.90) * 2.30 * 0.045;
            float d = (p.x + p.y) * 1.13 + t * 1.70;
            dx += cos(d) * 1.13 * 0.040;
            dz += cos(d) * 1.13 * 0.040;
            d = (p.x - p.y * 1.70) * 2.90 - t * 2.30;
            dx += cos(d) * 2.90 * 0.016;
            dz -= cos(d) * 4.93 * 0.008;
            return normalize(vec3(-dx, 1.0, -dz));
        }

        void main() {
            vec3 toCam = uCamPos - vWorld;
            vec3 V = toCam / max(length(toCam), 1e-4);
            vec3 N = faceNormal(vWorld, V);

            vec4 tex = texture(uAtlas, vUv);
            if (tex.a < 0.02) discard;
            vec3 albedo = toLinear(tex.rgb) * uWaterTint;

            float skyVis = clamp(vLight, 0.0, 1.0);
            bool horizontal = abs(N.y) > 0.5;
            if (horizontal) {
                vec3 w = waveNormal(vWorld.xz, uTime);
                N = normalize(vec3(w.x, N.y > 0.0 ? w.y : -w.y, w.z));
            }

            float ndl = max(dot(N, uLightDir), 0.0);
            float shade = shadowFactor(vWorld, N, vFogDist, ndl);

            // Френель: в упор вода прозрачная, вскользь — зеркало неба.
            float fres = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 4.0);
            fres = mix(0.06, 1.0, fres) * (horizontal ? 1.0 : 0.35);

            vec3 H = normalize(uLightDir + V);
            float spec = pow(max(dot(N, H), 0.0), 220.0) * 3.4 * shade * skyVis;

            vec3 body = albedo * (uLightColor * ndl * shade * skyVis * 0.55
                                  + mix(uGroundLight, uSkyLight, 0.85) * skyVis
                                  + uTorchColor * pow(clamp(vBlockLight, 0.0, 1.0), 1.4)
                                  + uAmbientColor
                                  + pointLight(vWorld, N));
            vec3 reflection = uSkyLight * 1.6 + uLightColor * 0.12;
            if (uSsrOn > 0.5 && horizontal) {
                // Проецируем несколько точек отражённого луча в уже снятый
                // цвет непрозрачной сцены и берём первую экранную пробу.
                vec3 R = reflect(-V, N);
                vec4 rp = uProjection * uView * vec4(vWorld + R * 5.0, 1.0);
                vec2 ruv = rp.xy / max(rp.w, 1e-4) * 0.5 + 0.5;
                if (all(greaterThan(ruv, vec2(0.01))) && all(lessThan(ruv, vec2(0.99))))
                    reflection = mix(reflection, texture(uScene, ruv).rgb, 0.68);
            }
            vec3 lit = mix(body, reflection, fres * skyVis * 0.75) + uLightColor * spec;
            lit *= uBrightness;

            lit = applyHeightFog(lit, vWorld, vFogDist);
            float alpha = clamp(mix(tex.a, 1.0, fres * 0.65), 0.0, 1.0);
            FragColor = vec4(finishColor(applyFog(lit, vWorld, vFogDist), uLinearOut), alpha);
        }
        """;

    // ------------------------------------------------------------------
    //  Проход карты теней
    // ------------------------------------------------------------------

    public static final String SHADOW_VERTEX = VER + LIB_SWAY + """
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec2 aUv;
        layout (location = 4) in vec3 aRepeat;
        out vec3 vRepeat;
        uniform mat4 uLightSpace;
        uniform mat4 uModel;
        uniform float uTime;
        out vec2 vUv;
        void main() {
            vRepeat = aRepeat;
            vUv = aUv;
            // Тень кроны качается вместе с кроной — иначе пятна света под
            // деревом стоят, а листья над ним шевелятся.
            vec4 world = uModel * vec4(aPos, 1.0);
            world.xyz = swayLeaves(world.xyz, aUv, uTime);
            gl_Position = uLightSpace * world;
        }
        """;

    // ------------------------------------------------------------------
    //  Осадки на видеокарте
    // ------------------------------------------------------------------

    /**
     * Снежинка или струя дождя из одного инстанса: семя (xyz в коробке, w —
     * крупность) плюс время. Коробка привязана к миру и сворачивается вокруг
     * камеры, крыши отсекаются картой высот.
     */
    public static final String PRECIP_VERTEX = VER + """
        layout (location = 0) in vec2 aCorner;
        layout (location = 1) in vec4 aSeed;
        uniform mat4  uViewProj;
        uniform vec3  uCamPos;
        uniform vec3  uCamRight;
        uniform vec3  uCamUp;
        uniform float uTime;
        uniform float uSnow;      // 1 — снег, 0 — дождь
        uniform float uStorm;
        uniform vec2  uWind;
        uniform vec3  uBox;
        uniform sampler2D uHeight;
        uniform vec2  uHeightOrigin;
        uniform float uHeightSize;
        out vec2  vUv;
        out float vFade;
        void main() {
            float big = aSeed.w;
            bool snow = uSnow > 0.5;
            vec3 vel;
            if (snow) {
                // Крупные хлопья падают быстрее и сносятся сильнее мелких:
                // разная скорость и есть глубина снегопада.
                float fall = mix(0.8, 2.1, big) + uStorm * 2.2;
                float carry = mix(0.55, 1.0, big) * (1.0 + uStorm * 0.8);
                vel = vec3(uWind.x * carry, -fall, uWind.y * carry);
            } else {
                vel = vec3(uWind.x * 0.35, -13.0 - big * 5.0, uWind.y * 0.35);
            }
            vec3 p = aSeed.xyz * uBox + vel * uTime;
            if (snow) {
                float ph = aSeed.x * 61.0 + aSeed.z * 23.0 + aSeed.y * 11.0;
                float turb = 0.35 + uStorm * 1.1;
                p.x += (sin(uTime * (0.9 + big * 0.7) + ph) * 0.8 + sin(uTime * 2.1 + ph * 1.7) * 0.3) * turb;
                p.z += (cos(uTime * (0.8 + big * 0.6) + ph * 1.3) * 0.8 + cos(uTime * 1.9 + ph * 0.7) * 0.3) * turb;
                p.y += sin(uTime * 1.3 + ph * 2.1) * 0.25 * turb;
            }
            vec3 origin = uCamPos - uBox * 0.5;
            p = mod(p - origin, uBox) + origin;

            vec2 hc = (floor(p.xz) - uHeightOrigin + 0.5) / uHeightSize;
            bool inside = hc.x > 0.0 && hc.y > 0.0 && hc.x < 1.0 && hc.y < 1.0;
            float roof = texture(uHeight, hc).r;
            float visible = (inside && p.y >= roof) ? 1.0 : 0.0;

            vec3 d = p - uCamPos;
            float dist = length(d);
            vFade = visible
                  * smoothstep(0.3, 1.4, dist)
                  * (1.0 - smoothstep(uBox.x * 0.36, uBox.x * 0.5, length(d.xz)))
                  * (1.0 - smoothstep(uBox.y * 0.36, uBox.y * 0.5, abs(d.y)));

            vec3 world;
            if (snow) {
                float size = mix(0.05, 0.15, big * big);
                vec3 axis = normalize(vel);
                // В метель хлопья вытягиваются вдоль полёта: так читается скорость.
                float stretch = 1.0 + uStorm * (1.5 + big * 2.0);
                vec3 side = normalize(cross(axis, normalize(uCamPos - p) + vec3(1e-4)));
                float ang = uTime * (0.6 + aSeed.y) + aSeed.x * 6.2831;
                vec2 c = vec2(cos(ang) * aCorner.x - sin(ang) * aCorner.y,
                              sin(ang) * aCorner.x + cos(ang) * aCorner.y);
                vec3 billboard = (uCamRight * c.x + uCamUp * c.y) * size;
                vec3 streak = (side * aCorner.x + axis * aCorner.y * stretch) * size;
                world = p + mix(billboard, streak, clamp(uStorm * 1.4, 0.0, 1.0));
            } else {
                vec3 axis = normalize(vel);
                vec3 side = normalize(cross(axis, normalize(uCamPos - p) + vec3(1e-4)));
                float len = 0.6 + big * 0.5;
                float width = 0.014 + big * 0.010;
                world = p + side * aCorner.x * width + axis * aCorner.y * len;
            }
            vUv = aCorner + 0.5;
            gl_Position = vFade > 0.001 ? uViewProj * vec4(world, 1.0) : vec4(2.0, 2.0, 2.0, 1.0);
        }
        """;

    public static final String PRECIP_FRAGMENT = VER + LIB_COLOR + """
        in vec2  vUv;
        in float vFade;
        uniform float uSnow;
        uniform vec3  uColor;
        uniform float uAlpha;
        uniform float uLinearOut;
        out vec4 FragColor;
        void main() {
            vec2 q = vUv - 0.5;
            float a;
            if (uSnow > 0.5) {
                float d = length(q) * 2.0;
                float star = 0.78 + 0.22 * cos(atan(q.y, q.x) * 6.0);
                a = 1.0 - smoothstep(0.30 * star, 0.95 * star, d);
            } else {
                a = (1.0 - abs(q.x) * 2.0) * smoothstep(0.0, 0.3, vUv.y) * (1.0 - smoothstep(0.7, 1.0, vUv.y));
            }
            a *= vFade * uAlpha;
            if (a < 0.01) discard;
            FragColor = vec4(uLinearOut > 0.5 ? uColor : toSrgb(tonemapACES(uColor)), a);
        }
        """;

    /** Мобы кастуют тень тем же кубом, что и рисуются: pos + faceId + угол. */
    public static final String SHADOW_MOB_VERTEX = VER + """
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in float aFace;
        layout (location = 2) in vec2 aCorner;
        uniform mat4 uLightSpace;
        uniform mat4 uModel;
        uniform vec2 uUvFront;
        uniform vec2 uUvSide;
        uniform vec2 uUvTop;
        uniform vec2 uTileSize;
        out vec2 vUv;
        out vec3 vRepeat;
        void main() {
            vRepeat = vec3(0.0);
            vec2 base = aFace < 0.5 ? uUvFront : (aFace < 1.5 ? uUvSide : uUvTop);
            vUv = base + aCorner * uTileSize;
            gl_Position = uLightSpace * uModel * vec4(aPos, 1.0);
        }
        """;

    public static final String SHADOW_FRAGMENT = VER + """
        in vec2 vUv;
        uniform sampler2D uAtlas;
        uniform sampler2DArray uBlockArray;
        in vec3 vRepeat;
        void main() {
            // Листва и кресты дырявые: без alpha-теста их тень — сплошной куб.
            if ((vRepeat.z > 0.5 ? texture(uBlockArray, vec3(vRepeat.xy, vRepeat.z - 1.0)) : texture(uAtlas, vUv)).a < 0.5) discard;
        }
        """;

    // ------------------------------------------------------------------
    //  Небесный купол
    // ------------------------------------------------------------------

    public static final String SKYDOME_VERTEX = VER + """
        uniform mat4 uInvViewProj;
        out vec3 vRay;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vec2 ndc = p * 2.0 - 1.0;
            gl_Position = vec4(ndc, 1.0, 1.0);
            vec4 far  = uInvViewProj * vec4(ndc,  1.0, 1.0);
            vec4 near = uInvViewProj * vec4(ndc, -1.0, 1.0);
            vRay = far.xyz / far.w - near.xyz / near.w;
        }
        """;

    public static final String SKYDOME_FRAGMENT = VER + LIB_COLOR + """
        in vec3 vRay;
        uniform vec3  uZenith;
        uniform vec3  uHorizon;
        uniform vec3  uGround;
        uniform vec3  uSunGlow;
        uniform vec3  uLightDir;
        uniform float uLinearOut;
        uniform float uWeather;     // облачность 0..1 — гасит гало
        uniform float uAurora;      // 0 — сияния нет, цикл не считается
        uniform float uTime;
        uniform vec3  uHaze;        // цвет мглы в метель и ливень
        uniform float uHazeMix;     // 0 — ясно, 1 — неба не видно
        out vec4 FragColor;

        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        float vnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
                       mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
        }

        float cloudFbm(vec3 p) {
            float n = 0.0, a = 0.55;
            for (int i = 0; i < 4; i++) {
                n += vnoise(p.xz + p.y * vec2(0.31, -0.27)) * a;
                p = p * 2.03 + vec3(7.1, 2.7, 11.3);
                a *= 0.48;
            }
            return n;
        }

        vec4 volumetricClouds(vec3 d) {
            if (uWeather < 0.025 || d.y < 0.035) return vec4(0.0);
            vec3 acc = vec3(0.0);
            float trans = 1.0;
            vec2 drift = vec2(uTime * 0.0045, uTime * 0.0022);
            // Восемь высотных срезов дают объём и параллакс без 3D-текстуры.
            for (int i = 0; i < 8; i++) {
                float k = float(i) / 7.0;
                float height = 1.55 + k * 0.48;
                vec3 p = d / max(d.y, 0.035) * height;
                p.xz = p.xz * 0.43 + drift + k * vec2(0.17, -0.11);
                float shape = cloudFbm(p);
                float density = smoothstep(0.50 - uWeather * 0.18, 0.78, shape);
                density *= smoothstep(0.035, 0.16, d.y) * uWeather * 0.32;
                float silver = pow(max(dot(d, uLightDir), 0.0), 10.0);
                vec3 cc = mix(vec3(0.30, 0.34, 0.39), vec3(0.92, 0.94, 0.98),
                              clamp(0.35 + d.y * 0.45 + silver * 0.5, 0.0, 1.0));
                acc += trans * density * cc;
                trans *= 1.0 - density;
            }
            return vec4(acc, 1.0 - trans);
        }

        /**
         * Северное сияние: лента-занавес на «потолке» неба.
         *
         * Луч пересекается со стопкой горизонтальных плоскостей на разной
         * высоте; на каждой лежит одна и та же извилистая лента. Для глаза
         * соседние плоскости сдвигают ленту по вертикали — так из восьми
         * дешёвых срезов складываются вертикальные лучи-складки, без честного
         * марша по объёму. Низ занавеса зелёный, верх уходит в фиолетовый.
         */
        vec3 aurora(vec3 d) {
            if (uAurora <= 0.001 || d.y <= 0.015) return vec3(0.0);
            vec3 acc = vec3(0.0);
            const int LAYERS = 10;
            for (int i = 0; i < LAYERS; i++) {
                float k = float(i) / float(LAYERS - 1);
                float height = 1.0 + k * 0.9;
                // Лента тянется с запада на восток (вдоль Z) и висит над
                // северным горизонтом: север этого мира — это −X (восток — −Z,
                // там встаёт солнце). В зените она ушла бы за спину любому,
                // кто смотрит вдаль.
                vec2 p = vec2(d.z, d.x) / d.y * height * 0.55;
                float t = uTime * 0.05;
                float curve = (p.y + 1.6)
                    - 0.55 * sin(p.x * 0.55 + t * 1.3)
                    - 0.25 * sin(p.x * 1.35 - t * 0.9 + 1.7)
                    - 0.45 * (vnoise(vec2(p.x * 0.45 + t, 3.7)) - 0.5);
                float ribbon = exp(-curve * curve * 5.0);
                // Складки занавеса: быстрый шум вдоль ленты, медленно ползущий.
                float folds = 0.45 + 0.55 * vnoise(vec2(p.x * 7.0 + t * 6.0, k * 2.0));
                float fade = 1.0 - k;
                vec3 col = mix(vec3(0.10, 1.00, 0.42), vec3(0.62, 0.22, 1.00), smoothstep(0.35, 1.0, k));
                acc += col * ribbon * folds * fade * fade;
            }
            // У горизонта сияние тонет в дымке, у зенита — редеет.
            float elev = smoothstep(0.02, 0.22, d.y) * (1.0 - smoothstep(0.75, 1.0, d.y) * 0.6);
            return acc * elev * uAurora * 0.55;
        }

        void main() {
            vec3 d = normalize(vRay);
            float h = d.y;
            vec3 col = mix(uHorizon, uZenith, pow(clamp(h, 0.0, 1.0), 0.42));
            col = mix(col, uGround, smoothstep(0.0, -0.22, h));
            // Гало вокруг солнца: широкое рассеяние + плотное ядро.
            float sd = max(dot(d, uLightDir), 0.0);
            col += uSunGlow * (pow(sd, 6.0) * 0.30 + pow(sd, 90.0) * 1.35) * (1.0 - uWeather * 0.8);
            col += aurora(d);
            vec4 clouds = volumetricClouds(d);
            col = mix(col, clouds.rgb / max(clouds.a, 0.001), clouds.a);
            // В метель небо не видно вовсе: купол сходится с туманом, иначе
            // над белой мглой висит синее небо и мгла выглядит стеной.
            col = mix(col, uHaze, uHazeMix);
            FragColor = vec4(uLinearOut > 0.5 ? col : toSrgb(tonemapACES(col)), 1.0);
        }
        """;

    // ------------------------------------------------------------------
    //  Пост-обработка
    // ------------------------------------------------------------------

    /** Полноэкранный треугольник без VBO — позиция считается из gl_VertexID. */
    public static final String POST_VERTEX = VER + """
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    public static final String POST_BRIGHT = VER + """
        in vec2 vUv;
        uniform sampler2D uScene;
        uniform float uThreshold;
        out vec4 FragColor;
        void main() {
            vec3 c = texture(uScene, vUv).rgb;
            float l = max(c.r, max(c.g, c.b));
            float k = max(0.0, l - uThreshold) / max(l, 1e-4);
            FragColor = vec4(c * k, 1.0);
        }
        """;

    public static final String POST_BLUR = VER + """
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uDir;
        out vec4 FragColor;
        void main() {
            float w0 = 0.2270270, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.0540540, w4 = 0.0162162;
            vec3 c = texture(uTex, vUv).rgb * w0;
            c += (texture(uTex, vUv + uDir).rgb       + texture(uTex, vUv - uDir).rgb)       * w1;
            c += (texture(uTex, vUv + uDir * 2.0).rgb + texture(uTex, vUv - uDir * 2.0).rgb) * w2;
            c += (texture(uTex, vUv + uDir * 3.0).rgb + texture(uTex, vUv - uDir * 3.0).rgb) * w3;
            c += (texture(uTex, vUv + uDir * 4.0).rgb + texture(uTex, vUv - uDir * 4.0).rgb) * w4;
            FragColor = vec4(c, 1.0);
        }
        """;

    /**
     * Объёмный туман: марш по лучу через слой низовой мглы с тенями от солнца.
     *
     * Считается в половинном разрешении, пишет в RGB рассеянный свет, в A —
     * пропускание. Плотность — высотный профиль, помноженный на двумерный шум,
     * который ветер тащит по миру: так туман лежит клочьями между деревьями, а
     * не ровной плёнкой. На каждом шаге берётся каскад теней — поэтому под
     * кроной в тумане темно, а в просветах стоят столбы света.
     */
    public static final String POST_FOG = VER + """
        in vec2 vUv;
        uniform sampler2D uDepth;
        uniform sampler2DShadow uShadow;
        uniform mat4  uInvViewProj;
        uniform mat4  uShadowMat;
        uniform vec3  uCamPos;
        uniform vec3  uLightDir;
        uniform vec3  uLightColor;
        uniform vec3  uAmbient;
        uniform float uDensity;     // плотность у земли
        uniform float uHaze;        // равномерная дымка по всей высоте
        uniform float uTop;         // выше этой Y мглы нет
        uniform float uDepthRange;  // на скольких блоках она набирает плотность
        uniform float uMaxDist;
        uniform float uTime;
        uniform vec2  uWind;
        uniform float uShadowOn;
        uniform vec2  uNoiseOffset;
        out vec4 FragColor;

        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        float vnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
                       mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
        }

        float density(vec3 p) {
            float h = clamp((uTop - p.y) / max(uDepthRange, 0.001), 0.0, 1.0);
            vec2 drift = uWind * uTime * 0.35;
            float n = vnoise((p.xz - drift) * 0.055) * 0.65
                    + vnoise((p.xz - drift * 1.6) * 0.17 + p.y * 0.23) * 0.35;
            float patches = smoothstep(0.25, 0.85, n);
            return uDensity * h * h * (0.25 + 0.75 * patches) + uHaze;
        }

        /** Henyey-Greenstein: вперёд по свету туман светится ярче. */
        float phase(float cosTheta, float g) {
            float g2 = g * g;
            return (1.0 - g2) / (12.566 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
        }

        void main() {
            float z = texture(uDepth, vUv).r;
            vec4 ndc = vec4(vUv * 2.0 - 1.0, z * 2.0 - 1.0, 1.0);
            vec4 wp = uInvViewProj * ndc;
            vec3 world = wp.xyz / wp.w;
            vec3 ray = world - uCamPos;
            float sceneDist = length(ray);
            vec3 dir = ray / max(sceneDist, 1e-4);
            float dist = min(z >= 0.99999 ? uMaxDist : sceneDist, uMaxDist);

            const int STEPS = 14;
            float stepLen = dist / float(STEPS);
            // Сдвиг старта на долю шага по пиксельному шуму: ступеньки слоёв
            // превращаются в мелкое зерно, которое съедает апскейл.
            float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy + uNoiseOffset,
                                                        vec2(0.06711056, 0.00583715))));
            float cosTheta = dot(dir, uLightDir);
            float ph = mix(phase(cosTheta, 0.62), phase(cosTheta, -0.2), 0.25) * 12.566;

            vec3 scattered = vec3(0.0);
            float transmittance = 1.0;
            for (int i = 0; i < STEPS; i++) {
                vec3 p = uCamPos + dir * ((float(i) + jitter) * stepLen);
                float d = density(p);
                if (d <= 1e-5) continue;
                float lit = 1.0;
                if (uShadowOn > 0.5) {
                    vec4 sc = uShadowMat * vec4(p, 1.0);
                    vec3 s = sc.xyz / sc.w * 0.5 + 0.5;
                    lit = texture(uShadow, s);
                }
                vec3 light = uLightColor * lit * ph + uAmbient;
                float absorb = d * stepLen;
                scattered += transmittance * absorb * light;
                transmittance *= exp(-absorb);
                if (transmittance < 0.02) break;
            }
            FragColor = vec4(scattered, transmittance);
        }
        """;

    /** Радиальное размытие яркой маски от экранной позиции солнца. */
    public static final String POST_GODRAY = VER + """
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2  uSunUv;
        uniform float uDensity;
        uniform float uDecay;
        out vec4 FragColor;
        void main() {
            const int STEPS = 24;
            vec2 delta = (vUv - uSunUv) * (uDensity / float(STEPS));
            vec2 uv = vUv;
            float illum = 1.0;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < STEPS; i++) {
                uv -= delta;
                acc += texture(uTex, uv).rgb * illum;
                illum *= uDecay;
            }
            FragColor = vec4(acc / float(STEPS), 1.0);
        }
        """;

    public static final String POST_COMPOSITE = VER + LIB_COLOR + """
        in vec2 vUv;
        uniform sampler2D uScene;
        uniform sampler2D uBloom;
        uniform sampler2D uRays;
        uniform float uBloomStrength;
        uniform float uRayStrength;
        uniform vec3  uRayColor;
        uniform float uExposure;
        uniform float uVignette;
        uniform float uUnderwater;
        uniform vec3  uUnderwaterTint;
        uniform float uNight;
        uniform float uSaturation;
        uniform float uDamage;        // 0..1, вспышка урона
        uniform vec3  uDamageColor;
        // Глубина резкости. Ноль в uDofStrength выключает выборки целиком:
        // в обычной игре за неё не платят ни такта.
        uniform sampler2D uDepth;
        uniform float uDofStrength;   // радиус размытия в пикселях
        uniform float uDofFocus;      // дистанция фокуса, блоки
        uniform float uDofRange;      // полуширина резкой зоны, блоки
        uniform vec2  uNearFar;
        uniform vec2  uTexel;
        // Где рука от первого лица: там резко при любом фокусе.
        uniform sampler2D uHandDepth;
        uniform float uHandMask;
        // Объёмный туман в половинном разрешении: rgb — рассеяние, a — пропускание.
        uniform sampler2D uFog;
        uniform float uFogOn;
        // Иней по краям кадра.
        uniform float uFrost;
        uniform float uFrostTime;
        uniform float uPoison;
        uniform float uStun;
        out vec4 FragColor;

        float linearDepthOf(float z) {
            float n = uNearFar.x, f = uNearFar.y;
            return (2.0 * n * f) / (f + n - (z * 2.0 - 1.0) * (f - n));
        }

        float fHash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        float fNoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(fHash(i), fHash(i + vec2(1, 0)), u.x),
                       mix(fHash(i + vec2(0, 1)), fHash(i + vec2(1, 1)), u.x), u.y);
        }

        /**
         * Узор инея: гребни шума, вытянутые от краёв к центру. Ледяные
         * «перья» растут поперёк края кадра, поэтому шум берётся в координатах
         * «расстояние до края × вдоль края», а гребень — это 1 − |2n − 1|.
         */
        float frostMask(vec2 uv, float amount) {
            vec2 d = abs(uv - 0.5) * 2.0;
            // Углы замерзают первыми: там стекло холоднее всего.
            float edge = max(max(d.x, d.y), length(d) * 0.78);
            float reach = mix(1.02, 0.66, amount);
            float base = smoothstep(reach, reach + 0.34, edge);
            // Кристаллы: гребни шума в повёрнутых и искажённых координатах —
            // по осям сетки value-noise рисует прямоугольные пятна, а не лёд.
            vec2 p = mat2(0.80, -0.60, 0.60, 0.80) * (uv * vec2(1.78, 1.0));
            p += vec2(fNoise(p * 6.0), fNoise(p * 6.0 + 3.7)) * 0.08;
            float ridge = 0.0, amp = 0.5, freq = 14.0;
            for (int i = 0; i < 4; i++) {
                float n = fNoise(p * freq + float(i) * 11.7);
                ridge += pow(1.0 - abs(n * 2.0 - 1.0), 3.0) * amp;
                p = mat2(0.80, 0.60, -0.60, 0.80) * p;
                freq *= 2.03;
                amp *= 0.6;
            }
            float feathers = smoothstep(0.18, 0.75, ridge);
            return clamp(base * (0.25 + feathers), 0.0, 1.0) * smoothstep(0.0, 0.2, amount);
        }

        /**
         * Туман из половинного разрешения без ореолов на силуэтах.
         * Четыре соседние выборки взвешиваются по близости их глубины к
         * глубине пикселя: иначе светлая мгла фона наползает на край ствола
         * на переднем плане.
         */
        vec4 fogAt(vec2 uv, float centerDepth) {
            vec2 o = uTexel * 1.5;
            vec4 acc = vec4(0.0);
            float wsum = 0.0;
            for (int i = 0; i < 4; i++) {
                vec2 off = vec2((i & 1) == 0 ? -o.x : o.x, (i & 2) == 0 ? -o.y : o.y);
                vec2 u = uv + off;
                float di = linearDepthOf(texture(uDepth, u).r);
                float w = 1.0 / (0.02 + abs(di - centerDepth) / max(centerDepth, 0.5));
                acc += texture(uFog, u) * w;
                wsum += w;
            }
            return acc / max(wsum, 1e-4);
        }

        /** Из оконной глубины обратно в расстояние по камере. */
        float linearDepth(float z) {
            float n = uNearFar.x, f = uNearFar.y;
            return (2.0 * n * f) / (f + n - (z * 2.0 - 1.0) * (f - n));
        }

        /**
         * Диск из двенадцати выборок на двух кольцах.
         *
         * Гауссов боке в два прохода дал бы мягче, но потребовал бы своей
         * пары буферов; в фоторежиме кадр всё равно не считают в миллисекундах,
         * а диск честнее по форме — размытие камеры круглое, а не крестом.
         */
        vec3 dofBlur(vec2 uv, float radius) {
            const vec2 RING[12] = vec2[12](
                vec2( 1.0, 0.0), vec2( 0.5, 0.87), vec2(-0.5, 0.87), vec2(-1.0, 0.0),
                vec2(-0.5,-0.87), vec2( 0.5,-0.87),
                vec2( 0.87, 0.5), vec2( 0.0, 1.0), vec2(-0.87, 0.5), vec2(-0.87,-0.5),
                vec2( 0.0,-1.0), vec2( 0.87,-0.5));
            vec3 sum = texture(uScene, uv).rgb;
            float w = 1.0;
            for (int i = 0; i < 12; i++) {
                float ring = i < 6 ? 0.55 : 1.0;
                sum += texture(uScene, uv + RING[i] * uTexel * radius * ring).rgb;
                w += 1.0;
            }
            return sum / w;
        }

        void main() {
            vec2 uv = vUv;
            // Под водой лучи преломляются на движущейся поверхности, а на
            // кадре появляются мягкие каустические полосы.
            if (uUnderwater > 0.001) {
                float wave = sin(vUv.y * 38.0 + uFrostTime * 2.2)
                           + sin(vUv.x * 27.0 - uFrostTime * 1.7);
                uv += vec2(wave, sin(vUv.x * 31.0 + uFrostTime * 1.3))
                    * uTexel * (2.4 * uUnderwater);
            }
            // Иней чуть преломляет картинку под собой: лёд на стекле не плоская
            // наклейка, сквозь него мир слегка плывёт.
            float frost = uFrost > 0.001 ? frostMask(vUv, uFrost) : 0.0;
            if (frost > 0.0) {
                vec2 wob = vec2(fNoise(vUv * 11.0), fNoise(vUv * 11.0 + 7.1)) - 0.5;
                uv += wob * 0.005 * frost;
            }
            vec3 c = texture(uScene, uv).rgb;
            float depth = linearDepth(texture(uDepth, uv).r);
            if (uDofStrength > 0.0) {
                // Резко в полосе вокруг фокуса, дальше размытие нарастает.
                float coc = clamp((abs(depth - uDofFocus) - uDofRange) / (uDofRange * 3.0), 0.0, 1.0);
                if (uHandMask > 0.5 && texture(uHandDepth, uv).r < 0.99999)
                    coc = 0.0;
                if (coc > 0.01)
                    c = mix(c, dofBlur(uv, uDofStrength * coc), coc);
            }
            // Объёмный туман ложится на мир, но не на руку: рука перед глазами,
            // между ней и камерой мглы нет.
            if (uFogOn > 0.5 && !(uHandMask > 0.5 && texture(uHandDepth, uv).r < 0.99999)) {
                vec4 fog = fogAt(uv, depth);
                c = c * fog.a + fog.rgb;
            }
            c += texture(uBloom, uv).rgb * uBloomStrength;
            c += texture(uRays, uv).rgb * uRayColor * uRayStrength;
            c = mix(c, c * uUnderwaterTint, uUnderwater);
            if (uUnderwater > 0.0) {
                float caustic = pow(max(0.0, sin((uv.x + uv.y) * 76.0 + uFrostTime * 2.1)
                                             * sin((uv.x - uv.y) * 53.0 - uFrostTime * 1.6)), 5.0);
                c += vec3(0.18, 0.42, 0.48) * caustic * uUnderwater * 0.16;
            }
            c *= uExposure;

            // Эффект Пуркинье: ночью тёмные участки уходят в холодный монохром.
            float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(c, vec3(lum) * vec3(0.70, 0.84, 1.28),
                    uNight * 0.5 * (1.0 - smoothstep(0.0, 0.30, lum)));

            c = tonemapACES(c);
            lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(lum), c, uSaturation);

            vec2 d = vUv - 0.5;
            c *= clamp(1.0 - uVignette * dot(d, d) * 1.7, 0.0, 1.0);

            // Урон читается как кровь по краям кадра, а не как заливка всего
            // экрана: заливка съедает картинку и мешает убегать.
            if (uDamage > 0.0) {
                float edge = smoothstep(0.08, 0.55, dot(d, d));
                c = mix(c, uDamageColor, edge * uDamage * 0.85);
            }
            if (uPoison > 0.0) {
                float edge = smoothstep(0.10, 0.52, dot(d, d));
                float pulse = 0.72 + 0.28 * sin(uFrostTime * 2.6);
                c = mix(c, vec3(0.12, 0.46, 0.08), edge * uPoison * pulse * 0.62);
            }
            if (uStun > 0.0) {
                vec2 split = vec2(uTexel.x * (3.0 + uStun * 5.0), 0.0);
                vec3 ghost = vec3(texture(uScene, uv + split).r,
                                  texture(uScene, uv).g,
                                  texture(uScene, uv - split).b);
                c = mix(c, tonemapACES(ghost * uExposure), uStun * 0.48);
            }
            // Иней — после тонемапа: лёд белый при любой экспозиции, а в
            // линейном свете ночью он тонул бы вместе со всем кадром.
            if (frost > 0.0) {
                float sparkle = step(0.985, fHash(floor(vUv * vec2(640.0, 360.0))))
                              * (0.5 + 0.5 * sin(uFrostTime * 3.0 + vUv.x * 40.0));
                vec3 ice = vec3(0.80, 0.89, 0.97) + sparkle * 0.25;
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(c, ice * (0.55 + 0.45 * max(lum, 0.25)), frost * 0.62);
            }
            c = toSrgb(c);

            // Дизеринг: 16-битное небо иначе идёт полосами.
            float n = fract(sin(dot(vUv, vec2(12.9898, 78.233))) * 43758.5453);
            FragColor = vec4(c + (n - 0.5) / 255.0, 1.0);
        }
        """;

    // ------------------------------------------------------------------
    //  Плоские и вспомогательные программы
    // ------------------------------------------------------------------

    public static final String HUD_VERTEX = VER + """
        layout (location = 0) in vec2 aPos;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """;

    public static final String HUD_FRAGMENT = VER + """
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() { FragColor = uColor; }
        """;

    public static final String LINE_VERTEX = VER + """
        layout (location = 0) in vec3 aPos;
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform mat4 uModel;
        void main() { gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0); }
        """;

    public static final String LINE_FRAGMENT = VER + LIB_COLOR + """
        uniform vec4 uColor;
        uniform float uLinearOut;
        uniform float uEmissive;   // запас за 1.0 в HDR — из него растёт bloom
        out vec4 FragColor;
        void main() {
            vec3 c = uLinearOut > 0.5 ? toLinear(uColor.rgb) * (1.0 + uEmissive) : uColor.rgb;
            FragColor = vec4(c, uColor.a);
        }
        """;

    public static final String PARTICLE_VERTEX = VER + """
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

    public static final String PARTICLE_FRAGMENT = VER + LIB_COLOR + """
        in vec2 vUv;
        uniform sampler2D uAtlas;
        uniform vec4 uColor;
        uniform float uLinearOut;
        uniform float uEmissive;
        out vec4 FragColor;
        void main() {
            vec4 tex = texture(uAtlas, vUv);
            if (tex.a < 0.05) discard;
            vec3 c = tex.rgb * uColor.rgb;
            if (uLinearOut > 0.5) c = toLinear(c) * (1.0 + uEmissive);
            FragColor = vec4(c, tex.a * uColor.a);
        }
        """;

    public static final String INSTANCED_PARTICLE_VERTEX = VER + """
        layout(location=0) in vec2 aCorner;
        layout(location=1) in vec4 aCenterSize;
        layout(location=2) in vec4 aColor;
        layout(location=3) in vec4 aUvRect;
        layout(location=4) in float aEmissive;
        uniform mat4 uProjection, uView;
        uniform vec3 uRight, uUp;
        out vec2 vUv;
        out vec4 vColor;
        out float vEmissive;
        void main() {
            vec3 world = aCenterSize.xyz + (uRight * aCorner.x + uUp * aCorner.y) * aCenterSize.w;
            gl_Position = uProjection * uView * vec4(world, 1.0);
            vUv = mix(aUvRect.xy, aUvRect.zw, aCorner + vec2(0.5));
            vColor = aColor;
            vEmissive = aEmissive;
        }
        """;
    public static final String INSTANCED_PARTICLE_FRAGMENT = PARTICLE_FRAGMENT
            .replace("uniform vec4 uColor;", "in vec4 vColor;")
            .replace("uniform float uEmissive;", "in float vEmissive;")
            .replace("uColor", "vColor").replace("uEmissive", "vEmissive");

    public static final String TEXT_VERTEX = VER + """
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

    public static final String TEXT_FRAGMENT = VER + """
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

    public static final String UI_VERTEX = VER + """
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

    public static final String UI_FRAGMENT = VER + """
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform sampler2D uBackdrop;
        uniform vec2 uFbSize;
        uniform int uUseTexture;   // 0 — заливка, 1 — текстура, 2 — стекло
        uniform vec4 uColor;
        out vec4 FragColor;
        void main() {
            vec4 c = uColor;
            if (uUseTexture == 1) {
                vec4 t = texture(uTex, vUv);
                if (t.a < 0.1) discard;
                c = t * uColor;
            } else if (uUseTexture == 2) {
                // Стекло чуть светлее и спокойнее того, что под ним: матовое
                // стекло рассеивает, а не просто размывает.
                vec3 b = texture(uBackdrop, gl_FragCoord.xy / uFbSize).rgb;
                float lum = dot(b, vec3(0.2126, 0.7152, 0.0722));
                b = mix(vec3(lum), b, 0.8) * 1.06 + 0.02;
                // Альфа из uColor: экран меню проявляется целиком, стекло тоже.
                c = vec4(b, uColor.a);
            }
            FragColor = c;
        }
        """;

    public static final String SKY_VERTEX = VER + """
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

    /**
     * Спрайты неба светятся: солнце уходит далеко за 1.0, чтобы bloom и
     * god rays было из чего строить.
     */
    public static final String SKY_FRAGMENT = VER + LIB_COLOR + """
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform vec4 uColor;
        uniform float uLinearOut;
        uniform float uEmissive;
        uniform float uPhase;     // фаза луны 0..7; отрицательная — это не луна
        out vec4 FragColor;
        void main() {
            vec4 t = texture(uTex, vUv);
            if (t.a < 0.01) discard;
            vec3 c = t.rgb * uColor.rgb;
            float a = t.a * uColor.a;
            if (uPhase >= 0.0) {
                // Терминатор идёт по пиксельной сетке диска 16x16: плавная
                // граница на квадратной блочной луне читалась бы как размытие.
                float x = ((floor(vUv.x * 16.0) + 0.5) / 16.0) * 2.0 - 1.0;
                float phi = uPhase * 0.785398;
                bool lit = phi <= 3.14159
                        ? x < 1.0 - 2.0 * phi / 3.14159
                        : x > 1.0 - 2.0 * (phi - 3.14159) / 3.14159;
                // Тёмная часть не пропадает: пепельный свет Земли.
                if (!lit) { c *= 0.09; a *= 0.6; }
            }
            if (uLinearOut > 0.5) c = toLinear(c) * uEmissive;
            FragColor = vec4(c, a);
        }
        """;

    /**
     * Трещины износа на инструменте в руке. Рисуется вторым проходом той же
     * геометрии: альфа спрайта инструмента — маска, тайл трещины — узор.
     */
    public static final String ITEM_CRACK_FRAGMENT = VER + LIB_COLOR + """
        centroid in vec2 vUv;
        in float vLight;
        in float vFogDist;
        in float vBlockLight;
        in vec3  vWorld;
        uniform sampler2D uAtlas;
        uniform vec2  uToolUv0;
        uniform vec2  uCrackUv0;
        uniform vec2  uSpan;
        uniform float uAlpha;
        uniform float uWear;       // 0..1 — насколько инструмент изношен
        uniform float uLinearOut;
        out vec4 FragColor;
        float cHash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        void main() {
            if (texture(uAtlas, vUv).a < 0.5) discard;
            vec2 local = clamp((vUv - uToolUv0) / uSpan, 0.0, 1.0);
            // Спрайт инструмента узкий и диагональный, тайл трещины блока
            // пересекается с ним парой пикселей. Поэтому к трещине добавлены
            // сколы по пиксельной сетке мастера 16x16: чем больше износ, тем
            // больше тёмных выщербин.
            vec4 crack = texture(uAtlas, uCrackUv0 + local * uSpan);
            float chip = step(cHash(floor(local * 16.0) + 7.3), uWear * 0.42);
            float a = max(crack.a > 0.1 ? crack.a : 0.0, chip);
            if (a < 0.1) discard;
            vec3 c = mix(vec3(0.10, 0.09, 0.08), crack.rgb * 0.3, crack.a > 0.1 ? 0.5 : 0.0);
            FragColor = vec4(uLinearOut > 0.5 ? toLinear(c) : c, a * uAlpha);
        }
        """;

    /** След взмаха: полоса вершин с альфой, светится сложением. */
    public static final String TRAIL_VERTEX = VER + """
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in float aAlpha;
        uniform mat4 uProjection;
        uniform mat4 uView;
        out float vAlpha;
        void main() {
            gl_Position = uProjection * uView * vec4(aPos, 1.0);
            vAlpha = aAlpha;
        }
        """;

    public static final String TRAIL_FRAGMENT = VER + LIB_COLOR + """
        in float vAlpha;
        uniform vec3  uColor;
        uniform float uLinearOut;
        out vec4 FragColor;
        void main() {
            vec3 c = uLinearOut > 0.5 ? toLinear(uColor) * 2.2 : uColor;
            FragColor = vec4(c, vAlpha);
        }
        """;

    public static final String CRACK_VERTEX = VER + """
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

    public static final String CRACK_FRAGMENT = VER + LIB_COLOR + """
        in vec2 vUv;
        uniform sampler2D uAtlas;
        uniform float uLinearOut;
        out vec4 FragColor;
        void main() {
            vec4 col = texture(uAtlas, vUv);
            if (col.a < 0.01) discard;
            FragColor = vec4(uLinearOut > 0.5 ? toLinear(col.rgb) : col.rgb, col.a);
        }
        """;

    // ------------------------------------------------------------------
    //  Мобы и рука от первого лица
    // ------------------------------------------------------------------

    public static final String MOB_VERTEX = VER + """
        layout (location = 0) in vec3 aPos;      // единичный куб, -0.5..0.5
        layout (location = 1) in float aFace;    // 0=front(-Z), 1=side, 2=top/bottom
        layout (location = 2) in vec2 aCorner;   // 0..1 внутри грани
        uniform mat4 uProjection;
        uniform mat4 uView;
        uniform mat4 uModel;
        uniform vec2 uUvFront;
        uniform vec2 uUvSide;
        uniform vec2 uUvTop;
        uniform vec2 uTileSize;
        out vec2 vUv;
        out float vFogDist;
        out vec3 vWorld;
        void main() {
            vec4 worldPos = uModel * vec4(aPos, 1.0);
            vec4 viewPos = uView * worldPos;
            gl_Position = uProjection * viewPos;
            vec2 base = aFace < 0.5 ? uUvFront : (aFace < 1.5 ? uUvSide : uUvTop);
            vUv = base + aCorner * uTileSize;
            vFogDist = length(viewPos.xyz);
            vWorld = worldPos.xyz;
        }
        """;

    public static final String MOB_FRAGMENT = VER + LIB_COLOR + LIB_SHADOW + LIB_LIGHT + """
        in vec2 vUv;
        in float vFogDist;
        in vec3 vWorld;
        uniform sampler2D uSkin;
        uniform float uSkyVis;     // доля небесного света в точке моба
        uniform float uBlockVis;   // доля блочного света
        uniform vec3  uTint;
        uniform vec3  uGlow;       // собственное свечение (ярость); ноль — нет
        out vec4 FragColor;
        void main() {
            vec3 toCam = uCamPos - vWorld;
            vec3 V = toCam / max(length(toCam), 1e-4);
            vec3 N = faceNormal(vWorld, V);

            vec4 tex = texture(uSkin, vUv);
            if (tex.a < 0.1) discard;
            vec3 albedo = toLinear(tex.rgb) * uTint;

            float ndl = max(dot(N, uLightDir), 0.0);
            float shade = shadowFactor(vWorld, N, vFogDist, ndl);
            float faceAo = faceShade(N);

            vec3 hemi = mix(uGroundLight, uSkyLight, N.y * 0.5 + 0.5) * uSkyVis * faceAo;
            vec3 lit = albedo * (uLightColor * ndl * shade * uSkyVis
                                 + hemi
                                 + uTorchColor * pow(uBlockVis, 1.4)
                                 + uAmbientColor
                                 + pointLight(vWorld, N));
            lit *= uBrightness;
            // Свечение не зависит от света вокруг: взбешённого видно и ночью.
            // Умножено на яркость текстуры, чтобы узор скина не залило краской.
            lit += uGlow * (0.35 + dot(albedo, vec3(0.2126, 0.7152, 0.0722)) * 2.0);

            lit = applyHeightFog(lit, vWorld, vFogDist);
            FragColor = vec4(finishColor(applyFog(lit, vWorld, vFogDist), uLinearOut), 1.0);
        }
        """;
}
