package com.mineclone.render;

/**
 * Текст в экранных пикселях (начало — левый верхний угол).
 *
 * <p>Своей программы и своего буфера у текста больше нет: буквы уходят в тот
 * же пакет, что и подложки под ними. Цифра на иконке и сама иконка — это один
 * слой интерфейса, и разрывать из-за неё пакет означало бы платить за каждую
 * надпись отдельным вызовом драйвера.
 *
 * <p>Вне открытого пакета рисовальщик открывает и закрывает его сам: отладочный
 * оверлей и тосты вызываются поодиночке и о пакете ничего не знают.
 */
public class TextRenderer {

    private final UiRenderer ui;

    public TextRenderer(UiRenderer ui) {
        this.ui = ui;
    }

    public void draw(Font font, String text, float x, float y, int screenW, int screenH,
                     float r, float g, float b, float a) {
        if (text == null || text.isEmpty())
            return;
        boolean standalone = !ui.isOpen();
        if (standalone)
            ui.begin(screenW, screenH);
        ui.glyphs(font, text, x, y, r, g, b, a);
        if (standalone)
            ui.end();
    }

    /** Draws shadow (1px down/right) then main color. */
    public void drawShadowed(Font font, String text, float x, float y, int screenW, int screenH,
                             float r, float g, float b) {
        boolean standalone = !ui.isOpen();
        if (standalone)
            ui.begin(screenW, screenH);
        ui.glyphs(font, text, x + 1, y + 1, 0f, 0f, 0f, 0.7f);
        ui.glyphs(font, text, x, y, r, g, b, 1f);
        if (standalone)
            ui.end();
    }

    /**
     * Текст с чёрной обводкой со всех сторон.
     *
     * Одной тени вниз-вправо хватает на панели, но не поверх иконки: счётчик
     * стопки ложится прямо на текстуру блока, и там светлая цифра теряется на
     * светлом пикселе, а тень — на тёмном.
     */
    public void drawOutlined(Font font, String text, float x, float y, int screenW, int screenH,
                             float r, float g, float b) {
        boolean standalone = !ui.isOpen();
        if (standalone)
            ui.begin(screenW, screenH);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0)
                    ui.glyphs(font, text, x + dx, y + dy, 0f, 0f, 0f, 0.85f);
        ui.glyphs(font, text, x, y, r, g, b, 1f);
        if (standalone)
            ui.end();
    }

    public void destroy() {
        // Ресурсов своих нет — всё живёт в UiRenderer.
    }
}
