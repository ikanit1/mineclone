package com.mineclone.world.entity;

import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Всё, что дерево поведения знает про текущий тик.
 *
 * Изменяемый объект, а не record: он переиспользуется мобом из кадра в кадр,
 * и заводить мусор на каждый тик каждого моба ради красоты — плохая сделка в
 * цикле, который крутится шестьдесят раз в секунду.
 */
public final class MobContext {
    public World world;
    public Vector3f playerPos;
    public float dt;
    public float daylight;
    /** Вектор от моба к игроку по горизонтали. */
    public float dx, dz;
    /** Полная дистанция до игрока, включая высоту. */
    public float dist;

    void set(World world, Vector3f playerPos, float dt, float daylight,
             float dx, float dz, float dist) {
        this.world = world;
        this.playerPos = playerPos;
        this.dt = dt;
        this.daylight = daylight;
        this.dx = dx;
        this.dz = dz;
        this.dist = dist;
    }
}
