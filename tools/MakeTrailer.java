import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Собирает нарезку «было / стало» из уже снятых кадров.
 *
 * Кадры майско-июньской версии снимает {@code ShotJune} под её собственный
 * API (инструментов превью тогда не существовало), нынешние —
 * {@code RenderVersionShot} тем же сидом и с той же точки. Здесь они только
 * складываются в сравнения и в анимацию.
 *
 * <p>GIF, а не видео: ffmpeg в сборочном окружении нет, а {@code ImageIO}
 * умеет писать анимированный GIF сам. Палитра у GIF на 256 цветов, поэтому
 * кадры сначала уменьшаются — на уменьшенном дизеринг заметен меньше.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools\MakeTrailer.java &lt;папка-июньских-кадров&gt;
 */
public class MakeTrailer {

    static final int W = 960, H = 540;
    /** Сколько миллисекунд держится обычный кадр и титр. */
    static final int HOLD = 1400, TITLE = 1100;

    static final Color INK = new Color(0xF2F2F5);
    static final Color DIM = new Color(0x9AA0A6);
    static final Color BG = new Color(0x0E0F13);

    public static void main(String[] args) throws Exception {
        Path june = Path.of(args.length > 0 ? args[0] : "out-test/shots-june");
        Path now = Path.of("out-test/version-shot");
        Path shots = Path.of("out-test/previews");
        Path auto = Path.of("out-test/autopilot/shots");
        Path out = Path.of("out-test/trailer");
        Files.createDirectories(out);

        String[] phases = { "day", "dusk", "dawn", "night" };
        String[] titles = { "Полдень", "Закат", "Рассвет", "Ночь" };

        // --- сравнения «было / стало» -------------------------------------
        List<BufferedImage> reel = new ArrayList<>();
        reel.add(title("MINECLONE", "май — июнь 2026   →   сентябрь 2026", null));

        for (int i = 0; i < phases.length; i++) {
            BufferedImage a = read(june.resolve("june-" + phases[i] + ".png"));
            BufferedImage b = read(now.resolve("now-" + phases[i] + ".png"));
            if (a == null || b == null) {
                System.out.println("skip " + phases[i] + " (missing frame)");
                continue;
            }
            BufferedImage pair = sideBySide(a, b, titles[i]);
            ImageIO.write(pair, "png", out.resolve("compare-" + phases[i] + ".png").toFile());
            reel.add(scale(pair, W, H));
        }

        // --- чего в июне не было вовсе -------------------------------------
        reel.add(title("ЧЕГО НЕ БЫЛО", "ни одной из этих систем в июне не существовало", null));
        String[][] features = {
            { shots.resolve("atmo-lightning.png").toString(), "Гроза", "вспышка освещает мир, гром идёт с задержкой" },
            { shots.resolve("atmo-blizzard.png").toString(), "Метель", "осадки целиком на видеокарте" },
            { shots.resolve("atmo-aurora.png").toString(), "Северное сияние", "фазы луны и ночное небо" },
            { shots.resolve("mobs-1280-0.png").toString(), "Мобы", "скелет стреляет, паук быстр, крипер взрывается" },
            { shots.resolve("atmo-juice.png").toString(), "Предметы и стрелы", "обломки, дроп, воткнувшиеся стрелы" },
            { shots.resolve("tool-templates.png").toString(), "Оружие в руке", "меч, лук на трёх стадиях натяжения" },
            { auto.resolve("01-title.png").toString(), "Меню", "кинематографичный фон, миры, настройки" },
            { auto.resolve("10-window-inventory.png").toString(), "Инвентарь и крафт", "сундук, печь, верстак, творческий режим" },
        };
        for (String[] f : features) {
            BufferedImage img = read(Path.of(f[0]));
            if (img == null) { System.out.println("skip " + f[1]); continue; }
            reel.add(captioned(scale(fit(img), W, H), f[1], f[2]));
        }

        reel.add(title("v1.1.0-alpha", "432 автотеста · протокол сети v6 · выделенный сервер", DIM));

        writeGif(out.resolve("mineclone-trailer.gif").toFile(), reel);
        System.out.println("frames in reel: " + reel.size());
        System.out.println("wrote " + out.resolve("mineclone-trailer.gif").toAbsolutePath());
    }

    static BufferedImage read(Path p) {
        try {
            return Files.exists(p) ? ImageIO.read(p.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Обрезает картинку до 16:9 по центру, чтобы галереи не плющило. */
    static BufferedImage fit(BufferedImage src) {
        float want = W / (float) H;
        int w = src.getWidth(), h = src.getHeight();
        if (Math.abs(w / (float) h - want) < 0.01f)
            return src;
        int cw = w, ch = (int) (w / want);
        if (ch > h) { ch = h; cw = (int) (h * want); }
        return src.getSubimage((w - cw) / 2, Math.min((h - ch) / 2, h - ch), cw, ch);
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Две версии рядом, с подписью времени суток и ярлыками. */
    static BufferedImage sideBySide(BufferedImage a, BufferedImage b, String phase) {
        int pw = 940, ph = 528, gap = 12, pad = 22, head = 74;
        BufferedImage out = new BufferedImage(pw * 2 + gap + pad * 2, ph + head + pad,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString(phase, pad, 46);

        g.drawImage(a, pad, head, pw, ph, null);
        g.drawImage(b, pad + pw + gap, head, pw, ph, null);

        tag(g, pad + 14, head + 14, "ИЮНЬ", new Color(0x60, 0x66, 0x70));
        tag(g, pad + pw + gap + 14, head + 14, "СЕЙЧАС", new Color(0x2E, 0x7D, 0x5B));
        g.dispose();
        return out;
    }

    static void tag(Graphics2D g, int x, int y, String text, Color fill) {
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        int w = g.getFontMetrics().stringWidth(text) + 24;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(x, y, w, 34);
        g.setColor(fill);
        g.fillRect(x, y, 5, 34);
        g.setColor(INK);
        g.drawString(text, x + 14, y + 24);
    }

    /** Кадр с полосой подписи внизу. */
    static BufferedImage captioned(BufferedImage src, String head, String sub) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int bar = 96;
        g.setColor(new Color(0, 0, 0, 165));
        g.fillRect(0, out.getHeight() - bar, out.getWidth(), bar);
        g.setColor(new Color(0x2E, 0x7D, 0x5B));
        g.fillRect(0, out.getHeight() - bar, 6, bar);
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(head, 26, out.getHeight() - bar + 40);
        g.setColor(DIM);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        g.drawString(sub, 26, out.getHeight() - bar + 70);
        g.dispose();
        return out;
    }

    static BufferedImage title(String head, String sub, Color accent) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(accent == null ? INK : accent);
        g.setFont(new Font("SansSerif", Font.BOLD, 62));
        int tw = g.getFontMetrics().stringWidth(head);
        g.drawString(head, (W - tw) / 2, H / 2 - 6);
        g.setColor(DIM);
        g.setFont(new Font("SansSerif", Font.PLAIN, 23));
        int sw = g.getFontMetrics().stringWidth(sub);
        g.drawString(sub, (W - sw) / 2, H / 2 + 42);
        g.setColor(new Color(0x2E, 0x7D, 0x5B));
        g.fillRect((W - 120) / 2, H / 2 + 68, 120, 4);
        g.dispose();
        return out;
    }

    /** Анимированный GIF: задержка на кадр, бесконечный повтор. */
    static void writeGif(File file, List<BufferedImage> frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (FileImageOutputStream stream = new FileImageOutputStream(file)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                BufferedImage f = frames.get(i);
                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(f), params);
                configure(meta, i == 0, HOLD);
                writer.writeToSequence(new IIOImage(f, null, meta), params);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
    }

    /**
     * Задержка кадра и зацикливание.
     *
     * Повтор задаётся расширением NETSCAPE в блоке приложений — иначе GIF
     * проигрывается один раз, и нарезка обрывается на последнем титре.
     */
    static void configure(IIOMetadata meta, boolean first, int delayMs) throws Exception {
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", String.valueOf(Math.round(delayMs / 10f)));
        gce.setAttribute("transparentColorIndex", "0");

        if (first) {
            IIOMetadataNode apps = child(root, "ApplicationExtensions");
            IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[] { 0x1, 0, 0 });   // 0 = бесконечно
            apps.appendChild(app);
        }
        meta.setFromTree(format, root);
    }

    static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++)
            if (root.item(i).getNodeName().equalsIgnoreCase(name))
                return (IIOMetadataNode) root.item(i);
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
