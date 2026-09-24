package com.mineclone.game;

import com.mineclone.world.Biome;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * Один кадр кинематографа главного меню: траектория камеры над пейзажем плюс
 * атмосфера, в которой этот пейзаж снят.
 *
 * <p>Класс целиком — чистая математика: ни GL, ни мира, ни времени. Поэтому
 * и траектория, и — главное — множество чанков, которые кадр обязан иметь
 * загруженными, проверяются обычными тестами, а не глазами по скриншоту.
 *
 * <p><b>Гарантия «без пропавших чанков».</b> Шейдер чанка мешает цвет с
 * туманом через {@code smoothstep(uFogStart, uFogEnd, d)}, а это ровно
 * единица при {@code d >= uFogEnd}: геометрия дальше {@link #FOG_END}
 * неотличима от тумана, и доказывать надо только ближнюю зону. Её и считает
 * {@link #requiredChunks()} — объединение видимого по всей траектории. Купол
 * неба в меню красится тем же цветом, что туман, поэтому за краем
 * загруженного диска не видно даже стыка.
 */
public final class MenuShot {

    /** С какого расстояния начинается воздушная дымка и где она полная, блоки. */
    public static final float FOG_START = 96f, FOG_END = 128f;
    /**
     * Радиус загрузки в чанках. Девять — это 144 блока гарантированно
     * загруженного во все стороны, с запасом в 16 блоков над {@link #FOG_END}:
     * последние шестнадцать рисуются полностью в тумане, и переход к куполу
     * попадает туда, где смотреть уже не на что.
     *
     * <p>Радиус подняли с семи после первых снимков: при {@code FOG_END = 96}
     * дальний план тонул в дымке целиком, и пейзаж кончался мягким пятном там,
     * где должен был читаться силуэт хребта.
     */
    public static final int LOAD_RADIUS = 9;

    /**
     * Полураствор конуса, в котором чанк считается видимым.
     *
     * <p>Восемьдесят пять градусов — это не «поле зрения с запасом», а предел
     * с большим запасом: самый широкий угол даёт нижний угол кадра у камеры,
     * наклонённой к земле, и он равен {@code atan(tan(fov/2)·aspect /
     * (cos θ − tan(fov/2)·sin θ))}. При нашем самом широком кадре (72°) и
     * наклоне 18° восемьдесят пять градусов покрывают соотношение сторон до
     * 11:1 — ни ультраширокий монитор, ни три монитора в ряд сюда не
     * упираются. Прежние 105° стоили четверти лишних чанков и ровно ничего не
     * давали.
     */
    static final float HALF_ANGLE = (float) Math.toRadians(85.0);
    /** Вокруг камеры чанки держатся всегда — земля под ногами и за спиной. */
    static final float NEAR_KEEP = 34f;
    /** Сколько точек траектории опрашивается при сборе нужных чанков. */
    private static final int SAMPLES = 256;
    /**
     * Доля пути на разгон и на торможение. Трапеция скорости, а не
     * {@code smoothstep}: равномерное движение читается механическим, а
     * сглаживание с обоих концов останавливает камеру там, где она должна
     * лететь.
     */
    private static final float EASE_K = 0.14f;

    /** Положение и курс камеры в момент {@code t}. */
    public static final class Pose {
        public float x, y, z, yaw, pitch;
    }

    /**
     * Геометрия пролёта. Камера идёт по квадратичной кривой Безье, цель
     * взгляда едет отдельно по прямой: так получается наезд с уводом, а не
     * поворот вокруг оси.
     */
    public record Path(float fromX, float fromY, float fromZ,
                       float toX, float toY, float toZ,
                       float ctrlX, float ctrlY, float ctrlZ,
                       float lookFromX, float lookFromY, float lookFromZ,
                       float lookToX, float lookToY, float lookToZ,
                       float duration, float fovDeg) {}

    /**
     * Воздух кадра: время суток и его ход, осадки, ветер, мгла и тон.
     * Всё, что в игре считает {@code Atmosphere} по месту игрока, здесь
     * назначено кадру: фон меню — снятая сцена, а не симуляция.
     */
    public record Air(float gameTime, float timeRate,
                      float cloudiness, float storm, float rain, float snow,
                      float windX, float windZ,
                      float mist, float haze,
                      float exposure, float saturation,
                      float dofStrength, float dofFocus, float dofRange,
                      float aurora) {}

    public final String name;
    public final Path path;
    public final Air air;
    public final Biome biome;

    private Set<Long> required;

    public MenuShot(String name, Path path, Air air, Biome biome) {
        this.name = name;
        this.path = path;
        this.air = air;
        this.biome = biome;
    }

    public float duration() { return path.duration(); }
    public float fovDeg() { return path.fovDeg(); }

    /**
     * Профиль скорости: разгон на первых {@link #EASE_K}, постоянная
     * скорость, торможение на последних. Возвращает пройденную долю пути.
     */
    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float k = EASE_K;
        float norm = 1f - k;            // площадь под трапецией скорости
        float s;
        if (t < k)
            s = t * t / (2f * k);
        else if (t < 1f - k)
            s = k * 0.5f + (t - k);
        else {
            float r = 1f - t;
            s = norm - r * r / (2f * k);
        }
        return s / norm;
    }

    /** Положение и курс камеры в долю {@code t} от начала кадра. */
    public Pose pose(float t, Pose out) {
        if (out == null)
            out = new Pose();
        float u = ease(t);
        float v = 1f - u;
        float w0 = v * v, w1 = 2f * v * u, w2 = u * u;
        out.x = w0 * path.fromX() + w1 * path.ctrlX() + w2 * path.toX();
        out.y = w0 * path.fromY() + w1 * path.ctrlY() + w2 * path.toY();
        out.z = w0 * path.fromZ() + w1 * path.ctrlZ() + w2 * path.toZ();

        float lx = path.lookFromX() + (path.lookToX() - path.lookFromX()) * u;
        float ly = path.lookFromY() + (path.lookToY() - path.lookFromY()) * u;
        float lz = path.lookFromZ() + (path.lookToZ() - path.lookFromZ()) * u;

        float dx = lx - out.x, dy = ly - out.y, dz = lz - out.z;
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        // Курс — как у Camera.forward: +X это sin(yaw), −Z это cos(yaw).
        out.yaw = (float) Math.atan2(dx, -dz);
        out.pitch = (float) Math.atan2(-dy, Math.max(1e-4f, flat));

        // Едва заметное дрожание: меньше полуградуса, периоды семь и
        // одиннадцать секунд. Без него камера читается как рельсы.
        float sec = t * path.duration();
        out.yaw += 0.0060f * (float) Math.sin(sec * 0.90 + 1.3);
        out.pitch += 0.0038f * (float) Math.sin(sec * 0.57 + 0.2);
        return out;
    }

    /**
     * Чанки, которые обязаны быть загружены и смешены до показа кадра.
     * Считается один раз и кэшируется: расписание спрашивает его каждый кадр.
     */
    public Set<Long> requiredChunks() {
        if (required != null)
            return required;
        Set<Long> all = new HashSet<>(512);
        Pose p = new Pose();
        for (int i = 0; i <= SAMPLES; i++) {
            pose(i / (float) SAMPLES, p);
            collectVisible(p, all);
        }
        required = all;
        return all;
    }

    /** Чанки, видимые в момент {@code t}. Отдельно — им проверяется гарантия. */
    public Set<Long> visibleChunks(float t) {
        Set<Long> out = new HashSet<>(128);
        collectVisible(pose(t, null), out);
        return out;
    }

    /**
     * Чанк попадает в набор, если его ближайшая точка ближе {@link #FOG_END}
     * и хотя бы один его угол лежит в конусе {@link #HALF_ANGLE} вокруг
     * курса, либо если он вовсе рядом с камерой.
     *
     * <p>Расстояние считается по горизонтали, а шейдер меряет полное
     * трёхмерное — оно всегда не меньше, поэтому оценка консервативна:
     * чанк, отброшенный здесь, в шейдере тем более в полном тумане.
     */
    private void collectVisible(Pose p, Set<Long> out) {
        int ccx = Math.floorDiv((int) Math.floor(p.x), Chunk.SIZE_X);
        int ccz = Math.floorDiv((int) Math.floor(p.z), Chunk.SIZE_Z);
        float fx = (float) Math.sin(p.yaw), fz = (float) -Math.cos(p.yaw);
        for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
            for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) {
                int cx = ccx + dx, cz = ccz + dz;
                float minX = cx * Chunk.SIZE_X, maxX = minX + Chunk.SIZE_X;
                float minZ = cz * Chunk.SIZE_Z, maxZ = minZ + Chunk.SIZE_Z;
                float nx = Math.max(minX, Math.min(maxX, p.x));
                float nz = Math.max(minZ, Math.min(maxZ, p.z));
                float near = (float) Math.hypot(nx - p.x, nz - p.z);
                if (near > FOG_END)
                    continue;
                if (near <= NEAR_KEEP) {
                    out.add(World.key(cx, cz));
                    continue;
                }
                if (anyCornerInCone(p, fx, fz, minX, maxX, minZ, maxZ))
                    out.add(World.key(cx, cz));
            }
        }
    }

    private static boolean anyCornerInCone(Pose p, float fx, float fz,
                                           float minX, float maxX, float minZ, float maxZ) {
        return inCone(p, fx, fz, minX, minZ) || inCone(p, fx, fz, maxX, minZ)
                || inCone(p, fx, fz, minX, maxZ) || inCone(p, fx, fz, maxX, maxZ);
    }

    private static boolean inCone(Pose p, float fx, float fz, float x, float z) {
        float ax = x - p.x, az = z - p.z;
        float len = (float) Math.sqrt(ax * ax + az * az);
        if (len < 1e-4f)
            return true;
        double cos = (ax * fx + az * fz) / len;
        return cos >= Math.cos(HALF_ANGLE);
    }

    /** Самая дальняя точка траектории от её середины — для разноса площадок. */
    public float span() {
        Pose a = pose(0f, null), b = pose(1f, null);
        return (float) Math.hypot(a.x - b.x, a.z - b.z);
    }
}
