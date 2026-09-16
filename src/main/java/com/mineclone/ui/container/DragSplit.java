package com.mineclone.ui.container;

/**
 * Как делится стопка, протянутая по слотам.
 *
 * <p>Отдельно от окна и без единой ссылки на мир: правило деления — это
 * арифметика, и проверять её надо числами, а не кликами по экрану.
 */
public final class DragSplit {

    private DragSplit() {}

    /**
     * Сколько предметов достанется каждому слоту.
     *
     * <p>Левой кнопкой — поровну, целой частью от деления; правой — по одному.
     * Остаток не размазывается по слотам, а остаётся на курсоре: игрок,
     * протянувший восемь предметов по трём слотам, ждёт 2/2/2 и двойку в руке,
     * а не 3/3/2.
     *
     * @param existing сколько уже лежит в каждом слоте
     * @param limits   потолок каждого слота
     * @return прибавка каждому слоту; остаток = {@code cursorCount} − сумма
     */
    public static int[] distribute(int cursorCount, boolean right, int[] existing, int[] limits) {
        int n = existing.length;
        int[] out = new int[n];
        if (n == 0 || cursorCount <= 0)
            return out;
        int share = right ? 1 : cursorCount / n;
        if (share <= 0)
            return out;
        int left = cursorCount;
        for (int i = 0; i < n && left > 0; i++) {
            int space = Math.max(0, limits[i] - existing[i]);
            int add = Math.min(Math.min(share, space), left);
            out[i] = add;
            left -= add;
        }
        return out;
    }
}
