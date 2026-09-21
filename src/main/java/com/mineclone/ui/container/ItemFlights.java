package com.mineclone.ui.container;

import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Перелёт иконки из слота в слот.
 *
 * <p>Shift-клик перекладывает стопку мгновенно, и без перелёта игрок видит
 * только то, что предмет пропал — куда именно, приходится искать глазами.
 * Летящая иконка отвечает на этот вопрос сама.
 *
 * <p>Слот назначения прячет свою иконку, пока перелёт не сел: иначе предмет
 * оказывается в двух местах сразу.
 */
public final class ItemFlights {

    /** Сколько длится перелёт: дольше — и окно начинает тормозить руку. */
    public static final float TIME = 0.18f;

    private static final class Flight {
        final ItemStack icon;
        final float fromX, fromY, toX, toY;
        final SlotRef target;
        float t;

        Flight(ItemStack icon, float fromX, float fromY, float toX, float toY, SlotRef target) {
            this.icon = icon;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.target = target;
        }
    }

    /** Где сейчас летящая иконка и насколько она сжата. */
    public interface FlightSink {
        void flight(ItemStack icon, float x, float y, float scale);
    }

    private final List<Flight> flights = new ArrayList<>();

    public void launch(ItemStack icon, float fromX, float fromY, float toX, float toY,
            SlotRef target) {
        if (icon == null)
            return;
        flights.add(new Flight(icon.copy(), fromX, fromY, toX, toY, target));
    }

    public void update(float dt) {
        for (int i = flights.size() - 1; i >= 0; i--) {
            Flight f = flights.get(i);
            f.t += dt;
            if (f.t >= TIME)
                flights.remove(i);
        }
    }

    /** Прячет ли слот свою иконку — к нему ещё летят. */
    public boolean hides(SlotRef slot) {
        if (slot == null)
            return false;
        for (Flight f : flights)
            if (slot.equals(f.target))
                return true;
        return false;
    }

    public boolean isEmpty() {
        return flights.isEmpty();
    }

    public void clear() {
        flights.clear();
    }

    public void forEach(FlightSink sink) {
        for (Flight f : flights) {
            float k = ease(Math.min(1f, f.t / TIME));
            float x = f.fromX + (f.toX - f.fromX) * k;
            float y = f.fromY + (f.toY - f.fromY) * k;
            // Иконка чуть подрастает на взлёте и садится в размер слота:
            // прямой перенос без этого читается как телепорт.
            float scale = 1f + 0.18f * (float) Math.sin(k * Math.PI);
            sink.flight(f.icon, x, y, scale);
        }
    }

    /** Плавный старт и плавная посадка; линейный полёт выглядит механическим. */
    public static float ease(float t) {
        return t * t * (3f - 2f * t);
    }
}
