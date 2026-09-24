package com.mineclone.game;

import com.mineclone.world.Biome;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Разведчик пейзажей для фона меню: находит в мире меню пять мест, которые
 * стоит показать, и ставит над каждым камеру.
 *
 * <p>Рельеф и биомы — чистые функции от сида, поэтому искать можно, не
 * сгенерировав ни одного чанка: разведка стоит доли секунды, детерминирована
 * и проверяется обычным тестом. Альтернатива — вписать пять пар координат
 * константами — привязала бы красоту меню к одному сиду навсегда и не
 * проверялась бы ничем.
 *
 * <p>Категории разные намеренно: гора, вода, берег, лес и холод дают пять
 * непохожих кадров. Если категория в этом сиде не нашлась, её место занимает
 * лучший оставшийся кандидат — кадров всегда ровно пять.
 */
public final class MenuScout {
    private MenuScout() {}

    /** Докуда от начала координат ищем и с каким шагом. */
    private static final int SCAN_RADIUS = 960, SCAN_STEP = 160;
    /** Местная выборка вокруг кандидата: 7×7 точек через 22 блока. */
    private static final int PROBE = 7, PROBE_STEP = 22;
    /** Ближе этого две площадки не ставим — иначе два кадра об одном и том же. */
    private static final int MIN_SEPARATION = 320;
    /** Сколько секунд длится кадр. */
    private static final float DURATION = 21f;

    /** Откуда и куда летит камера относительно героя кадра. */
    private static final float DIST_FROM = 78f, DIST_TO = 54f;
    /** Насколько камера идёт выше земли под собой — минимум, а не правило. */
    private static final float RISE_FROM = 22f, RISE_TO = 15f;
    /**
     * В каких пределах камера смотрит вниз на цель. Верхняя граница задаёт
     * высоту камеры, нижняя — опускает саму цель, если камере не хватило
     * потолка мира: на альпийский гребень в сто десять блоков сверху уже не
     * встать, и раньше точка прицеливания уезжала внутрь горы.
     */
    private static final float TAN_MAX = 0.249f, TAN_MIN = 0.123f;  // 14° и 7°
    /** На сколько камера уводит вбок за кадр и насколько выгибается дуга. */
    private static final float SWEEP = 0.42f, BULGE = 9f;
    /** Ниже этого над рельефом камера не опускается, выше потолка не лезет. */
    private static final float CLEARANCE = 8f, CEILING = 120f;

    /** Выше этого над землёй под собой камера не поднимается. */
    private static final float MAX_ABOVE = 42f;

    /**
     * Что в кадре главное.
     *
     * <p>{@code HIGH} — самая высокая точка вокруг, {@code WATER} — вода,
     * {@code MIDDLE} — сама площадка. Лесу и снежнику нужен именно
     * {@code MIDDLE}: раньше их целью становился случайный пик на краю
     * выборки, камера уходила за ним под потолок мира, и вместо леса в кадре
     * был вид с высоты в семьдесят блоков.
     */
    private enum Hero { HIGH, WATER, MIDDLE }

    /**
     * Кандидат: местная выборка высот и биомов, свёрнутая в несколько чисел.
     *
     * @param hiX,hiZ   центр тяжести высоких точек — куда смотреть на горе
     * @param loX,loZ   центр тяжести низких — откуда на неё смотреть
     * @param wetX,wetZ центр тяжести воды
     */
    private record Site(int x, int z, int hi, int lo, int avg,
                        int water, int cold, int treeish, int alpine, int kinds,
                        float hiX, float hiZ, float loX, float loZ,
                        float wetX, float wetZ, Biome biome) {}

    /** Все кадры фона меню по сиду мира. Порядок показа — порядок списка. */
    public static List<MenuShot> shots(World world) {
        List<Site> sites = scan(world);
        List<MenuShot> out = new ArrayList<>(5);
        List<Site> taken = new ArrayList<>(5);
        for (Kind kind : Kind.values()) {
            Site s = pick(sites, taken, kind);
            if (s == null)
                continue;
            taken.add(s);
            out.add(compose(world, s, kind, out.size()));
        }
        return out;
    }

    // ---------------- разведка ----------------

    private static List<Site> scan(World world) {
        List<Site> out = new ArrayList<>(256);
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x += SCAN_STEP)
            for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z += SCAN_STEP) {
                Site s = probe(world, x, z);
                // Площадка целиком под водой показывать нечего.
                if (s.hi() > World.SEA_LEVEL + 2)
                    out.add(s);
            }
        return out;
    }

    private static Site probe(World world, int cx, int cz) {
        int n = PROBE * PROBE, half = PROBE / 2;
        int hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE, sum = 0;
        int water = 0, cold = 0, treeish = 0, alpine = 0;
        long kindBits = 0;
        float hiWx = 0, hiWz = 0, hiW = 0, loWx = 0, loWz = 0, loW = 0;
        float wetX = 0, wetZ = 0;
        int[] h = new int[n];
        int[] px = new int[n], pz = new int[n];
        for (int i = 0; i < PROBE; i++)
            for (int j = 0; j < PROBE; j++) {
                int k = i * PROBE + j;
                int wx = cx + (i - half) * PROBE_STEP;
                int wz = cz + (j - half) * PROBE_STEP;
                px[k] = wx;
                pz[k] = wz;
                h[k] = world.terrainHeight(wx, wz);
                Biome b = world.biomes.biomeAt(wx, wz);
                kindBits |= 1L << b.ordinal();
                if (h[k] <= World.SEA_LEVEL) {
                    water++;
                    wetX += wx;
                    wetZ += wz;
                }
                if (b.isCold()) cold++;
                if (b == Biome.FOREST || b == Biome.TAIGA || b == Biome.SWAMP) treeish++;
                if (b == Biome.ALPINE) alpine++;
                hi = Math.max(hi, h[k]);
                lo = Math.min(lo, h[k]);
                sum += h[k];
            }
        int avg = sum / n;
        for (int k = 0; k < n; k++) {
            float up = Math.max(0, h[k] - avg), down = Math.max(0, avg - h[k]);
            hiWx += px[k] * up;   hiWz += pz[k] * up;   hiW += up;
            loWx += px[k] * down; loWz += pz[k] * down; loW += down;
        }
        float hx = hiW > 0 ? hiWx / hiW : cx, hz = hiW > 0 ? hiWz / hiW : cz;
        float lx = loW > 0 ? loWx / loW : cx, lz = loW > 0 ? loWz / loW : cz;
        float mx = water > 0 ? wetX / water : cx, mz = water > 0 ? wetZ / water : cz;
        return new Site(cx, cz, hi, lo, avg, water, cold, treeish, alpine,
                Long.bitCount(kindBits), hx, hz, lx, lz, mx, mz,
                world.biomes.biomeAt(cx, cz));
    }

    // ---------------- отбор ----------------

    /** Категории кадров в порядке показа. */
    private enum Kind { MOUNTAIN, VALLEY, COAST, FOREST, COLD }

    private static Site pick(List<Site> sites, List<Site> taken, Kind kind) {
        Site best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Site s : sites) {
            if (tooClose(s, taken))
                continue;
            float sc = score(s, kind);
            // Строгое сравнение: список обходится в одном и том же порядке,
            // поэтому при равенстве побеждает первый — разведка детерминирована.
            if (sc > bestScore) {
                bestScore = sc;
                best = s;
            }
        }
        return best;
    }

    private static boolean tooClose(Site s, List<Site> taken) {
        for (Site t : taken)
            if (Math.hypot(s.x() - t.x(), s.z() - t.z()) < MIN_SEPARATION)
                return true;
        return false;
    }

    private static float score(Site s, Kind kind) {
        int relief = s.hi() - s.lo();
        return switch (kind) {
            case MOUNTAIN -> relief * 1.0f + s.alpine() * 3.0f
                    + Math.max(0, s.avg() - World.SEA_LEVEL) * 0.5f - s.water() * 1.5f;
            // Русло реки или озеро: вода есть, но не половина кадра, и берега
            // не плоские.
            case VALLEY -> (s.water() >= 3 && s.water() <= 18 ? 45f : 0f)
                    + relief * 0.9f + s.kinds() * 7f - Math.abs(s.water() - 9) * 1.5f;
            case COAST -> (s.water() >= 14 && s.water() <= 32 ? 45f : 0f)
                    + relief * 0.5f + s.kinds() * 5f;
            case FOREST -> s.treeish() * 2.2f + relief * 0.8f + s.kinds() * 5f - s.water() * 0.6f;
            case COLD -> s.cold() * 2.4f + relief * 0.9f + s.kinds() * 4f;
        };
    }

    // ---------------- постановка кадра ----------------

    private static MenuShot compose(World world, Site s, Kind kind, int index) {
        Hero hero = switch (kind) {
            case VALLEY, COAST -> Hero.WATER;
            case MOUNTAIN -> Hero.HIGH;
            case FOREST, COLD -> Hero.MIDDLE;
        };
        float heroX = switch (hero) {
            case WATER -> s.wetX();
            case HIGH -> s.hiX();
            case MIDDLE -> s.x();
        };
        float heroZ = switch (hero) {
            case WATER -> s.wetZ();
            case HIGH -> s.hiZ();
            case MIDDLE -> s.z();
        };
        // Откуда смотрим: с низкой стороны на высокую, а на воду — с берега.
        float awayX = hero == Hero.WATER ? s.hiX() - heroX : s.loX() - heroX;
        float awayZ = hero == Hero.WATER ? s.hiZ() - heroZ : s.loZ() - heroZ;
        float len = (float) Math.hypot(awayX, awayZ);
        if (len < 1e-3f) {           // ровная площадка — курс всё равно нужен
            awayX = 1f; awayZ = 0f; len = 1f;
        }
        awayX /= len;
        awayZ /= len;
        if (kind == Kind.COLD) {
            // Сияние висит над северным горизонтом (−X), и оно здесь главное:
            // камера встаёт с юга, чтобы занавес был в кадре.
            awayX = 1f;
            awayZ = 0f;
        }

        float bearing = (float) Math.atan2(awayZ, awayX);
        float sweep = (index % 2 == 0 ? 1f : -1f) * SWEEP;
        float b0 = bearing - sweep * 0.5f, b1 = bearing + sweep * 0.5f;

        float fromX = heroX + (float) Math.cos(b0) * DIST_FROM;
        float fromZ = heroZ + (float) Math.sin(b0) * DIST_FROM;
        float toX = heroX + (float) Math.cos(b1) * DIST_TO;
        float toZ = heroZ + (float) Math.sin(b1) * DIST_TO;

        // Высота камеры — не ниже земли под ней и не ниже, чем нужно для
        // взгляда сверху вниз на цель; потолок мира бьёт и то и другое.
        float aim = hero == Hero.WATER
                ? World.SEA_LEVEL + 5f
                : world.terrainHeight(Math.round(heroX), Math.round(heroZ)) + 8f;
        float underFrom = floor(world, fromX, fromZ), underTo = floor(world, toX, toZ);
        float fromY = Math.min(Math.min(CEILING, underFrom + MAX_ABOVE), Math.max(
                underFrom + RISE_FROM, aim + DIST_FROM * TAN_MAX));
        float toY = Math.min(Math.min(CEILING - 3f, underTo + MAX_ABOVE), Math.max(
                underTo + RISE_TO, aim + DIST_TO * TAN_MAX));
        // Если камера упёрлась в потолок, вниз едет цель: взгляд обязан идти
        // сверху вниз, иначе в кадре одно небо.
        float targetY = Math.min(aim, Math.min(
                fromY - DIST_FROM * TAN_MIN, toY - DIST_TO * TAN_MIN));

        // Контрольная точка дуги: середина, вынесенная наружу по нормали —
        // прямой отрезок между двумя опорами читается как рельсы.
        float midX = (fromX + toX) * 0.5f, midZ = (fromZ + toZ) * 0.5f;
        float outX = midX - heroX, outZ = midZ - heroZ;
        float outLen = Math.max(1e-3f, (float) Math.hypot(outX, outZ));
        float ctrlX = midX + outX / outLen * BULGE;
        float ctrlZ = midZ + outZ / outLen * BULGE;
        float ctrlY = (fromY + toY) * 0.5f + 3f;

        MenuShot.Path path = new MenuShot.Path(
                fromX, fromY, fromZ, toX, toY, toZ, ctrlX, ctrlY, ctrlZ,
                // Взгляд доезжает до героя, а не стоит на нём с первого кадра:
                // лёгкий увод даёт кадру движение даже там, где рельеф ровный.
                heroX - awayZ * 12f, targetY + 4f, heroZ + awayX * 12f,
                heroX, targetY, heroZ,
                DURATION, fov(kind));
        path = lift(world, path);
        return new MenuShot(title(kind), path, air(kind, path), s.biome());
    }

    /** Земля под точкой, но не ниже уровня моря: над водой камера тоже летит. */
    private static float floor(World world, float x, float z) {
        return Math.max(World.SEA_LEVEL,
                world.terrainHeight(Math.round(x), Math.round(z)));
    }

    /**
     * Поднимает всю траекторию, если она где-то задевает рельеф, и держит её
     * под потолком мира. Проверяется по самой траектории, а не по опорам:
     * дуга выгибается наружу и может пройти сквозь соседний холм.
     */
    private static MenuShot.Path lift(World world, MenuShot.Path path) {
        MenuShot probe = new MenuShot("probe", path, air(Kind.MOUNTAIN, path), Biome.PLAINS);
        MenuShot.Pose p = new MenuShot.Pose();
        float need = 0f;
        for (int i = 0; i <= 48; i++) {
            probe.pose(i / 48f, p);
            int ground = world.terrainHeight((int) Math.floor(p.x), (int) Math.floor(p.z));
            need = Math.max(need, ground + CLEARANCE - p.y);
        }
        float top = Math.max(path.fromY(), Math.max(path.toY(), path.ctrlY()));
        // Только вверх и только под потолок: опустить траекторию значило бы
        // увести камеру ниже собственной цели.
        float d = Math.max(0f, Math.min(need, CEILING - top));
        if (d < 0.01f)
            return path;
        return new MenuShot.Path(
                path.fromX(), path.fromY() + d, path.fromZ(),
                path.toX(), path.toY() + d, path.toZ(),
                path.ctrlX(), path.ctrlY() + d, path.ctrlZ(),
                path.lookFromX(), path.lookFromY(), path.lookFromZ(),
                path.lookToX(), path.lookToY(), path.lookToZ(),
                path.duration(), path.fovDeg());
    }

    private static float fov(Kind kind) {
        // Гора снимается длиннофокусно — planes сжимаются и хребет читается
        // слоями; долина и берег требуют простора.
        return switch (kind) {
            case MOUNTAIN -> 55f;
            case VALLEY -> 72f;
            case COAST -> 68f;
            case FOREST -> 64f;
            case COLD -> 70f;
        };
    }

    private static String title(Kind kind) {
        return switch (kind) {
            case MOUNTAIN -> "ridge";
            case VALLEY -> "valley";
            case COAST -> "coast";
            case FOREST -> "forest";
            case COLD -> "snow";
        };
    }

    /**
     * Время суток выбирается под курс камеры: солнце в этом мире ходит по оси
     * Z (восход в −Z), поэтому «против света» и «по свету» — это выбор утра
     * или вечера, а не поворот камеры. Так контровой свет достаётся кадру
     * всегда, независимо от того, куда повернул его рельеф.
     */
    private static MenuShot.Air air(Kind kind, MenuShot.Path path) {
        float lookZ = path.lookToZ() - path.fromZ();
        boolean lookingEast = lookZ < 0f;   // солнце встаёт в −Z
        return switch (kind) {
            // Золотой час против света: хребет встаёт силуэтом, из-за него
            // растут лучи.
            case MOUNTAIN -> new MenuShot.Air(
                    sun(0.30f, lookingEast), 0.010f,
                    0.18f, 0f, 0f, 0f, 1.6f, 0.9f,
                    0.003f, 0.0010f, 0.96f, 1.08f,
                    0.55f, 62f, 34f, 0f);
            // Рассвет над водой по свету: низовая мгла в русле подсвечена.
            case VALLEY -> new MenuShot.Air(
                    sun(0.24f, !lookingEast), 0.012f,
                    0.28f, 0f, 0f, 0f, 1.1f, -0.6f,
                    0.020f, 0.0018f, 0.99f, 1.05f,
                    0.70f, 48f, 26f, 0f);
            // День клонится к вечеру: солнечная дорожка на воде.
            case COAST -> new MenuShot.Air(
                    sun(0.52f, lookingEast), 0.010f,
                    0.30f, 0f, 0f, 0f, 2.4f, 1.4f,
                    0.0025f, 0.0010f, 0.97f, 1.07f,
                    0.45f, 70f, 40f, 0f);
            // Ливень: небо затянуто, света мало, всё в дымке.
            case FOREST -> new MenuShot.Air(
                    1.15f, 0.008f,
                    0.74f, 0.16f, 0.62f, 0f, 3.0f, -1.8f,
                    0.009f, 0.0022f, 1.12f, 0.96f,
                    0.60f, 44f, 26f, 0f);
            // Ночь: сияние над северным горизонтом и позёмка.
            case COLD -> new MenuShot.Air(
                    4.35f, 0.010f,
                    0.12f, 0.10f, 0f, 0.55f, 2.2f, 0.8f,
                    0.012f, 0.0018f, 1.10f, 1.02f,
                    0.50f, 55f, 32f, 0.9f);
        };
    }

    /** Время суток с солнцем на заданной высоте, утром или вечером. */
    private static float sun(float elevation, boolean morning) {
        return morning ? elevation : (float) Math.PI - elevation;
    }

    /** Чанк, в котором стоит точка. Разведке и расписанию удобнее в чанках. */
    static long chunkKeyAt(float x, float z) {
        return World.key(Math.floorDiv((int) Math.floor(x), Chunk.SIZE_X),
                Math.floorDiv((int) Math.floor(z), Chunk.SIZE_Z));
    }
}
