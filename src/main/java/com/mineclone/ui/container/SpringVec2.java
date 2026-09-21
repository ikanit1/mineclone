package com.mineclone.ui.container;

/** Пружина по двум осям: курсор, подсказка, всё, что едет по экрану. */
public final class SpringVec2 {

    public final Spring x;
    public final Spring y;

    public SpringVec2(float frequency, float dampingRatio) {
        x = new Spring(frequency, dampingRatio);
        y = new Spring(frequency, dampingRatio);
    }

    public void update(float targetX, float targetY, float dt) {
        x.update(targetX, dt);
        y.update(targetY, dt);
    }

    public void snap(float vx, float vy) {
        x.snap(vx);
        y.snap(vy);
    }

    public float valueX() {
        return x.value;
    }

    public float valueY() {
        return y.value;
    }
}
